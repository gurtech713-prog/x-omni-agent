package com.omniclaw.app.agent

import android.content.Context
import android.util.Log
import com.omniclaw.app.agent.tools.DeviceAction
import com.omniclaw.app.agent.tools.DeviceToolSchema
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
import com.omniclaw.app.data.memory.MemoryRepository
import com.omniclaw.app.data.model.MemoryEntry
import com.omniclaw.app.data.model.MemoryEntry.MemoryKind
import com.omniclaw.app.data.prefs.SettingsRepository
import com.omniclaw.app.data.session.SessionRepository
import com.omniclaw.app.data.skill.SkillRepository
import com.omniclaw.app.deeplink.DeepLinkManager
import com.omniclaw.app.gallery.GalleryScanner
import com.omniclaw.app.logging.AgentLogger
import com.omniclaw.app.service.AgentForegroundService
import com.omniclaw.app.service.HaloOverlayService
import com.omniclaw.app.service.ScreenCaptureService
import com.omniclaw.app.vision.VlmClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [runCatching] that re-throws [CancellationException] instead of swallowing it.
 *
 * Standard `runCatching { ... }` catches every [Throwable] including
 * [CancellationException], which breaks structured concurrency: a cancelled
 * coroutine (user-requested stop, session supersession, parent scope timeout)
 * gets converted into a `Result.failure` and the cancellation never propagates
 * to the parent scope. This helper restores the contract by re-throwing
 * CancellationException and only catching other throwables.
 *
 * Duplicated (top-level, file-private) in each layer that needs it because the
 * shared `core/` package is owned by a different fix subagent; duplicating the
 * one-liner keeps file-boundary scopes clean.
 */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/**
 * Observation -> Reasoning -> Execution loop.
 *
 * Implements the X-OmniClaw execution methodology: at each step, the agent
 *  1) perceives the current screen + previous action outcome (observation),
 *  2) calls the LLM to interpret the screen and pick the next action (reasoning),
 *  3) dispatches the concrete Android action via DeviceScheduler (execution),
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

/**
 * Marker [CancellationException] used by [AgentLoop.start] to cancel a running
 * [AgentLoop.runLoop] when a new run is taking over the same session.
 *
 * The [runLoop] catch block checks `if (e is SupersessionCancellation)` to
 * distinguish supersession from user-initiated stop:
 *   - **Supersession**: the new runLoop owns the session — don't touch status,
 *     don't emit `Event.Stopped`, don't run reflection. Exit silently.
 *   - **User stop** (plain `CancellationException`): emit `Stopped`, set
 *     `SessionStatus.STOPPED`, finish the episode, run reflection.
 *
 * Without this distinction, a second `send()` while the first turn is still
 * streaming would clobber the session status to `STOPPED`, and the new
 * runLoop's `isStopRequested()` check would return `true` on its first
 * iteration — silently dropping the user's new prompt.
 */
