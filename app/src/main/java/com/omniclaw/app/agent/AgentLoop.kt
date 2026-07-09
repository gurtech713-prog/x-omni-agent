package com.omniclaw.app.agent

import android.content.Context
import android.util.Log
import com.omniclaw.app.agent.tools.DeviceAction
import com.omniclaw.app.agent.tools.DeviceScheduler
import com.omniclaw.app.agent.learning.EpisodeRecorder
import com.omniclaw.app.agent.learning.LearningEngine
import com.omniclaw.app.agent.verifier.SuccessMonitor
import com.omniclaw.app.behavior.BehaviorRecorder
import com.omniclaw.app.data.llm.LlmClient
import com.omniclaw.app.data.llm.LlmException
import com.omniclaw.app.data.llm.UnifiedLlmClient
import com.omniclaw.app.data.model.ChatMessage
import com.omniclaw.app.data.model.LlmUsage
import com.omniclaw.app.data.model.Session
import com.omniclaw.app.data.model.SessionStatus
import com.omniclaw.app.data.model.ToolCall
import com.omniclaw.app.data.prefs.SettingsRepository
import com.omniclaw.app.data.session.SessionRepository
import com.omniclaw.app.deeplink.DeepLinkManager
import com.omniclaw.app.gallery.GalleryScanner
import com.omniclaw.app.logging.AgentLogger
import com.omniclaw.app.service.AgentForegroundService
import com.omniclaw.app.service.HaloOverlayService
import com.omniclaw.app.service.ScreenCaptureService
import com.omniclaw.app.vision.VlmClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observation -> Reasoning -> Execution loop.
 *
 * Implements the X-OmniClaw execution methodology: at each step, the agent
 *  1) perceives the current screen + previous action outcome (observation),
 *  2) calls the LLM to interpret the screen and pick the next action (reasoning),
 *  3) dispatches the concrete Android action via DeviceScheduler (execution).
 *  4) verifies the result via SuccessMonitor and decides whether to continue.
 *
 * Dual-track decisions (per the original's "vision fallback" feature):
 *   - Prefer structured accessibility tree as observation.
 *   - If the tree is empty / very small / unparseable, fall back to vision:
 *     capture a screenshot and ask the VLM to describe what to tap.
 *
 * The loop is bounded by maxSteps and detects cycles using a hash of recent
 * action signatures. Failures converge — the LLM is shown the failure and
 * asked to retry with a different plan.
 */
@Singleton
class AgentLoop @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val llm: UnifiedLlmClient,
    private val scheduler: DeviceScheduler,
    private val verifier: SuccessMonitor,
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
    private val vlm: VlmClient,
    private val behaviorRecorder: BehaviorRecorder,
    private val logger: AgentLogger,
    private val gallery: GalleryScanner,
    private val deepLinks: DeepLinkManager,
    private val learning: LearningEngine,
    private val episodeRecorder: EpisodeRecorder,
) {

    /** Number of currently-running sessions; used to start/stop the foreground service. */
    private val activeCount = AtomicInteger(0)

    sealed class Event {
        abstract val sessionId: String

        data class StepStarted(override val sessionId: String, val step: Int) : Event()
        data class Thought(override val sessionId: String, val step: Int, val text: String) : Event()
        data class ToolCall(override val sessionId: String, val step: Int, val call: com.omniclaw.app.data.model.ToolCall) : Event()
        data class StepFinished(override val sessionId: String, val step: Int, val usage: LlmUsage) : Event()
        /** Emitted when learned lessons are injected into the system prompt.
         *  Lets the chat UI show a "applied N lessons" hint for transparency. */
        data class LessonsApplied(override val sessionId: String, val step: Int, val lessonCount: Int) : Event()
        data class LoopDetected(override val sessionId: String) : Event()
        data class Failed(override val sessionId: String, val error: String) : Event()
        data class Completed(override val sessionId: String, val finalText: String) : Event()
        data class Stopped(override val sessionId: String) : Event()
    }

    // DROP_OLDEST: if a collector (Halo, ChatViewModel) is slow, drop the oldest
    // event rather than suspending the agent loop mid-step. The agent must never
    // stall due to UI back-pressure.
    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 128,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val stopSet = mutableSetOf<String>()
    private val stopMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(session: Session, prompt: String) {
        // Save the user's prompt FIRST so it's visible even if we bail out.
        sessions.appendMessage(
            session.id,
            ChatMessage(UUID.randomUUID().toString(), ChatMessage.Role.USER, prompt, System.currentTimeMillis())
        )
        // Start recording the episode for self-learning reflection.
        episodeRecorder.start(session.id, prompt)
        // NOTE: We no longer hard-fail when the accessibility service isn't
        // connected. Many user prompts are conversational ("what's the
        // weather", "summarize this", "explain X") and don't need device
        // automation — the LLM can answer directly. If a device action is
        // later required (tap/swipe/type/launch), the dispatch will return
        // false and the loop will tell the LLM to retry with a non-device
        // approach. Previously this pre-flight check made the entire chat
        // unusable until the user manually enabled accessibility, which most
        // users never did — so "chat doesn't work" was the #1 reported issue.
        sessions.setStatus(session.id, SessionStatus.RUNNING)
        // Start the foreground service + Halo overlay when the first session
        // becomes active, so the loop survives backgrounding and the user sees
        // live status via the Dynamic Island-style pill.
        if (activeCount.getAndIncrement() == 0) {
            runCatching { AgentForegroundService.start(ctx) }
            runCatching { HaloOverlayService.start(ctx) }
        }
        scope.launch { runLoop(session.id, prompt) }
    }

    suspend fun stop(sessionId: String) {
        stopMutex.withLock { stopSet.add(sessionId) }
        sessions.stop(sessionId)
        verifier.reset(sessionId)
        _events.tryEmit(Event.Stopped(sessionId))
    }

    private suspend fun runLoop(sessionId: String, prompt: String) {
        try {
            runLoopInner(sessionId, prompt)
        } catch (e: Exception) {
            Log.e(TAG, "Agent loop crashed: ${e.message}", e)
            sessions.appendMessage(
                sessionId,
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatMessage.Role.SYSTEM,
                    content = "Agent loop crashed: ${e.message}",
                    timestamp = System.currentTimeMillis()
                )
            )
            _events.tryEmit(Event.Failed(sessionId, e.message ?: "Crash"))
            sessions.setStatus(sessionId, SessionStatus.FAILED)
        } finally {
            // Decrement active session count; stop the foreground service + Halo
            // when no sessions are running anymore.
            if (activeCount.decrementAndGet() <= 0) {
                runCatching { AgentForegroundService.stop(ctx) }
                runCatching { HaloOverlayService.stop(ctx) }
            }
            // Clean up per-session verifier state + stopSet entry (prevents
            // unbounded growth of stopSet across many sessions).
            verifier.reset(sessionId)
            stopMutex.withLock { stopSet.remove(sessionId) }
        }
    }

    private suspend fun runLoopInner(sessionId: String, prompt: String) {
        val cfg = settings.modelConfig.first()

        // Guard: validate config before starting the loop. Give the user a clear
        // error instead of a cryptic HTTP 401 or timeout.
        if (cfg.provider == com.omniclaw.app.data.prefs.LlmProvider.OPENAI_COMPAT) {
            if (cfg.baseUrl.isBlank()) {
                val errMsg = "No Base URL configured. Go to Settings → AI Provider and enter your endpoint (e.g. https://api.openai.com/v1)."
                sessions.appendMessage(sessionId, ChatMessage(java.util.UUID.randomUUID().toString(), ChatMessage.Role.SYSTEM, errMsg, System.currentTimeMillis()))
                _events.tryEmit(Event.Failed(sessionId, errMsg))
                sessions.setStatus(sessionId, SessionStatus.FAILED)
                return
            }
            if (cfg.apiKey.isBlank()) {
                val errMsg = "No API key configured. Go to Settings → AI Provider and enter your API key."
                sessions.appendMessage(sessionId, ChatMessage(java.util.UUID.randomUUID().toString(), ChatMessage.Role.SYSTEM, errMsg, System.currentTimeMillis()))
                _events.tryEmit(Event.Failed(sessionId, errMsg))
                sessions.setStatus(sessionId, SessionStatus.FAILED)
                return
            }
        } else if (cfg.provider == com.omniclaw.app.data.prefs.LlmProvider.GEMINI) {
            if (cfg.apiKey.isBlank()) {
                val errMsg = "No Gemini API key configured. Go to Settings → AI Provider and enter your Google AI Studio key."
                sessions.appendMessage(sessionId, ChatMessage(java.util.UUID.randomUUID().toString(), ChatMessage.Role.SYSTEM, errMsg, System.currentTimeMillis()))
                _events.tryEmit(Event.Failed(sessionId, errMsg))
                sessions.setStatus(sessionId, SessionStatus.FAILED)
                return
            }
        }

        val recentActions = ArrayDeque<Pair<String, String>>()  // (actionSig, fingerprint)
        val maxSteps = 24
        // Token budget guard — prevents runaway sessions from burning unlimited
        // tokens. When cumulative usage exceeds this, force DONE with a clear
        // message. Default 30k tokens (~$0.30 on GPT-4o-mini, ~$0.90 on Opus).
        val maxSessionTokens = 30_000L
        var sessionTokens = 0L

        for (step in 1..maxSteps) {
            if (isStopRequested(sessionId)) return
            _events.tryEmit(Event.StepStarted(sessionId, step))

            // ---- Refresh history each step so the LLM sees its own prior
            // thoughts, tool-call results, and any "action failed" system notes.
            // This closes the verify -> retry half of the four-layer loop. ----
            val history = sessions.getById(sessionId)?.messages.orEmpty().map {
                LlmClient.Message(role = it.role.name.lowercase(), content = it.content)
            }

            // ---- Dual-track observation: structured tree preferred, vision fallback ----
            var observation = scheduler.snapshot()
            var usedVision = false
            // Heuristic: if the tree is empty / very short / looks unparseable, ask the VLM.
            if (observation.isBlank() || observation.length < 80 || observation.contains("not connected", ignoreCase = true)) {
                // Prefer the continuous MediaProjection stream if available, else fall back to one-shot screenshot.
                var png: ByteArray? = ScreenCaptureService.latestFramePng()
                if (png == null || png.isEmpty()) png = scheduler.screenshot()
                if (png != null && png.isNotEmpty()) {
                    val vlmAnswer = runCatching {
                        vlm.describe(png, "Describe the current screen. List interactive elements with their approximate tap coordinates (x, y). Be concise.")
                    }.getOrNull()
                    if (!vlmAnswer.isNullOrBlank()) {
                        observation = "[VISION FALLBACK]\n$vlmAnswer"
                        usedVision = true
                        Log.i(TAG, "Session $sessionId step $step: used vision fallback (${png.size} bytes)")
                    }
                }
            }
            val systemMsg = LlmClient.Message(
                role = "system",
                content = buildSystemPrompt(observation, recentActions, usedVision, sessionId, step)
            )

            val thought: String
            val usage: LlmUsage
            try {
                // ---- Streaming: collect the thought token-by-token so the UI
                // can show live progress. The stream() flow emits deltas; we
                // accumulate them into the full thought. Falls back to non-
                // streaming complete() if streaming isn't supported (e.g. LITERT
                // or if the endpoint doesn't support SSE). ----
                var streamedThought: String? = null
                runCatching {
                    val thoughtBuilder = StringBuilder()
                    llm.stream(
                        provider = cfg.provider,
                        baseUrl = cfg.baseUrl,
                        apiKey = cfg.apiKey,
                        model = cfg.model,
                        messages = listOf(systemMsg) + history,
                        temperature = cfg.temperature,
                        maxTokens = cfg.maxTokens,
                    ).collect { delta ->
                        thoughtBuilder.append(delta)
                        // Emit partial thoughts periodically so the chat UI streams.
                        if (thoughtBuilder.length % 32 < delta.length) {
                            _events.tryEmit(Event.Thought(sessionId, step, thoughtBuilder.toString() + "…"))
                        }
                    }
                    if (thoughtBuilder.isNotEmpty()) streamedThought = thoughtBuilder.toString()
                }

                if (streamedThought != null) {
                    thought = streamedThought!!
                    val estimatedTokens = (thought.length / 4).toLong()
                    usage = LlmUsage(0L, estimatedTokens, estimatedTokens)
                } else {
                    // Streaming produced nothing (or failed) — fall back to non-streaming.
                    val result = com.omniclaw.app.core.retry(
                        maxAttempts = 3,
                        retryable = { it is LlmException || it is java.io.IOException },
                    ) {
                        llm.complete(
                            provider = cfg.provider,
                            baseUrl = cfg.baseUrl,
                            apiKey = cfg.apiKey,
                            model = cfg.model,
                            messages = listOf(systemMsg) + history,
                            temperature = cfg.temperature,
                            maxTokens = cfg.maxTokens,
                        )
                    }
                    thought = result.text
                    usage = result.usage
                }
            } catch (e: Exception) {
                val errMsg = e.message ?: "LLM error"
                logger.logError(
                    AgentLogger.ErrorLocation(
                        sessionId = sessionId, step = step, action = "llm.complete",
                        className = "AgentLoop", methodName = "runLoopInner",
                        lineNumber = Thread.currentThread().stackTrace.getOrNull(1)?.lineNumber ?: -1,
                        message = errMsg,
                    )
                )
                sessions.appendMessage(
                    sessionId,
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.SYSTEM,
                        content = "Agent failed: $errMsg",
                        timestamp = System.currentTimeMillis()
                    )
                )
                _events.tryEmit(Event.Failed(sessionId, errMsg))
                sessions.setStatus(sessionId, SessionStatus.FAILED)
                triggerReflection(sessionId, "FAILED")
                return
            }

            _events.tryEmit(Event.Thought(sessionId, step, thought))
            sessions.appendMessage(
                sessionId,
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatMessage.Role.ASSISTANT,
                    content = thought,
                    timestamp = System.currentTimeMillis(),
                    thoughts = parseThoughts(thought),
                )
            )
            sessions.incSteps(sessionId)
            sessions.addTokens(sessionId, usage.totalTokens)
            sessionTokens += usage.totalTokens
            _events.tryEmit(Event.StepFinished(sessionId, step, usage))

            // ---- Token budget guard ----
            // If cumulative token usage exceeds the session budget, force DONE
            // with a clear message. Prevents runaway sessions from burning
            // unlimited API credits on verbose models (Claude Opus, etc.).
            if (sessionTokens >= maxSessionTokens) {
                val msg = "Token budget exceeded ($sessionTokens / $maxSessionTokens). Stopping to limit cost."
                _events.tryEmit(Event.Completed(sessionId, msg))
                sessions.setStatus(sessionId, SessionStatus.DONE)
                triggerReflection(sessionId, "DONE")
                return
            }

            // Parse action lines: lines starting with "ACTION:" -> "tap(x,y)" | "swipe(...)" | "type(...)" | "launch(pkg)" | "done"
            // Case-insensitive so models that respond with lowercase "action:" still work.
            val action = Regex("(?mi)^action:\\s*(.+)$").find(thought)?.groupValues?.getOrNull(1)?.trim()
            if (action == null || action.lowercase().startsWith("done")) {
                _events.tryEmit(Event.Completed(sessionId, thought))
                sessions.setStatus(sessionId, SessionStatus.DONE)
                triggerReflection(sessionId, "DONE")
                return
            }

            // ---- Content-aware cycle detection ----
            // Previous logic: flag if the same action signature appears 3 times.
            // Problem: legitimate flows repeat actions (scrolling a long list 5x).
            // New logic: flag only if the same (action, screen fingerprint) pair
            // repeats — same action on the same screen means we're stuck; same
            // action on a different screen means we're making progress.
            val sig = episodeRecorder.normalizeAction(action)
            val currentFingerprint = episodeRecorder.fingerprint(observation)
            val stuckCount = recentActions.count { it.first == sig && it.second == currentFingerprint }
            if (stuckCount >= 2) {
                _events.tryEmit(Event.LoopDetected(sessionId))
                sessions.setStatus(sessionId, SessionStatus.FAILED)
                episodeRecorder.recordLoop(sessionId, step, observation, action)
                triggerReflection(sessionId, "FAILED")
                return
            }
            recentActions.addLast(sig to currentFingerprint)
            if (recentActions.size > 6) recentActions.removeFirst()

            // Dispatch — check for skill: actions first, then fall through to device actions.
            val started = System.currentTimeMillis()
            val skillResult = handleSkillAction(action, sessionId)
            val (ok, resultText) = if (skillResult != null) {
                Pair(true, skillResult)
            } else {
                val dispatchOk = runCatching { scheduler.dispatch(parseDeviceAction(action)) }.isSuccess
                Pair(dispatchOk, if (dispatchOk) "ok" else "error")
            }
            val call = ToolCall(
                id = UUID.randomUUID().toString(),
                name = action,
                args = "",
                result = resultText,
                ok = ok,
                durationMs = System.currentTimeMillis() - started,
            )
            _events.tryEmit(Event.ToolCall(sessionId, step, call))
            // If behavior recording is active, log this action for later replay.
            behaviorRecorder.recordAction(call)
            sessions.appendMessage(
                sessionId,
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatMessage.Role.TOOL,
                    content = "${call.name} -> ${call.result}",
                    timestamp = System.currentTimeMillis(),
                    toolCalls = listOf(call),
                )
            )

            // Verify
            val verifyOk = verifier.verifyLast(sessionId, call)
            // Record this step in the episode for self-learning reflection.
            episodeRecorder.recordStep(sessionId, step, observation, action, call, verifyOk)
            // Record a direct (fingerprint, action, outcome) lesson immediately —
            // this captures concrete experience without waiting for LLM reflection.
            val fingerprint = episodeRecorder.fingerprint(observation)
            val actionSig = episodeRecorder.normalizeAction(action)
            val outcome = when {
                !call.ok -> com.omniclaw.app.data.model.Lesson.LessonOutcome.FAILURE
                !verifyOk -> com.omniclaw.app.data.model.Lesson.LessonOutcome.FAILURE
                else -> com.omniclaw.app.data.model.Lesson.LessonOutcome.SUCCESS
            }
            runCatching {
                learning.recordDirectLesson(sessionId, fingerprint, actionSig, outcome, observation)
            }
            if (!verifyOk) {
                // Tell the LLM and let it retry on next iteration
                sessions.appendMessage(
                    sessionId,
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.SYSTEM,
                        content = "Previous action failed. Try a different approach.",
                        timestamp = System.currentTimeMillis(),
                    )
                )
            }

            // ---- Inter-step delay + screen stabilization ----
            // After dispatching a tap / swipe / launch, the target app needs
            // time to animate before the next snapshot. Without this delay, the
            // next step's snapshot() captures the pre-transition screen and the
            // agent reasons about stale state — causing phantom "action didn't
            // work" failures.
            //
            // We wait 400ms, then poll the accessibility tree twice (100ms apart)
            // and only proceed once it stabilizes (two consecutive identical
            // fingerprints) or after a 1.5s cap. This balances responsiveness
            // against correctness for slow-animating apps.
            if (action.startsWith("tap", ignoreCase = true) ||
                action.startsWith("swipe", ignoreCase = true) ||
                action.startsWith("launch", ignoreCase = true) ||
                action.startsWith("back", ignoreCase = true) ||
                action.startsWith("home", ignoreCase = true)
            ) {
                kotlinx.coroutines.delay(400)
                // Wait for the screen to stabilize — compare two snapshots.
                val cap = 1_500L
                val start = System.currentTimeMillis()
                var prevFp = episodeRecorder.fingerprint(scheduler.snapshot())
                while (System.currentTimeMillis() - start < cap) {
                    kotlinx.coroutines.delay(100)
                    val curFp = episodeRecorder.fingerprint(scheduler.snapshot())
                    if (curFp == prevFp) break  // stabilized
                    prevFp = curFp
                }
            }
        }

        _events.tryEmit(Event.Completed(sessionId, "Max steps reached."))
        sessions.setStatus(sessionId, SessionStatus.DONE)
        triggerReflection(sessionId, "DONE")
    }

    /**
     * Trigger self-learning reflection on session end.
     *
     * Runs asynchronously so the agent loop returns immediately — the user
     * doesn't wait for reflection to finish before seeing "DONE". The
     * LearningEngine extracts lessons from the recorded episode and (if the
     * session succeeded with >3 steps) auto-creates a reusable SKILL.md.
     */
    private fun triggerReflection(sessionId: String, finalStatus: String) {
        episodeRecorder.finish(sessionId, finalStatus)
        scope.launch {
            runCatching { learning.reflectOnEpisode(sessionId) }
            runCatching { learning.maybeAutoCreateSkill(sessionId) }
        }
    }

    private fun parseThoughts(text: String): List<String> =
        Regex("(?m)^THOUGHT:\\s*(.+)$").findAll(text).map { it.groupValues[1].trim() }.toList()

    private fun parseDeviceAction(action: String): DeviceAction {
        val s = action.trim()
        return when {
            s.startsWith("tap", ignoreCase = true) -> {
                val m = Regex("(?i)tap\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").find(s)
                val x = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val y = m?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                DeviceAction.Tap(x, y)
            }
            s.startsWith("swipe", ignoreCase = true) -> {
                val m = Regex("(?i)swipe\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").find(s)
                DeviceAction.Swipe(
                    m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0,
                    m?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0,
                    m?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 0,
                    m?.groupValues?.getOrNull(4)?.toIntOrNull() ?: 0,
                )
            }
            s.startsWith("type", ignoreCase = true) -> {
                val m = Regex("(?i)type\\s*\\(\\s*\"?(.*?)\"?\\s*\\)").find(s)
                DeviceAction.Type(m?.groupValues?.getOrNull(1).orEmpty().trim().trim('"'))
            }
            s.startsWith("launch", ignoreCase = true) -> {
                val m = Regex("(?i)launch\\s*\\(\\s*\"?(.*?)\"?\\s*\\)").find(s)
                DeviceAction.Launch(m?.groupValues?.getOrNull(1).orEmpty().trim().trim('"'))
            }
            s.startsWith("back", ignoreCase = true) -> DeviceAction.Back
            s.startsWith("home", ignoreCase = true) -> DeviceAction.Home
            s.startsWith("screenshot", ignoreCase = true) -> DeviceAction.Screenshot
            else -> DeviceAction.NoOp
        }
    }

    /**
     * Handle a skill-action line: `skill:<skill-id>(<arg>)`.
     *
     * These are dispatched directly to the relevant manager (gallery, deep
     * links, behavior) rather than going through the accessibility service.
     * Returns a result string the agent loop can show to the user.
     */
    private fun handleSkillAction(action: String, sessionIdForSkill: String): String? {
        val m = Regex("(?i)skill:([a-z\\-]+)\\s*\\(\\s*(.*?)\\s*\\)").find(action) ?: return null
        val skillId = m.groupValues[1].lowercase()
        val arg = m.groupValues[2].trim().trim('"')
        return when (skillId) {
            "gallery-qa", "gallery-memory" -> {
                val n = arg.toIntOrNull() ?: 20
                // GalleryScanner methods are suspend — run in a coroutine and
                // return a placeholder; the result will appear in the next step.
                scope.launch {
                    val count = runCatching { gallery.syncMemory(n) }.getOrDefault(0)
                    logger.logInfo(sessionIdForSkill, 0, "gallery-sync: $count photos")
                }
                "Gallery memory sync started (scan latest $n photos)."
            }
            "gallery-search" -> {
                scope.launch {
                    val photos = runCatching { gallery.search(arg) }.getOrDefault(emptyList())
                    logger.logInfo(sessionIdForSkill, 0, "gallery-search '$arg': ${photos.size} hits")
                }
                "Gallery search started for '$arg'."
            }
            "capcut-theme-video" -> {
                scope.launch {
                    val uris = runCatching { gallery.stageForTheme(arg) }.getOrDefault(emptyList())
                    logger.logInfo(sessionIdForSkill, 0, "capcut-stage '$arg': ${uris.size} photos")
                }
                "Staging ${arg} photos for CapCut in the background."
            }
            "clipboard-to-shortcut" -> {
                val url = deepLinks.readClipboardUrl()
                    ?: return "Clipboard doesn't contain a URL."
                val id = deepLinks.saveBookmark(arg.ifBlank { "Quick link" }, url)
                "Saved bookmark '$id' for URL: $url"
            }
            "open-bookmark" -> {
                val ok = deepLinks.launchByPhrase(arg)
                if (ok) "Launched bookmark matching '$arg'." else "No bookmark matched '$arg'."
            }
            "behavior-replay" -> {
                // Launch replay as a side-effect coroutine; the loop continues
                // immediately so the agent doesn't block on the replay duration.
                scope.launch {
                    val ok = runCatching { behaviorRecorder.replay(arg) }.getOrDefault(false)
                    logger.logInfo(sessionIdForSkill, 0, "behavior-replay($arg): ${if (ok) "ok" else "failed"}")
                }
                "Replaying behavior skill '$arg' in the background."
            }
            "app-search", "amazon-search", "reddit-search" -> {
                // Launch the target app, then the agent loop's next iteration
                // will see the app's search bar via the accessibility tree and
                // can tap+type the query. We just kick off the launch here.
                val pkg = when (skillId) {
                    "amazon-search" -> "com.amazon.mShop.android.shopping"
                    "reddit-search" -> "com.reddit.frontpage"
                    else -> arg.substringBefore(':').ifBlank { "com.android.chrome" }
                }
                val query = if (skillId == "app-search" && arg.contains(':')) arg.substringAfter(':').trim() else arg
                val launched = runCatching { scheduler.dispatch(DeviceAction.Launch(pkg)) }.isSuccess
                if (launched) {
                    "Launched $pkg. Next step: tap the search bar and type '$query'."
                } else {
                    "Failed to launch $pkg. Try a different package or use the browser."
                }
            }
            "model-config" -> {
                // Parse arg as "baseUrl|apiKey|model" or "baseUrl|model"
                val parts = arg.split('|').map { it.trim() }
                scope.launch {
                    val current = settings.modelConfig.first()
                    val newCfg = when (parts.size) {
                        3 -> current.copy(baseUrl = parts[0], apiKey = parts[1], model = parts[2])
                        2 -> current.copy(baseUrl = parts[0], model = parts[1])
                        else -> current
                    }
                    settings.setModelConfig(newCfg)
                }
                "Model config updated: baseUrl=${parts.getOrNull(0)}, model=${parts.getOrNull(2) ?: parts.getOrNull(1)}. Key: ${maskKey(parts.getOrNull(1))}"
            }
            "model-provider" -> {
                // Switch the active LLM provider at runtime.
                // Arg: "openai-compat" | "gemini" | "litert"
                val provider = com.omniclaw.app.data.prefs.LlmProvider.fromString(arg)
                scope.launch {
                    val current = settings.modelConfig.first()
                    settings.setModelConfig(current.copy(provider = provider))
                }
                "Provider switched to ${provider.name}. Restart any running session to use it."
            }
            "channel-config" -> {
                // Parse arg as "feishuAppId|feishuAppSecret|feishuWebhook|discordWebhook"
                val parts = arg.split('|').map { it.trim() }
                scope.launch {
                    val current = settings.channelConfig.first()
                    val newCfg = current.copy(
                        feishuAppId = parts.getOrNull(0) ?: current.feishuAppId,
                        feishuAppSecret = parts.getOrNull(1) ?: current.feishuAppSecret,
                        feishuWebhook = parts.getOrNull(2) ?: current.feishuWebhook,
                        discordWebhook = parts.getOrNull(3) ?: current.discordWebhook,
                    )
                    settings.setChannelConfig(newCfg)
                }
                "Channel config updated: feishuAppId=${parts.getOrNull(0)?.take(8)}… · secret=${maskKey(parts.getOrNull(1))}"
            }
            "skill-creator" -> {
                // Use the LLM to draft a SKILL.md from the user's description,
                // then persist it under assets/skills/<id>/SKILL.md (runtime cache).
                scope.launch {
                    runCatching {
                        val cfg2 = settings.modelConfig.first()
                        val draftResult = llm.complete(
                            provider = cfg2.provider,
                            baseUrl = cfg2.baseUrl,
                            apiKey = cfg2.apiKey,
                            model = cfg2.model,
                            messages = listOf(
                                LlmClient.Message("system", "Draft a SKILL.md for an Android agent skill. Format: # Name\\n\\nDescription.\\n- Example utterance.\\n\\n## Tools\\n- list of tools used"),
                                LlmClient.Message("user", "Skill description: $arg"),
                            ),
                        )
                        val content = draftResult.text
                        val id = "custom-" + arg.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(24)
                        val dir = java.io.File(ctx.filesDir, "skills/$id").apply { mkdirs() }
                        java.io.File(dir, "SKILL.md").writeText(content)
                        logger.logInfo(sessionIdForSkill, 0, "skill-creator: wrote $id")
                    }
                }
                "Drafting SKILL.md for '$arg' in the background. Check Sessions > Skills to see it after refresh."
            }
            "scheduled-automation" -> {
                // Parse arg as "intervalMinutes|prompt" or "weekly:Wed:10:00|prompt"
                val parts = arg.split('|', limit = 2)
                if (parts.size < 2) return "Usage: skill:scheduled-automation(60|prompt text)"
                val scheduleSpec = parts[0].trim()
                val promptText = parts[1].trim()
                scope.launch {
                    runCatching {
                        if (scheduleSpec.startsWith("weekly:")) {
                            val dayName = scheduleSpec.substringAfter("weekly:").substringBefore(':')
                            val time = scheduleSpec.substringAfterLast(':')
                            val dayMap = mapOf("Sun" to 1, "Mon" to 2, "Tue" to 3, "Wed" to 4, "Thu" to 5, "Fri" to 6, "Sat" to 7)
                            val day = dayMap[dayName] ?: 4
                            com.omniclaw.app.cron.ScheduledTaskWorker.scheduleWeekly(
                                ctx, java.util.UUID.randomUUID().toString().take(8),
                                "Agent-created weekly", promptText, setOf(day), time,
                            )
                        } else {
                            val minutes = scheduleSpec.toLongOrNull() ?: 60L
                            com.omniclaw.app.cron.ScheduledTaskWorker.scheduleInterval(
                                ctx, java.util.UUID.randomUUID().toString().take(8),
                                "Agent-created interval", promptText, minutes,
                            )
                        }
                    }
                }
                "Scheduled automation created: $scheduleSpec → '$promptText'"
            }
            else -> null
        }
    }

    private suspend fun buildSystemPrompt(
        observation: String,
        recent: ArrayDeque<Pair<String, String>>,
        usedVision: Boolean = false,
        sessionId: String,
        step: Int,
    ): String = buildString {
        appendLine("You are X-OmniClaw, an edge-native multimodal Android agent with self-learning.")
        appendLine("You can answer questions conversationally AND automate the device when asked.")
        appendLine()
        appendLine("Respond using this strict format:")
        appendLine("THOUGHT: <one sentence reasoning>")
        appendLine("ACTION: <one of the actions below>")
        appendLine()
        appendLine("ACTION choices:")
        appendLine("- done                  — use this when you can answer the user directly in your THOUGHT,")
        appendLine("                          or when the task is complete. Put the full answer in THOUGHT.")
        appendLine("- tap(x,y)              — tap a screen coordinate")
        appendLine("- swipe(x1,y1,x2,y2)    — swipe between two coordinates")
        appendLine("- type(\"text\")          — type text into the focused field")
        appendLine("- launch(package)       — launch an app by package name")
        appendLine("- back | home | screenshot")
        appendLine("- skill:<id>(<arg>)     — invoke a bundled skill (see list)")
        appendLine()
        appendLine("Available skill actions:")
        appendLine("- skill:gallery-qa(20)             — scan latest 20 photos into memory")
        appendLine("- skill:gallery-search(parrot)     — search gallery by keyword")
        appendLine("- skill:capcut-theme-video(parrot) — stage theme photos for CapCut")
        appendLine("- skill:clipboard-to-shortcut(name)— save clipboard URL as a bookmark")
        appendLine("- skill:open-bookmark(name)        — launch a saved bookmark by name")
        appendLine("- skill:behavior-replay(id)        — replay a recorded behavior skill")
        appendLine("- skill:app-search(com.reddit.frontpage:query) — launch app + search")
        appendLine("- skill:app-search(com.amazon.mShop.android:query) — launch Amazon + search")
        appendLine("- skill:model-config(baseUrl|apiKey|model) — update model config")
        appendLine("- skill:model-provider(gemini|litert|openai-compat) — switch LLM backend")
        appendLine("- skill:channel-config(feishuId|secret|webhook|discordHook) — update channels")
        appendLine("- skill:skill-creator(description) — LLM-draft a new SKILL.md")
        appendLine("- skill:scheduled-automation(60|prompt) — schedule interval task")
        appendLine("- skill:scheduled-automation(weekly:Wed:10:00|prompt) — schedule weekly task")
        appendLine()
        appendLine("Rules:")
        appendLine("- For questions, explanations, summaries, or advice: put the full answer in")
        appendLine("  THOUGHT and use ACTION: done. Do NOT use device actions for conversational replies.")
        appendLine("- Only use device actions (tap/swipe/type/launch) when the user explicitly asks")
        appendLine("  to automate something on the device.")
        appendLine("- Stop with done when the user's request is satisfied.")
        appendLine("- Never repeat an action that already failed twice.")
        appendLine("- Be concise. No markdown.")
        appendLine()
        // ---- Self-learning: inject lessons from past sessions ----
        // The LearningEngine queries the lesson store for lessons matching
        // the current screen fingerprint. If any are found, they're appended
        // here so the LLM can avoid known-bad actions and repeat known-good ones.
        val fingerprint = episodeRecorder.fingerprint(observation)
        val lessons = runCatching { learning.lessonsForPrompt(fingerprint) }.getOrNull()
        if (lessons != null) {
            appendLine(lessons)
            // Emit a LessonsApplied event so the chat UI can show a transparency
            // hint ("applied N lessons from past sessions"). Count is derived
            // from the number of [AVOID]/[USE]/[LOOP] tags in the injected text.
            val lessonCount = Regex("\\[(AVOID|USE|LOOP|NOTE)\\]").findAll(lessons).count()
            if (lessonCount > 0) {
                _events.tryEmit(Event.LessonsApplied(sessionId, step, lessonCount))
            }
        }
        if (usedVision) {
            appendLine("Current screen observation (from VLM vision fallback — coordinates are approximate):")
        } else {
            appendLine("Current screen observation (from accessibility tree; blank if service is off):")
        }
        appendLine(observation.ifBlank { "(no screen observation — answer conversationally without device actions)" })
        if (recent.isNotEmpty()) {
            appendLine()
            appendLine("Recent actions:")
            recent.forEach { appendLine("- ${it.first} (screen: ${it.second})") }
        }
    }

    private suspend fun isStopRequested(sessionId: String): Boolean {
        // Stop if the user requested it OR the session was deleted / externally stopped.
        if (stopMutex.withLock { sessionId in stopSet }) return true
        val s = sessions.getById(sessionId) ?: return true
        if (s.status == SessionStatus.STOPPED) return true
        return false
    }

    companion object {
        private const val TAG = "AgentLoop"

        /** Mask a secret for display in tool-call results — first 2 + last 2 chars only. */
        private fun maskKey(s: String?): String {
            if (s.isNullOrBlank()) return "(none)"
            if (s.length <= 4) return "****"
            return "${s.take(2)}***${s.takeLast(2)}"
        }
    }
}