private class SupersessionCancellation(sessionId: String) :
    CancellationException("Superseded by a new run for session $sessionId")

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
    private val memoryRepo: MemoryRepository,
    private val skillRepo: SkillRepository,
    private val planner: Planner,
    private val toolRegistry: com.omniclaw.app.agent.tools.AgenticToolRegistry,
) {

    /** Number of currently-running sessions; used to start/stop the foreground service. */
    private val activeCount = AtomicInteger(0)

    sealed class Event {
        abstract val sessionId: String

        data class StepStarted(override val sessionId: String, val step: Int) : Event()
        /**
         * A thought emitted by the agent. During streaming, multiple Thought
         * events are emitted per step — each carries the accumulated text so
         * far. The [isFinal] flag is true on the LAST Thought event for a
         * step (after the LLM call completes), and false on intermediate
         * streaming deltas.
         *
         * Callers that want to trigger side-effects (TTS, notification, etc.)
         * should check [isFinal] to avoid firing on every token — otherwise
         * they'll be invoked dozens of times per second during streaming,
         * causing audio garbling and wasted CPU.
         */
        data class Thought(
            override val sessionId: String,
            val step: Int,
            val text: String,
            val isFinal: Boolean = false,
        ) : Event()
        data class ToolCall(override val sessionId: String, val step: Int, val call: com.omniclaw.app.data.model.ToolCall) : Event()
        data class StepFinished(override val sessionId: String, val step: Int, val usage: LlmUsage) : Event()
        /** Emitted when learned lessons are injected into the system prompt.
         *  Lets the chat UI show a "applied N lessons" hint for transparency. */
        data class LessonsApplied(override val sessionId: String, val step: Int, val lessonCount: Int) : Event()
        data class LoopDetected(override val sessionId: String) : Event()
        data class Failed(override val sessionId: String, val error: String) : Event()
        data class Completed(override val sessionId: String, val finalText: String) : Event()
        data class Stopped(override val sessionId: String) : Event()
        /**
         * Emitted when a background skill action (gallery-sync, replay, skill-creator,
         * scheduled-automation) completes. Allows the UI to surface final results
         * rather than leaving users with only a placeholder "started..." message.
         */
        data class SkillComplete(override val sessionId: String, val skillId: String, val result: String) : Event()
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

    /**
     * Per-session mutex + Job registry.
     *
     * - `runningJobs` ensures only ONE [runLoop] coroutine runs per session ID
     *   at a time. If [start] is called again while a session is already
     *   running, the existing job is cancelled (and joined) before the new one
     *   starts — preventing double API calls, interleaved messages, and race
     *   conditions on the accessibility tree.
     * - The map entry is removed in [runLoop]'s `finally` block so the
     *   registry doesn't grow unbounded across many sessions.
     */
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val startMutex = Mutex()

    /** Per-step LLM call timeout (prevents a single step from running 10+ min). */
    private val stepTimeoutMs = 45_000L  // reduced 60->45s
    /** Per-session overall timeout (prevents maxSteps × stepTimeout runaway). */
    private val sessionTimeoutMs = 6 * 60 * 1000L  // reduced 10min->6min

    fun start(session: Session, prompt: String) {
        // AGENTIC TOOL REGISTRY: wire up the skill_creator tool's LLM callback
        // so it can draft SKILL.md files. This is set once per start() call —
        // the callback captures the current model config.
        toolRegistry.skillDraftCallback = { description ->
            try {
                val cfg2 = settings.modelConfig.first()
                val draftResult = llm.complete(
                    provider = cfg2.provider,
                    baseUrl = cfg2.baseUrl,
                    apiKey = cfg2.apiKey,
                    model = cfg2.model,
                    messages = listOf(
                        LlmClient.Message("system", "Draft a SKILL.md for an Android agent skill. Format: # Name\\n\\nDescription.\\n- Example utterance.\\n\\n## Tools\\n- list of tools used"),
                        LlmClient.Message("user", "Skill description: $description"),
                    ),
                )
                val content = draftResult.text
                val id = "custom-" + description.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(24)
                val dir = java.io.File(ctx.filesDir, "skills/$id").apply { mkdirs() }
                java.io.File(dir, "SKILL.md").writeText(content)
                logger.logInfo(session.id, 0, "skill-creator: wrote $id")
                com.omniclaw.app.agent.tools.AgenticToolRegistry.ToolResult(
                    success = true,
                    content = "Created skill '$id' from description: $description",
                    data = kotlinx.serialization.json.buildJsonObject {
                        put("skillId", id)
                        put("path", dir.absolutePath)
                    }
                )
            } catch (e: Exception) {
                com.omniclaw.app.agent.tools.AgenticToolRegistry.ToolResult(
                    success = false,
                    content = "Skill creation failed: ${e.message}"
                )
            }
        }
        scope.launch {
            // Save the user's prompt FIRST and wait for persistence so it's guaranteed to be in history before reasoning starts.
            sessions.appendMessage(
                session.id,
                ChatMessage(UUID.randomUUID().toString(), ChatMessage.Role.USER, prompt, System.currentTimeMillis())
            )

            // CHAT-9 FIX: set the session title from the first user prompt so the
            // Sessions list shows meaningful titles. Only update if the title is
            // still the placeholder "New session" — don't overwrite a title the
            // user may have set via another path (e.g. scheduled tasks set
            // "[Scheduled] <title>"). Truncation is handled by SessionRepository.setTitle.
            if (session.title == "New session") {
                sessions.setTitle(session.id, prompt)
            }
            // Start recording the episode for self-learning reflection.
            episodeRecorder.start(session.id, prompt)

            sessions.setStatus(session.id, SessionStatus.RUNNING)
            // CRITICAL FIX (agent not performing tasks): check if the
            // accessibility service is connected BEFORE starting the loop.
            // If it's not connected, tap/swipe/type won't work (they need
            // gesture dispatch via the a11y service). Launch will still work
            // (it uses PackageManager directly), but the user needs to know
            // that device actions require the a11y service. We surface a
            // one-time SYSTEM message at session start so the user sees it
            // immediately instead of wondering why "tap does nothing".
            if (scheduler.boundService == null) {
                val a11yWarn = "⚠ Accessibility service not connected. Launch works, but tap/swipe/type need the service. Enable it: Settings → Accessibility → X-OmniClaw → On."
                sessions.appendMessage(
                    session.id,
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.SYSTEM,
                        content = a11yWarn,
                        timestamp = System.currentTimeMillis(),
                    )
                )
                _events.tryEmit(Event.Thought(session.id, 0, a11yWarn))
            }
            // Start the foreground service + Halo overlay when the first session
            // becomes active, so the loop survives backgrounding and the user sees
            // live status via the Dynamic Island-style pill.
            if (activeCount.getAndIncrement() == 0) {
                val fgStarted = runCatching { AgentForegroundService.start(ctx) }.getOrDefault(false)
                if (!fgStarted) {
                    val warn = "Warning: foreground service failed to start; agent may not survive backgrounding."
                    sessions.appendMessage(
                        session.id,
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = ChatMessage.Role.SYSTEM,
                            content = warn,
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                    _events.tryEmit(Event.Thought(session.id, 0, warn))
                }
                runCatching { HaloOverlayService.start(ctx) }
            }
            // Launch the new run, ensuring any existing run for this session is
            // cancelled first (per-session mutex prevents interleaved messages and
            // double API calls). The cancel+join+launch+register sequence is a
            // SINGLE critical section — splitting it across two withLock blocks
            // allowed two rapid start() calls to both launch a runLoop before
            // either registered, spawning two concurrent loops for one session.
            //
            // CHAT-FLOW FIX (supersession vs user-stop):
            // Use a typed SupersessionCancellation marker so the catch block in
            // runLoop can distinguish "I'm being cancelled because a new run is
            // taking over this session" from "the user clicked Stop". Previously
            // both used a plain CancellationException, so the catch block
            // unconditionally set status=STOPPED — which then made the new
            // runLoop's isStopRequested() check return true on its very first
            // iteration, silently dropping the user's new prompt.
            startMutex.withLock {
                runningJobs[session.id]?.let { existing ->
                    existing.cancel(SupersessionCancellation(session.id))
                    runCatchingCancellable { existing.join() }
                }
                // Defensive: re-assert RUNNING after the join, in case the
                // superseded run's catch block raced and set STOPPED before we
                // got here. The new run owns the session now.
                sessions.setStatus(session.id, SessionStatus.RUNNING)
                val job = scope.launch { runLoop(session.id, prompt) }
                runningJobs[session.id] = job
            }
        }
    }

    suspend fun stop(sessionId: String) {
        stopMutex.withLock { stopSet.add(sessionId) }
        // Cancel the running job so blocking LLM calls (which now use
        // suspendCancellableCoroutine) actually abort within ~100ms instead of
        // waiting for readTimeout (120s).
        val job = runningJobs[sessionId]
        job?.cancel(CancellationException("User requested stop for session $sessionId"))
        if (job != null) runCatchingCancellable { job.join() }
        sessions.stop(sessionId)
        verifier.reset(sessionId)
        _events.tryEmit(Event.Stopped(sessionId))
    }

    private suspend fun runLoop(sessionId: String, prompt: String) {
        try {
            // Bounded overall session timeout — prevents runaway loops from
            // burning unlimited tokens / battery.
            val completedNormally = withTimeoutOrNull(sessionTimeoutMs) {
                runLoopInner(sessionId, prompt)
                true
            } ?: false

            if (!completedNormally) {
                val warn = "Session timed out after ${sessionTimeoutMs / 60000} minutes."
                scope.launch {
                    sessions.appendMessage(
                        sessionId,
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = ChatMessage.Role.SYSTEM,
                            content = warn,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                _events.tryEmit(Event.Failed(sessionId, warn))
                sessions.setStatus(sessionId, SessionStatus.FAILED)
                // A-C1 FIX: Mirror the cancellation + exception branches so the
                // episode is marked FAILED and the post-session learning pipeline
                // (reflection + auto-skill) still runs. Previously the session
                // timeout branch only emitted Failed + set status, leaving the
                // episode in RUNNING state and skipping reflection entirely —
                // so a timed-out session was never learned from.
                episodeRecorder.finish(sessionId, "FAILED")
                scope.launch { runCatchingCancellable { learning.runPostSessionPipeline(sessionId) } }
            }
        } catch (e: CancellationException) {
            // CHAT-FLOW FIX: distinguish supersession from user-stop.
            //
            // SupersessionCancellation means a NEW runLoop is taking over this
            // session — we must NOT touch the session status (the new runLoop
            // already re-asserted RUNNING in start()) and must NOT emit
            // Event.Stopped (the new runLoop will emit its own events). The
            // superseded run simply exits silently so the new run can proceed
            // without its prompt being dropped by a stale STOPPED flag.
            //
            // Any other CancellationException is a user-initiated stop — emit
            // Stopped, set status, finish the episode, run reflection.
            if (e is SupersessionCancellation) {
                Log.i(TAG, "Session $sessionId superseded by a new run — exiting silently")
            } else {
                _events.tryEmit(Event.Stopped(sessionId))
                sessions.setStatus(sessionId, SessionStatus.STOPPED)
                episodeRecorder.finish(sessionId, "STOPPED")
                scope.launch { runCatchingCancellable { learning.runPostSessionPipeline(sessionId) } }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Agent loop crashed: ${e.message}", e)
            scope.launch {
                sessions.appendMessage(
                    sessionId,
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.SYSTEM,
                        content = "Agent loop crashed: ${e.message}",
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            _events.tryEmit(Event.Failed(sessionId, e.message ?: "Crash"))
            sessions.setStatus(sessionId, SessionStatus.FAILED)
            episodeRecorder.finish(sessionId, "FAILED")
            scope.launch { runCatchingCancellable { learning.runPostSessionPipeline(sessionId) } }
        } finally {
            // Decrement active session count; stop the foreground service + Halo
            // when no sessions are running anymore.
            if (activeCount.decrementAndGet() <= 0) {
                runCatching { AgentForegroundService.stop(ctx) }
            }
            // Clean up per-session verifier state + stopSet entry + running-job
            // registry (prevents unbounded growth across many sessions).
            verifier.reset(sessionId)
            stopMutex.withLock { stopSet.remove(sessionId) }
            runningJobs.remove(sessionId)
            clearHistoryCache(sessionId)
        }
    }

    private suspend fun runLoopInner(sessionId: String, prompt: String) {
        // CLEAN REACT LOOP (reimplemented from scratch).
        //
        // This is a standard Reason+Act loop with structured tool-calling:
        //   1. OBSERVE: capture the current screen (accessibility tree, or VLM fallback).
        //   2. THINK: call the LLM with the full tool list (device actions + gallery + skills).
        //      The LLM either responds with text (conversational -> done) or a tool_call (-> dispatch).
        //   3. ACT: dispatch the tool via AgenticToolRegistry. The registry handles ALL capabilities.
        //   4. FEED BACK: append the tool result as a `tool` message so the LLM can reason over it.
        //   5. REPEAT until the LLM stops calling tools or maxSteps is reached.
        //
        // This replaces the old dual-path (structured-tools + free-text THOUGHT/ACTION) architecture
        // with a single clean path. Skills are now first-class tools the LLM calls directly.

        // Bookmark shortcut pre-match.
        if (deepLinks.launchByPhrase(prompt)) {
            scope.launch {
                sessions.appendMessage(sessionId, ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatMessage.Role.SYSTEM,
                    content = "Launched bookmark shortcut matching: $prompt",
                    timestamp = System.currentTimeMillis()
                ))
            }
            sessions.setStatus(sessionId, SessionStatus.DONE)
            _events.tryEmit(Event.Completed(sessionId, "Launched bookmark shortcut matching: $prompt"))
            clearHistoryCache(sessionId)
            learning.clearLessonCache(sessionId)
            episodeRecorder.finish(sessionId, "DONE")
            scope.launch { runCatchingCancellable { learning.runPostSessionPipeline(sessionId) } }
            return
        }

        // Fetch config + tuning in parallel.
        val (cfg, tuning) = coroutineScope {
            val cfgAsync = async { settings.modelConfig.first() }
            val tuningAsync = async { settings.agentTuning.first() }
            Pair(cfgAsync.await(), tuningAsync.await())
        }

        // Validate config.
        if (cfg.provider == com.omniclaw.app.data.prefs.LlmProvider.OPENAI_COMPAT) {
            if (cfg.baseUrl.isBlank()) { failSession(sessionId, "No Base URL configured. Go to Settings -> AI Provider."); return }
            if (cfg.apiKey.isBlank()) { failSession(sessionId, "No API key configured. Go to Settings -> AI Provider."); return }
        }
        if (cfg.model.isBlank()) { failSession(sessionId, "No model configured. Go to Settings -> AI Provider."); return }

        val maxSteps = tuning.maxSteps
        val stepTimeoutMs = tuning.stepTimeoutMs
        val recentActions = ArrayDeque<Pair<String, String>>()

        // Build the tool list once (filtered by enabled skills inside the registry).
        val toolSpecs = toolRegistry.toolSpecs()
        if (toolSpecs.isEmpty()) { failSession(sessionId, "No tools available."); return }

        // Warn if a11y service is off.
        if (scheduler.boundService == null) {
            val warn = "Warning: Accessibility service not connected. Launch works, but tap/swipe/scroll/type need the service. Enable it: Settings -> Accessibility -> X-OmniClaw -> On."
            sessions.appendMessage(sessionId, ChatMessage(
                id = UUID.randomUUID().toString(), role = ChatMessage.Role.SYSTEM,
                content = warn, timestamp = System.currentTimeMillis(),
            ))
            _events.tryEmit(Event.Thought(sessionId, 0, warn))
        }

        // ---- MAIN REACT LOOP ----
        for (step in 1..maxSteps) {
            if (isStopRequested(sessionId)) return
            _events.tryEmit(Event.StepStarted(sessionId, step))

            // 1. OBSERVE
            val isLikelyChatOnly = step == 1 && promptLooksConversational(prompt)
            var observation = if (isLikelyChatOnly) "" else scheduler.snapshot()
            var usedVision = false
            if (!isLikelyChatOnly && cfg.vlmApiKey.isNotBlank() &&
                (observation.isBlank() || observation.length < 80 ||
                    observation.contains("accessibility service not connected", ignoreCase = true))
            ) {
                var png: ByteArray? = ScreenCaptureService.latestFrameBytes()
                if (png == null || png.isEmpty()) png = scheduler.screenshot()
                if (png != null && png.isNotEmpty()) {
                    val vlmAnswer = runCatchingCancellable {
                        vlm.describe(png, "Describe the current screen. List interactive elements with their approximate tap coordinates (x, y). Be concise.")
                    }.getOrNull()
                    if (!vlmAnswer.isNullOrBlank()) {
                        observation = "[VISION FALLBACK]\n$vlmAnswer"
                        usedVision = true
                    }
                }
            }

            // 2. THINK (LLM call with tools, streaming)
            val systemMsg = LlmClient.Message(
                role = "system",
                content = buildAgenticSystemPrompt(observation, recentActions, usedVision, sessionId, step, prompt, isLikelyChatOnly)
            )
            val history = buildHistory(sessionId)
            _events.tryEmit(Event.Thought(sessionId, step, "Thinking...", isFinal = false))

            val thoughtBuilder = StringBuilder()
            val toolCallArgs = mutableMapOf<Int, StringBuilder>()
            val toolCallMeta = mutableMapOf<Int, Pair<String?, String?>>()
            var streamHadToolCall = false
            var streamHadText = false
            var lastEmitMs = 0L

            // TOOLS-FALLBACK: some OpenAI-compat providers (Ollama, llama.cpp,
            // older self-hosted endpoints) reject the `tools` parameter with
            // HTTP 400/422. When this happens, we retry WITHOUT tools so the
            // user at least gets a text response instead of an instant failure.
            // The session is marked tools-disabled so subsequent steps skip the
            // tools path entirely (no point retrying every step).
            val toolsDisabled = toolsDisabledSessions[sessionId] == true
            val effectiveTools = if (toolsDisabled) null else toolSpecs
            val effectiveToolChoice = if (toolsDisabled) null else "auto"

            try {
                withTimeout(stepTimeoutMs) {
                    llm.streamWithTools(
                        provider = cfg.provider, baseUrl = cfg.baseUrl, apiKey = cfg.apiKey,
                        model = cfg.model, messages = listOf(systemMsg) + history,
                        temperature = cfg.temperature, maxTokens = cfg.maxTokens,
                        tools = effectiveTools, toolChoice = effectiveToolChoice,
                    ).collect { chunk ->
                        when (chunk) {
                            is LlmClient.StreamChunk.TextDelta -> {
                                thoughtBuilder.append(chunk.text)
                                streamHadText = true
                                val now = System.currentTimeMillis()
                                if (now - lastEmitMs >= 50L || thoughtBuilder.length < 15) {
                                    lastEmitMs = now
                                    _events.tryEmit(Event.Thought(sessionId, step, thoughtBuilder.toString()))
                                }
                            }
                            is LlmClient.StreamChunk.ToolCallDelta -> {
                                streamHadToolCall = true
                                toolCallArgs.getOrPut(chunk.index) { StringBuilder() }.append(chunk.argumentsChunk)
                                val existing = toolCallMeta[chunk.index]
                                toolCallMeta[chunk.index] = Pair(chunk.id ?: existing?.first, chunk.name ?: existing?.second)
                            }
                            is LlmClient.StreamChunk.Done -> {}
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException && currentCoroutineContext().isActive) {
                    failSession(sessionId, "Request timed out after ${stepTimeoutMs / 1000}s.")
                    return
                }
                throw e
            } catch (e: com.omniclaw.app.data.llm.LlmException) {
                // TOOLS-FALLBACK: if the provider rejected the tools parameter
                // (HTTP 400/422), disable tools for this session and retry this
                // step WITHOUT tools. The LLM will respond in plain text — it
                // can't call tools, but at least the user gets a response
                // instead of "Agent failed: HTTP 400".
                val msg = e.message.orEmpty()
                if (!toolsDisabled && (msg.contains("HTTP 400") || msg.contains("HTTP 422"))) {
                    Log.w(TAG, "Session $sessionId: provider rejected tools ($msg) — disabling tools, retrying without")
                    toolsDisabledSessions[sessionId] = true
                    // Retry this step without tools by continuing to the next
                    // iteration of the loop. But we need to NOT count this as
                    // a step — so decrement step counter by continuing without
                    // incrementing. Simplest: just `continue` the for loop.
                    // However, we already emitted StepStarted — emit StepFinished
                    // to keep the UI balanced.
                    _events.tryEmit(Event.StepFinished(sessionId, step, LlmUsage(0, 0, 0)))
                    // Re-run this step by jumping back. Since we can't easily
                    // re-run the same step in a for loop, we'll just let the
                    // loop advance — the user will see a "retrying without tools"
                    // note and the next step will use the tools-disabled path.
                    sessions.appendMessage(sessionId, ChatMessage(
                        id = UUID.randomUUID().toString(), role = ChatMessage.Role.SYSTEM,
                        content = "AI provider doesn't support tool-calling. Retrying in conversational mode (device automation may be limited).",
                        timestamp = System.currentTimeMillis(),
                    ))
                    continue
                }
                failSession(sessionId, "Agent failed: ${e.message ?: "LLM error"}")
                return
            } catch (e: Exception) {
                failSession(sessionId, "Agent failed: ${e.message ?: "LLM error"}")
                return
            }

            val thought = thoughtBuilder.toString()
            _events.tryEmit(Event.Thought(sessionId, step, thought.ifBlank { "(empty response)" }, isFinal = true))

            // Empty response guard.
            if (thought.isBlank() && !streamHadToolCall) {
                val note = "(The model returned an empty response. This can happen with safety filters or content policies. Try rephrasing.)"
                sessions.appendMessage(sessionId, ChatMessage(
                    id = UUID.randomUUID().toString(), role = ChatMessage.Role.SYSTEM,
                    content = note, timestamp = System.currentTimeMillis(),
                ))
                _events.tryEmit(Event.Completed(sessionId, "Empty response from model."))
                sessions.setStatus(sessionId, SessionStatus.DONE)
                clearHistoryCache(sessionId); learning.clearLessonCache(sessionId)
                episodeRecorder.finish(sessionId, "DONE")
                scope.launch { runCatchingCancellable { learning.runPostSessionPipeline(sessionId) } }
                return
            }

            // 3. ACT or DONE -- if no tool_call, it is a conversational reply -> done.
            if (!streamHadToolCall) {
                sessions.appendMessage(sessionId, ChatMessage(
                    id = UUID.randomUUID().toString(), role = ChatMessage.Role.ASSISTANT,
                    content = thought, timestamp = System.currentTimeMillis(),
                ))
                sessions.incSteps(sessionId)
                _events.tryEmit(Event.StepFinished(sessionId, step, LlmUsage(0, 0, 0)))
                _events.tryEmit(Event.Completed(sessionId, thought))
                sessions.setStatus(sessionId, SessionStatus.DONE)
                clearHistoryCache(sessionId); learning.clearLessonCache(sessionId)
                episodeRecorder.finish(sessionId, "DONE")
                scope.launch { runCatchingCancellable { learning.runPostSessionPipeline(sessionId) } }
                return
            }

            // LLM returned tool_call(s). Assemble them.
            val toolCalls = toolCallMeta.entries.sortedBy { it.key }.map { (index, meta) ->
                val tcId = meta.first ?: UUID.randomUUID().toString()
                val tcName = meta.second ?: "unknown"
                val tcArgs = toolCallArgs[index]?.toString() ?: "{}"
                com.omniclaw.app.data.model.LlmToolCall(id = tcId, name = tcName, arguments = tcArgs)
            }

            // Append assistant message WITH tool_calls (required by OpenAI spec).
            sessions.appendMessage(sessionId, ChatMessage(
                id = UUID.randomUUID().toString(), role = ChatMessage.Role.ASSISTANT,
                content = thought, timestamp = System.currentTimeMillis(),
                toolCalls = toolCalls.map { ToolCall(id = it.id, name = it.name, args = it.arguments, result = "", ok = true, durationMs = 0L) },
            ))
            sessions.incSteps(sessionId)

            // Dispatch each tool_call.
            for (tc in toolCalls) {
                val dispatchStart = System.currentTimeMillis()
                val result = runCatchingCancellable {
                    toolRegistry.dispatch(tc.name, tc.arguments)
                }.getOrElse { e ->
                    com.omniclaw.app.agent.tools.AgenticToolRegistry.ToolResult(
                        success = false, content = "Tool '${tc.name}' threw: ${e.message ?: e::class.simpleName}"
                    )
                }
                val durationMs = System.currentTimeMillis() - dispatchStart
                val call = ToolCall(id = tc.id, name = tc.name, args = tc.arguments, result = result.content, ok = result.success, durationMs = durationMs)
                _events.tryEmit(Event.ToolCall(sessionId, step, call))
                scope.launch { behaviorRecorder.recordAction(call) }

                // Append tool result as a `tool` message with tool_call_id.
                sessions.appendMessage(sessionId, ChatMessage(
                    id = UUID.randomUUID().toString(), role = ChatMessage.Role.TOOL,
                    content = result.toMessageContent(), timestamp = System.currentTimeMillis(),
                    toolCallId = tc.id,
                ))

                // If this was device_action with action=done, end the session.
                if (tc.name == DeviceToolSchema.TOOL_NAME) {
                    val parsed = DeviceToolSchema.parse(tc.arguments)
                    if (parsed.done) {
                        // CRITICAL FIX: use the LLM's `thought` field from the
                        // tool call arguments as the completion text — NOT the
                        // streaming text (thoughtBuilder), which is often empty
                        // when the LLM returns only a tool_call with no visible
                        // text. The user-facing answer lives in parsed.thought.
                        val completionText = parsed.thought.ifBlank {
                            thought.ifBlank { "Task completed." }
                        }
                        _events.tryEmit(Event.Completed(sessionId, completionText))
                        sessions.setStatus(sessionId, SessionStatus.DONE)
                        clearHistoryCache(sessionId); learning.clearLessonCache(sessionId)
                        episodeRecorder.finish(sessionId, "DONE")
                        scope.launch { runCatchingCancellable { learning.runPostSessionPipeline(sessionId) } }
                        return
                    }
                }

                // 4. STUCK DETECTION -- same tool_call on same screen = stuck.
                val currentFingerprint = episodeRecorder.fingerprint(observation)
                val sig = "${tc.name}(${tc.arguments.take(100)})"
                val stuckCount = recentActions.count { it.first == sig && it.second == currentFingerprint }
                if (stuckCount >= 2) {
                    _events.tryEmit(Event.LoopDetected(sessionId))
                    sessions.appendMessage(sessionId, ChatMessage(
                        id = UUID.randomUUID().toString(), role = ChatMessage.Role.SYSTEM,
                        content = "Loop detected: '$sig' repeated on the same screen. Try a different action.",
                        timestamp = System.currentTimeMillis(),
                    ))
                    if (stuckCount >= 3) {
                        sessions.setStatus(sessionId, SessionStatus.FAILED)
                        episodeRecorder.recordLoop(sessionId, step, observation, sig)
                        learning.clearLessonCache(sessionId)
                        episodeRecorder.finish(sessionId, "FAILED")
                        scope.launch { runCatchingCancellable { learning.runPostSessionPipeline(sessionId) } }
                        return
                    }
                }
                recentActions.addLast(sig to currentFingerprint)
                if (recentActions.size > 8) recentActions.removeFirst()

                // Record for self-learning.
                episodeRecorder.recordStep(sessionId, step, observation, sig, call, result.success)
                val outcome = if (result.success) com.omniclaw.app.data.model.Lesson.LessonOutcome.SUCCESS
                else com.omniclaw.app.data.model.Lesson.LessonOutcome.FAILURE
                val fFp = currentFingerprint; val fAs = sig
                scope.launch { runCatchingCancellable { learning.recordDirectLesson(sessionId, fFp, fAs, outcome, observation) } }

                // If the tool failed, add a system note so the LLM can recover.
                if (!result.success) {
                    sessions.appendMessage(sessionId, ChatMessage(
                        id = UUID.randomUUID().toString(), role = ChatMessage.Role.SYSTEM,
                        content = "Tool '${tc.name}' failed: ${result.content}. Try a different approach.",
                        timestamp = System.currentTimeMillis(),
                    ))
                } else {
                    // VERIFICATION: check if the screen changed after the action.
                    // This gives the LLM feedback on whether its action had an
                    // effect — if the screen didn't change, the tap may have
                    // missed or the button may not have responded.
                    val beforeFp = episodeRecorder.fingerprint(observation)
                    val verifyResult = runCatchingCancellable {
                        scheduler.verifyScreenChanged(beforeFp)
                    }.getOrNull()
                    if (verifyResult != null && !verifyResult.changed) {
                        sessions.appendMessage(sessionId, ChatMessage(
                            id = UUID.randomUUID().toString(), role = ChatMessage.Role.SYSTEM,
                            content = "Note: the screen did NOT change after '${tc.name}'. The action may not have had an effect — check the next observation and try a different approach if needed.",
                            timestamp = System.currentTimeMillis(),
                        ))
                    }
                }
            }

            _events.tryEmit(Event.StepFinished(sessionId, step, LlmUsage(0, 0, 0)))
            // Loop continues -- next iteration observes the post-action screen.
        }

        // Max steps reached.
        _events.tryEmit(Event.Completed(sessionId, "Max steps ($maxSteps) reached."))
        sessions.setStatus(sessionId, SessionStatus.DONE)
        clearHistoryCache(sessionId); learning.clearLessonCache(sessionId)
        episodeRecorder.finish(sessionId, "DONE")
        scope.launch { runCatchingCancellable { learning.runPostSessionPipeline(sessionId) } }
    }

    /** Mark a session as FAILED with a diagnostic message. */
    private suspend fun failSession(sessionId: String, errorMsg: String) {
        scope.launch {
            sessions.appendMessage(sessionId, ChatMessage(
                id = UUID.randomUUID().toString(), role = ChatMessage.Role.SYSTEM,
                content = "Agent failed: $errorMsg", timestamp = System.currentTimeMillis(),
            ))
        }
        _events.tryEmit(Event.Failed(sessionId, errorMsg))
        sessions.setStatus(sessionId, SessionStatus.FAILED)
        learning.clearLessonCache(sessionId)
        episodeRecorder.finish(sessionId, "FAILED")
        scope.launch { runCatchingCancellable { learning.runPostSessionPipeline(sessionId) } }
    }

    /**
     * Build the system prompt for the agentic loop. Much simpler than the old
     * prompt because tools are declared via the `tools` parameter natively.
     */
    private suspend fun buildAgenticSystemPrompt(
        observation: String,
        recent: ArrayDeque<Pair<String, String>>,
        usedVision: Boolean,
        sessionId: String,
        step: Int,
        prompt: String,
        isChatOnly: Boolean,
    ): String = buildString {
        appendLine("You are X-OmniClaw, an edge-native multimodal Android agent with self-learning.")
        appendLine("You can answer questions conversationally AND automate the device when asked.")
        appendLine("You have access to tools -- use them for any device automation or data lookup.")
        appendLine("For conversational questions (explanations, advice, summaries), respond with text only (no tool call).")
        appendLine("For device automation tasks (open apps, tap buttons, scroll, search gallery), use the appropriate tool.")
        appendLine("When the task is complete, call the device_action tool with action='done' and put your final answer in the 'thought' field.")
        appendLine()
        appendLine("Key tools:")
        appendLine("- device_action: tap/click/swipe/scroll/drag/type/launch/back/home/screenshot/done")
        appendLine("- tap_element: PREFERRED for tapping buttons/links/labels — takes text (e.g. 'Search', 'OK') instead of coordinates. Has app-specific fallback for camera apps.")
        appendLine("- tap_element_visual: VLM-based tap for when the a11y tree is empty (camera apps, games). Takes a description (e.g. 'shutter button').")
        appendLine("- find_elements: find ALL elements matching text — returns bounds + TAP coords for each. Use when multiple matches exist.")
        appendLine("- wait_for_element: wait for an element to appear (use after launching apps, before tapping). Takes text + timeoutSeconds.")
        appendLine("- undo_last_action: press BACK to revert the last action (dismiss dialogs, go back).")
        appendLine("- select_text: select text in the focused field by start/end char index")
        appendLine("- copy_to_clipboard: copy the current selection to clipboard")
        appendLine("- read_clipboard: read the current clipboard content")
        appendLine("- gallery_recent: get recent photos with metadata (name, date, bucket, dimensions)")
        appendLine("- gallery_search: search gallery by filename or album name")
        appendLine("- gallery_sync_memory: scan photos into long-term memory")
        appendLine("- gallery_stage_theme: filter photos by theme for video creation")
        appendLine("- clipboard_read / clipboard_save_bookmark / bookmark_list / bookmark_launch")
        appendLine("- app_search: launch an app to search within it")
        appendLine("- scheduled_automation: schedule recurring tasks (interval or weekly)")
        appendLine("- skill_creator: create a new skill from a description")
        appendLine()
        appendLine("Rules:")
        appendLine("- Be concise. No markdown in conversational replies.")
        appendLine("- For multi-step tasks, call one tool per turn, observe the result, then decide the next step.")
        appendLine("- If a tool fails, read the error message and try a different approach.")
        appendLine("- Never repeat a failed action more than twice.")
        appendLine("- Model config, provider, and webhook config CANNOT be changed by you -- the user configures these in Settings.")
        appendLine()

        // Inject lessons from past sessions.
        val fingerprint = episodeRecorder.fingerprint(observation)
        val cached = lastLessonCache[sessionId]
        val lessons = if (cached != null && cached.first == fingerprint) cached.second
        else {
            val fresh = runCatchingCancellable { learning.lessonsForPrompt(fingerprint, sessionId = sessionId) }.getOrNull()
            lastLessonCache[sessionId] = fingerprint to fresh
            fresh
        }
        if (lessons != null && lessons.isNotBlank()) {
            appendLine(lessons)
            val lessonCount = Regex("\\[(AVOID|USE|LOOP|NOTE)\\b").findAll(lessons).count()
            if (lessonCount > 0) _events.tryEmit(Event.LessonsApplied(sessionId, step, lessonCount))
        }

        // Inject long-term memory.
        val memoryEntries = memoryRepo.entries.value
            .sortedWith(compareByDescending<MemoryEntry> { it.pinned }.thenByDescending { it.createdAt })
            .take(50)
        if (memoryEntries.isNotEmpty()) {
            appendLine("---- Long-term Memory & Facts (Self-learning) ----")
            memoryEntries.forEach { entry ->
                val pin = if (entry.pinned) "[PINNED]" else ""
                appendLine("- ${entry.kind}: ${entry.content} $pin")
            }
            val total = memoryRepo.entries.value.size
            if (total > 50) appendLine("... ($total total, showing top 50)")
            appendLine()
        }

        // Observation.
        if (isChatOnly) {
            appendLine("(No screen observation -- this is a conversational turn. Answer directly without device actions.)")
        } else {
            if (usedVision) appendLine("Current screen observation (from VLM vision fallback -- coordinates are approximate):")
            else appendLine("Current screen observation (from accessibility tree; blank if service is off):")
            appendLine(observation.ifBlank { "(no screen observation)" })
        }
        if (recent.isNotEmpty()) {
            appendLine()
            appendLine("Recent actions (avoid repeating failed ones):")
            recent.takeLast(6).forEach { appendLine("- ${it.first}") }
        }
    }

    private val historyCache = java.util.concurrent.ConcurrentHashMap<String, MutableList<LlmClient.Message>>()

    /**
     * PERF-FIX (slow agent response): per-session cache of the last
     * (fingerprint, lessons) pair returned by [LearningEngine.lessonsForPrompt].
     * Avoids re-querying Room when the fingerprint hasn't changed (common on
     * chat-only turns where the observation is the empty string both times
     * buildSystemPrompt is called, and on retries within the same step).
     */
    private val lastLessonCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String?>>()

    /**
     * FIX (openai-compat agent failed): per-session flag marking the active
     * provider as NOT supporting the OpenAI `tools` parameter. Set when the
     * structured-tools streaming call returns HTTP 400 or 422 (the standard
     * "I don't understand this field" codes) — once set, subsequent steps in
     * the same session skip the structured-tools path entirely and use the
     * plain streaming path (which sends no `tools` array).
     *
     * This is per-session (not global) because the user may switch providers
     * between sessions — a session started against Ollama (no tools support)
     * shouldn't disable tools for a later session against GLM (tools supported).
     *
     * Cleared in [clearHistoryCache] when the session ends.
     */
    private val toolsDisabledSessions = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private suspend fun buildHistory(sessionId: String): List<LlmClient.Message> {
        val cached = historyCache.getOrPut(sessionId) { mutableListOf() }
        // PERF-FIX (slow agent response): use the in-memory snapshot instead
        // of the suspend getById(). During a running session the StateFlow is
        // always current (the agent loop's own appendMessage calls update it
        // synchronously), so the suspend call was just an unnecessary context
        // switch + cached.messages.isNotEmpty() check on every step. getById()
        // is still the right call for cold reads (e.g. opening a past session
        // from disk) — but buildHistory is only ever called from inside a
        // running loop, where the snapshot is authoritative.
        val session = sessions.getByIdSnapshot(sessionId) ?: return cached.toList()
        // A-M1 FIX: if the session's message list shrank (e.g. the user
        // cleared history, or the session was reset), the cache holds a
        // STALE larger list — the `cached.size == session.messages.size`
        // check below would never be true and we'd return the stale cache
        // forever. Detect the shrink and invalidate the cache so the next
        // call rebuilds from the fresh (smaller) session.messages list.
        if (cached.size > session.messages.size) {
            historyCache.remove(sessionId)
            return buildHistory(sessionId)
        }
        if (cached.size == session.messages.size) return cached.toList()
        val startIdx = cached.size
        for (i in startIdx until session.messages.size) {
            val msg = session.messages[i]
            // CHAT-FLOW FIX: map the full message shape, including toolCalls
            // and toolCallId. Previously only role+content were mapped, which:
            //   - Reduced ASSISTANT messages with structured tool invocations
            //     to plain text (the LLM couldn't see which tool it called).
            //   - Sent TOOL messages with role="tool" but no tool_call_id,
            //     which most OpenAI-compat endpoints reject with HTTP 400:
            //     "tool messages must have tool_call_id".
            // Mapping both fields lets the structured-tools path maintain a
            // correct conversation history across multi-step device tasks.
            val toolCalls = msg.toolCalls.takeIf { it.isNotEmpty() }
                ?.map { com.omniclaw.app.data.model.LlmToolCall(id = it.id, name = it.name, arguments = it.args) }
            cached.add(
                LlmClient.Message(
                    role = msg.role.name.lowercase(),
                    content = msg.content,
                    toolCalls = toolCalls,
                    toolCallId = msg.toolCallId,
                )
            )
        }
        return cached.toList()
    }

    private fun clearHistoryCache(sessionId: String) {
        historyCache.remove(sessionId)
        // PERF-FIX (slow agent response): also clear the lesson cache so a
        // future run on the same session doesn't see stale lessons from a
        // different observation.
        lastLessonCache.remove(sessionId)
        // FIX (openai-compat agent failed): also clear the tools-disabled flag
        // so a future run on the same session (potentially with a different
        // provider after the user changed Settings) re-tries structured tools.
        toolsDisabledSessions.remove(sessionId)
    }

    private suspend fun isStopRequested(sessionId: String): Boolean {
        // Stop if the user requested it OR the session was deleted / externally stopped.
        if (stopMutex.withLock { sessionId in stopSet }) return true
        val s = sessions.getById(sessionId) ?: return true
        if (s.status == SessionStatus.STOPPED) return true
        return false
    }

    /**
     * # STRATEGY CHANGE: No screenshots during pure chat
     *
     * Cheap prompt-side heuristic that classifies whether the user's prompt is
     * a pure conversational turn (question, explanation, summary, drafting,
     * translation, etc.) — NOT a request to automate something on the device.
     *
     * When this returns `true` on step 1, [runLoopInner] SKIPS:
     *   - `scheduler.snapshot()` (accessibility tree read)
     *   - `ScreenCaptureService.latestFrameBytes()` (MediaProjection frame pull)
     *   - `scheduler.screenshot()` (one-shot a11y screenshot fallback)
     *   - `vlm.describe(...)` (cloud VLM call)
     *
     * This eliminates the privacy-invasive screenshot-then-VLM-describe cycle
     * that previously fired on EVERY chat message whenever the accessibility
     * service wasn't connected (the common case during chat — the agent app is
     * in the foreground, not the target app).
     *
     * The heuristic is intentionally high-precision (prefer false-negatives
     * over false-positives): if we wrongly classify a device-action prompt as
     * chat, we skip the vision probe for one step but the LLM still sees the
     * accessibility snapshot on step ≥ 2 (when the LLM emits a non-`done`
     * ACTION and we're now in automation mode). If we wrongly classify a chat
     * prompt as device-action, we waste one screenshot — annoying but not
     * broken.
     *
     * The classifier checks for:
     *   1. Question words (what/why/how/who/when/where/can you/could you/would you)
     *   2. Conversational verbs (explain/tell me/describe/define/compare/list/summarize/translate/write/draft)
     *   3. Generic chat openers (hi/hello/hey/thanks/ok/yes/no)
     * And excludes (returns false) when the prompt contains device-action verbs
     * followed by an app/package reference: tap/open/launch/swipe/type/scroll/click
     * on/for/in + app name or "screen".
     */
    private fun promptLooksConversational(prompt: String): Boolean {
        val p = prompt.trim().lowercase()
        if (p.isEmpty()) return true  // empty prompt → no device action possible
        if (p.length > 1000) return false  // very long prompts are usually automation instructions

        // Device-action verbs that indicate automation intent, NOT chat.
        // Match common forms: "tap X", "open Reddit", "launch com.foo", "scroll down",
        // "swipe up", "type hello", "click the button", "search for X on Y",
        // "take a screenshot", "play X on Y", "go to settings".
        //
        // CRITICAL FIX (agent not performing tasks): the previous `open|launch`
        // pattern required the word AFTER the verb to be either `com.` or a
        // word followed by `app|on|in|for`. So "open camera", "open X", "open
        // Chrome" did NOT match — `camera`/`x`/`chrome` alone don't satisfy
        // `[a-z]+\s*(app|on|in|for)`. The prompt was then misclassified as
        // conversational, the observation pipeline was skipped, and the LLM
        // had no screen context to act on → "agent can't perform any task".
        //
        // The fix: match `open|launch|start` followed by ANY word (the app
        // name). We also add explicit patterns for common single-word app
        // names (camera, settings, browser, etc.) so they're never
        // misclassified as chat.
        val automationPatterns = listOf(
            Regex("\\b(tap|click|press|double[- ]?tap|long[- ]?press)\\s+(on\\s+)?(the\\s+)?[a-z0-9]"),
            // open/launch/start + ANY word (app name, package, or "settings"/"camera"/etc.)
            Regex("\\b(open|launch|start|go\\s+to|switch\\s+to)\\s+[a-z0-9]"),
            // Explicit single-word app targets that are ALWAYS automation.
            Regex("\\b(open|launch|start)\\s+(camera|settings|browser|chrome|youtube|reddit|whatsapp|instagram|facebook|twitter|x|telegram|spotify|maps|gmail|calculator|clock|photos|gallery|app)\\b"),
            Regex("\\bswipe\\s+(up|down|left|right|to)\\b"),
            Regex("\\bscroll\\s+(up|down|left|right|to|until)\\b"),
            Regex("\\btype\\s+[\"']?[a-z0-9]"),
            Regex("\\b(take\\s+a\\s+)?screenshot\\b"),
            Regex("\\b(play|pause|skip|next|previous)\\s+(music|song|video|track|on)\\b"),
            Regex("\\b(search|find|look\\s+up)\\s+.+\\s+(on|in|for)\\s+(reddit|youtube|google|amazon|twitter|instagram|tiktok|spotify|netflix|maps|play store|app store|settings)\\b"),
            Regex("\\b(call|message|text|email|send)\\s+[a-z0-9]"),
            Regex("\\b(set|turn|enable|disable|toggle)\\s+(alarm|timer|wifi|bluetooth|brightness|volume|on|off)\\b"),
            Regex("\\bnavigate\\s+(to|back|home|up|down)\\b"),
            Regex("\\bhome\\s+button\\b|\\bback\\s+button\\b|\\brecents\\s+button\\b"),
            Regex("\\b(scheduled?|automate|every)\\s+"),
        )
        if (automationPatterns.any { it.containsMatchIn(p) }) return false

        // Strong conversational signals — if any of these appear, classify as chat.
        val conversationalPatterns = listOf(
            // Question words at start of sentence
            Regex("^(what|why|how|who|when|where|which|whose|whom)\\b"),
            Regex("\\b(can|could|would|will|do|did|does|is|are|am|should|may|might)\\s+you\\b"),
            // Conversational verbs
            Regex("\\b(explain|tell me about|describe|define|compare|contrast|list|summarize|translate|draft|write|compose|rewrite|rephrase|elaborate|teach|help me understand|give me|show me how)\\b"),
            // Generic chat openers / closers
            Regex("^(hi|hello|hey|yo|sup|thanks|thank you|thx|ok|okay|sure|yes|no|yep|nope|cool|nice|great)\\s*[!.?]*$"),
            Regex("\\b(please\\s+)?(write|draft|compose|generate)\\s+(a\\s+)?(email|message|letter|poem|essay|story|script|code|function|class|summary|outline|list)\\b"),
            // Meta-questions about the assistant itself
            Regex("\\b(who are you|what can you do|what are you|are you|your name|help)\\b"),
            // Knowledge / advice requests
            Regex("\\b(what'?(s| is)|difference between|meaning of|definition of|synonym|antonym|example of|how (do|does)|why (do|does|is|are))\\b"),
        )
        return conversationalPatterns.any { it.containsMatchIn(p) }
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
