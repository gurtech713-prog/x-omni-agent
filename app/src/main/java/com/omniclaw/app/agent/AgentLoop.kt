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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

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
            startMutex.withLock {
                runningJobs[session.id]?.let { existing ->
                    existing.cancel(CancellationException("Superseded by a new run for session ${session.id}"))
                    runCatching { existing.join() }
                }
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
        if (job != null) runCatching { job.join() }
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
            }
        } catch (e: CancellationException) {
            // Cooperative cancellation — user requested stop or session was superseded.
            _events.tryEmit(Event.Stopped(sessionId))
            sessions.setStatus(sessionId, SessionStatus.DONE)
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
        }
    }

    private suspend fun runLoopInner(sessionId: String, prompt: String) {
        // Shortcut pre-match: if the prompt matches a saved bookmark, launch it immediately
        // and finish the session. This makes bookmark shortcuts work instantly.
        if (deepLinks.launchByPhrase(prompt)) {
            scope.launch {
                sessions.appendMessage(
                    sessionId,
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.SYSTEM,
                        content = "Launched bookmark shortcut matching: $prompt",
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            sessions.setStatus(sessionId, SessionStatus.DONE)
            _events.tryEmit(Event.Completed(sessionId, "Launched bookmark shortcut matching: $prompt"))
            return
        }

        val cfg = settings.modelConfig.first()

        // Guard: validate config before starting the loop. Give the user a clear
        // error instead of a cryptic HTTP 401 or timeout.
        if (cfg.provider == com.omniclaw.app.data.prefs.LlmProvider.OPENAI_COMPAT) {
            if (cfg.baseUrl.isBlank()) {
                val errMsg = "No Base URL configured. Go to Settings → AI Provider and enter your endpoint (e.g. https://api.openai.com/v1)."
                scope.launch {
                    sessions.appendMessage(sessionId, ChatMessage(java.util.UUID.randomUUID().toString(), ChatMessage.Role.SYSTEM, errMsg, System.currentTimeMillis()))
                }
                _events.tryEmit(Event.Failed(sessionId, errMsg))
                sessions.setStatus(sessionId, SessionStatus.FAILED)
                return
            }
            val isLocalUrl = cfg.baseUrl.contains("localhost", ignoreCase = true) ||
                cfg.baseUrl.contains("127.0.0.1") ||
                cfg.baseUrl.contains("10.0.2.2") ||
                cfg.baseUrl.contains("192.168.") ||
                cfg.baseUrl.contains("10.") ||
                cfg.baseUrl.contains("ollama", ignoreCase = true) ||
                cfg.baseUrl.contains("lmstudio", ignoreCase = true)
            if (cfg.apiKey.isBlank() && !isLocalUrl) {
                val errMsg = "No API key configured. Go to Settings → AI Provider and enter your API key."
                scope.launch {
                    sessions.appendMessage(sessionId, ChatMessage(java.util.UUID.randomUUID().toString(), ChatMessage.Role.SYSTEM, errMsg, System.currentTimeMillis()))
                }
                _events.tryEmit(Event.Failed(sessionId, errMsg))
                sessions.setStatus(sessionId, SessionStatus.FAILED)
                return
            }
        } else if (cfg.provider == com.omniclaw.app.data.prefs.LlmProvider.GEMINI) {
            if (cfg.apiKey.isBlank()) {
                val errMsg = "No Gemini API key configured. Go to Settings → AI Provider and enter your Google AI Studio key."
                scope.launch {
                    sessions.appendMessage(sessionId, ChatMessage(java.util.UUID.randomUUID().toString(), ChatMessage.Role.SYSTEM, errMsg, System.currentTimeMillis()))
                }
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
            // PERFORMANCE: build history incrementally
            val history = buildHistory(sessionId)

            // ---- Dual-track observation: structured tree preferred, vision fallback ----
            var observation = scheduler.snapshot()
            var usedVision = false
            // Heuristic: if the tree is empty / very short / looks unparseable, ask the VLM (if VLM API key is configured).
            if (cfg.vlmApiKey.isNotBlank() && (observation.isBlank() || observation.length < 80 || observation.contains("not connected", ignoreCase = true))) {
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
                // Per-step timeout — prevents a single slow LLM call (or a
                // hung connection) from blocking the session for 10+ minutes.
                // Cancellation propagates to the underlying OkHttp call via
                // suspendCancellableCoroutine (see LlmClient.stream).
                runCatching {
                    withTimeout(stepTimeoutMs) {
                        val thoughtBuilder = StringBuilder()
                        var lastEmitMs = 0L
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
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= 50L || thoughtBuilder.length < 15) {
                                lastEmitMs = now
                                _events.tryEmit(Event.Thought(sessionId, step, thoughtBuilder.toString()))
                            }
                        }
                        if (thoughtBuilder.isNotEmpty()) {
                            streamedThought = thoughtBuilder.toString()
                            _events.tryEmit(Event.Thought(sessionId, step, streamedThought!!))
                        }
                    }
                }

                if (streamedThought != null) {
                    thought = streamedThought
                    // CJK-safe token estimate: ~1 token/char for CJK, ~1/4 for ASCII.
                    // Conservative (lower) bound to avoid premature budget cutoffs.
                    val completionEstimate = (thought.length / 2).toLong()
                    // Estimate prompt tokens from the actual message content
                    // (system prompt + full history). Prompt tokens dominate the
                    // cost — they include the full history, memory, and skills
                    // list — and were never counted when promptTokens was
                    // hardcoded to 0, breaking the maxSessionTokens budget guard.
                    val promptChars = (listOf(systemMsg) + history).sumOf { it.content.length }
                    val promptEstimate = (promptChars / 2).toLong()
                    usage = LlmUsage(promptEstimate, completionEstimate, promptEstimate + completionEstimate)
                } else {
                    // Streaming produced nothing (or failed) — fall back to non-streaming with fast retry.
                    val result = withTimeout(stepTimeoutMs) {
                        com.omniclaw.app.core.retry(
                            maxAttempts = 2,
                            baseDelayMs = 1000,
                            maxDelayMs = 3000,
                            retryable = { e ->
                                when (e) {
                                    is com.omniclaw.app.data.llm.RateLimitException -> true
                                    is java.io.IOException -> true
                                    is LlmException -> {
                                        val msg = e.message.orEmpty()
                                        msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504") || msg.contains("429")
                                    }
                                    else -> false
                                }
                            },
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
                    }
                    thought = result.text
                    usage = result.usage
                }
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    val errMsg = "Request timed out after ${stepTimeoutMs / 1000}s. The AI provider did not respond in time."
                    logger.logError(
                        AgentLogger.ErrorLocation(
                            sessionId = sessionId, step = step, action = "llm.timeout",
                            className = "AgentLoop", methodName = "runLoopInner",
                            lineNumber = Thread.currentThread().stackTrace.getOrNull(1)?.lineNumber ?: -1,
                            message = errMsg,
                        )
                    )
                    scope.launch {
                        sessions.appendMessage(
                            sessionId,
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = ChatMessage.Role.SYSTEM,
                                content = "Agent failed: $errMsg",
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                    _events.tryEmit(Event.Failed(sessionId, errMsg))
                    sessions.setStatus(sessionId, SessionStatus.FAILED)
                    learning.clearLessonCache(sessionId)
                    triggerReflection(sessionId, "FAILED")
                    return
                } else {
                    throw e
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
                scope.launch {
                    sessions.appendMessage(
                        sessionId,
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = ChatMessage.Role.SYSTEM,
                            content = "Agent failed: $errMsg",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                _events.tryEmit(Event.Failed(sessionId, errMsg))
                sessions.setStatus(sessionId, SessionStatus.FAILED)
                learning.clearLessonCache(sessionId)
                triggerReflection(sessionId, "FAILED")
                return
            }

            _events.tryEmit(Event.Thought(sessionId, step, thought, isFinal = true))
            // CHAT-10 FIX: guard against empty LLM responses. If the model
            // returned an empty string (e.g. content-filtered by safety
            // settings, or a malformed response with empty choices), don't
            // append an empty assistant bubble — that would show as a blank
            // message in the chat UI and immediately mark the session DONE
            // with no explanation. Instead, substitute a clear system note
            // so the user understands what happened and can retry.
            if (thought.isBlank()) {
                val note = "(The model returned an empty response. This can happen with safety filters, reasoning-only outputs, or content policies. Try rephrasing your request.)"
                sessions.appendMessage(
                    sessionId,
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.SYSTEM,
                        content = note,
                        timestamp = System.currentTimeMillis(),
                    )
                )
                _events.tryEmit(Event.Completed(sessionId, "Empty response from model."))
                sessions.setStatus(sessionId, SessionStatus.DONE)
                clearHistoryCache(sessionId)
                learning.clearLessonCache(sessionId)
                triggerReflection(sessionId, "DONE")
                return
            }

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
                // The assistant message was already appended unconditionally
                // above (after the LLM call). Don't append it again here — the
                // previous duplicate call double-wrote every conversational
                // and "done" turn into the session history and the chat UI.
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
            // PERFORMANCE: compute fingerprint and normalized action ONCE per step
            val currentFingerprint = episodeRecorder.fingerprint(observation)
            val sig = episodeRecorder.normalizeAction(action)
            val stuckCount = recentActions.count { it.first == sig && it.second == currentFingerprint }
            if (stuckCount >= 2) {
                _events.tryEmit(Event.LoopDetected(sessionId))
                sessions.setStatus(sessionId, SessionStatus.FAILED)
                episodeRecorder.recordLoop(sessionId, step, observation, action)
                learning.clearLessonCache(sessionId)
                triggerReflection(sessionId, "FAILED")
                return
            }
            recentActions.addLast(sig to currentFingerprint)
            if (recentActions.size > 6) recentActions.removeFirst()

            // Dispatch — check for skill: actions first, then fall through to device actions.
            val started = System.currentTimeMillis()
            val parsedAction = parseActionLine(action)
            val skillResult = handleSkillAction(parsedAction, sessionId)
            val (ok, resultText) = if (skillResult != null) {
                Pair(true, skillResult)
            } else {
                val deviceAction = parseDeviceAction(action)
                val dispatchOk = runCatching { scheduler.dispatch(deviceAction) }.getOrDefault(false)
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
            // PERFORMANCE: defer behavior recording off the hot path
            val fCall = call
            scope.launch { behaviorRecorder.recordAction(fCall) }
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

            // Verify — pass the already-captured observation as preSnapshot so
            // SuccessMonitor doesn't build the same tree a second time.
            val verifyOk = verifier.verifyLast(sessionId, call, preSnapshot = observation)
            // Record this step in the episode for self-learning reflection.
            episodeRecorder.recordStep(sessionId, step, observation, action, call, verifyOk)
            // Record a direct (fingerprint, action, outcome) lesson immediately —
            // uses the ALREADY-COMPUTED fingerprint and actionSig from above.
            val outcome = when {
                !call.ok -> com.omniclaw.app.data.model.Lesson.LessonOutcome.FAILURE
                !verifyOk -> com.omniclaw.app.data.model.Lesson.LessonOutcome.FAILURE
                else -> com.omniclaw.app.data.model.Lesson.LessonOutcome.SUCCESS
            }
            // PERFORMANCE: defer lesson recording off the hot path
            val fFp = currentFingerprint; val fAs = sig
            scope.launch { runCatching { learning.recordDirectLesson(sessionId, fFp, fAs, outcome, observation) } }
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
            //
            // The poll uses a CHEAP fingerprint (packageName:childCount) via
            // the scheduler's snapshotBlocking() — NOT the full SHA-256
            // fingerprint used for cycle detection. The cheap fingerprint is
            // sufficient for "did the screen change?" polling and avoids
            // re-hashing up to 8KB of observation text 15x per step.
            // PERFORMANCE: Skip stabilization if the action already failed —
            // the screen didn't change so there's nothing to wait for. This saves
            // 200-400ms on every failed attempt (common when the app isn't responding).
            if (call.ok && (
                parsedAction.startsWith("tap", ignoreCase = true) ||
                parsedAction.startsWith("swipe", ignoreCase = true) ||
                parsedAction.startsWith("launch", ignoreCase = true) ||
                parsedAction.startsWith("back", ignoreCase = true) ||
                parsedAction.startsWith("home", ignoreCase = true) ||
                parsedAction.startsWith("type", ignoreCase = true)
            )) {
                val initialDelay = getAdaptiveInitialDelay()
                kotlinx.coroutines.delay(initialDelay)
                
                // Wait for the screen to stabilize — compare two cheap fingerprints.
                val cap = getAdaptiveCap()
                val start = System.currentTimeMillis()
                var prevFp = cheapStabilizationFingerprint()
                while (System.currentTimeMillis() - start < cap) {
                    kotlinx.coroutines.delay(getAdaptivePollInterval())
                    val curFp = cheapStabilizationFingerprint()
                    if (curFp == prevFp) break  // stabilized
                    prevFp = curFp
                }
                
                // Update stabilization metrics for future adaptive delays
                updateStabilizationMetrics(System.currentTimeMillis() - start)
            }
        }

        _events.tryEmit(Event.Completed(sessionId, "Max steps reached."))
        sessions.setStatus(sessionId, SessionStatus.DONE)
        triggerReflection(sessionId, "DONE")
    }

    /**
     * Parse an action line into a normalized action string for stabilization
     * checks. Returns the raw action after stripping leading whitespace.
     */
    private fun parseActionLine(action: String): String = action.trim()

    /**
     * Trigger self-learning reflection on session end.
     *
     * Runs asynchronously so the agent loop returns immediately — the user
     * doesn't wait for reflection to finish before seeing "DONE". The
     * LearningEngine extracts lessons from the recorded episode and (if the
     * session succeeded with >3 steps) auto-creates a reusable SKILL.md.
     *
     * FIXED: Now uses [LearningEngine.runPostSessionPipeline] internally which
     * serializes reflection + auto-skill creation with a per-session Mutex,
     * preventing the race where both coroutines race on the same episode.
     */
    private fun triggerReflection(sessionId: String, finalStatus: String) {
        episodeRecorder.finish(sessionId, finalStatus)
        scope.launch {
            runCatching { learning.runPostSessionPipeline(sessionId) }
        }
    }

    private fun parseThoughts(text: String): List<String> =
        Regex("(?m)^THOUGHT:\\s*(.+)$").findAll(text).map { it.groupValues[1].trim() }.toList()

    private fun parseDeviceAction(action: String): DeviceAction {
        val s = action.trim()
        return when {
            s.startsWith("tap", ignoreCase = true) -> {
                val m = Regex("(?i)tap\\s*\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)").find(s)
                val x = m?.groupValues?.getOrNull(1)?.toFloatOrNull()?.roundToInt() ?: 0
                val y = m?.groupValues?.getOrNull(2)?.toFloatOrNull()?.roundToInt() ?: 0
                DeviceAction.Tap(x, y)
            }
            s.startsWith("swipe", ignoreCase = true) -> {
                val m = Regex("(?i)swipe\\s*\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)").find(s)
                DeviceAction.Swipe(
                    m?.groupValues?.getOrNull(1)?.toFloatOrNull()?.roundToInt() ?: 0,
                    m?.groupValues?.getOrNull(2)?.toFloatOrNull()?.roundToInt() ?: 0,
                    m?.groupValues?.getOrNull(3)?.toFloatOrNull()?.roundToInt() ?: 0,
                    m?.groupValues?.getOrNull(4)?.toFloatOrNull()?.roundToInt() ?: 0,
                )
            }
            s.startsWith("type", ignoreCase = true) -> {
                // Use lazy/non-greedy match to handle quoted text correctly.
                val m = Regex("(?i)type\\s*\\(\\s*\"(.*?)\"\\s*\\)").find(s)
                val unquoted = m?.groupValues?.getOrNull(1)
                    ?: Regex("(?i)type\\s*\\(\\s*(.+?)\\s*\\)").find(s)?.groupValues?.getOrNull(1)
                DeviceAction.Type(unquoted.orEmpty().trim())
            }
            s.startsWith("launch", ignoreCase = true) || s.startsWith("open_app", ignoreCase = true) || s.startsWith("openapp", ignoreCase = true) -> {
                val m = Regex("(?i)(?:launch|open_app|openapp)\\s*\\(\\s*(?:\"|')?(.*?)(?:\"|')?\\s*\\)").find(s)
                DeviceAction.Launch(m?.groupValues?.getOrNull(1).orEmpty().trim())
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
     * Returns a result string the agent loop can show to the user, or null
     * if the action is not a skill.
     *
     * FIXED: Background skill actions (gallery-sync, replay, skill-creator,
     * scheduled-automation) now emit [Event.SkillComplete] when finished so
     * the UI gets actual results instead of just "started..." placeholders.
     */
    private suspend fun handleSkillAction(action: String, sessionIdForSkill: String): String? {
        val m = Regex("(?i)skill:([a-z\\-]+)\\s*\\(\\s*(.*?)\\s*\\)").find(action) ?: return null
        val skillId = m.groupValues[1].lowercase()
        val arg = m.groupValues[2].trim().trim('"')
        // Refuse disabled skills with a clear message back to the LLM so it
        // can pick a different approach or ask the user to re-enable the
        // skill. Without this check the toggle was purely cosmetic — the LLM
        // could still invoke any skill: action regardless of the user's choice.
        if (!isSkillEnabled(skillId)) {
            return "Skill '$skillId' is disabled by the user. Use a different approach or ask the user to enable it in Settings."
        }
        return when (skillId) {
            "gallery-qa", "gallery-memory" -> {
                val n = arg.toIntOrNull() ?: 20
                // Run synchronously (with fallback) instead of fire-and-forget,
                // so the tool-call result reflects actual completion.
                val count = runCatching { gallery.syncMemory(n) }.getOrDefault(0)
                logger.logInfo(sessionIdForSkill, 0, "gallery-sync: $count photos")
                "Gallery memory sync completed (scanned $count photos)."
            }
            "gallery-search" -> {
                val photos = runCatching { gallery.search(arg) }.getOrDefault(emptyList())
                logger.logInfo(sessionIdForSkill, 0, "gallery-search '$arg': ${photos.size} hits")
                "Gallery search found ${photos.size} results for '$arg'."
            }
            "capcut-theme-video" -> {
                val uris = runCatching { gallery.stageForTheme(arg) }.getOrDefault(emptyList())
                logger.logInfo(sessionIdForSkill, 0, "capcut-stage '$arg': ${uris.size} photos")
                "Staged ${uris.size} photos for CapCut theme '$arg'."
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
                // Run replay synchronously with bounded timeout so the agent loop
                // doesn't hang forever on a broken behavior skill.
                val ok = runCatching {
                    withTimeout(30_000L) { behaviorRecorder.replay(arg) }
                }.getOrDefault(false)
                logger.logInfo(sessionIdForSkill, 0, "behavior-replay($arg): ${if (ok) "ok" else "failed"}")
                if (ok) "Behavior replay '$arg' completed successfully." else "Behavior replay '$arg' failed or timed out."
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
                val launched = runCatching { scheduler.dispatchBlocking(DeviceAction.Launch(pkg)) }.isSuccess
                if (launched) {
                    "Launched $pkg. Next step: tap the search bar and type '$query'."
                } else {
                    "Failed to launch $pkg. Try a different package or use the browser."
                }
            }
            "model-config", "model-provider", "channel-config" -> {
                // SECURITY: These skill actions have been INTENTIONALLY REMOVED
                // from the LLM's action vocabulary. They allowed a prompt-injected
                // LLM to silently rewrite the API endpoint, exfiltrate the API
                // key, or redirect the Discord webhook to an attacker URL — a
                // privilege-escalation vector. Users configure these explicitly
                // via the Settings screen; the LLM must not be able to.
                //
                // If a caller passes one of these IDs, log + refuse.
                logger.logError(
                    AgentLogger.ErrorLocation(
                        sessionId = sessionIdForSkill, step = 0,
                        action = "skill:$skillId",
                        className = "AgentLoop", methodName = "handleSkillAction",
                        lineNumber = 0,
                        message = "Refused privileged skill action '$skillId' from LLM context."
                    )
                )
                "Action '$skillId' must be configured from the Settings screen — it cannot be modified by the agent."
            }
            "skill-creator" -> {
                // Use the LLM to draft a SKILL.md from the user's description,
                // then persist it under filesDir/skills/<id>/SKILL.md (runtime cache).
                val result = runCatching {
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
                    "Created skill '$id' from description: $arg"
                }.getOrElse { e ->
                    logger.logError(
                        AgentLogger.ErrorLocation(
                            sessionId = sessionIdForSkill, step = 0,
                            action = "skill:skill-creator",
                            className = "AgentLoop", methodName = "handleSkillAction",
                            lineNumber = 0,
                            message = "Skill creation failed: ${e.message}",
                        )
                    )
                    "Skill creation failed: ${e.message}"
                }
                result
            }
            "scheduled-automation" -> {
                // Parse arg as "intervalMinutes|prompt" or "weekly:Wed:10:00|prompt"
                val parts = arg.split('|', limit = 2)
                if (parts.size < 2) return "Usage: skill:scheduled-automation(60|prompt text)"
                val scheduleSpec = parts[0].trim()
                val promptText = parts[1].trim()
                val created = runCatching {
                    if (scheduleSpec.startsWith("weekly:")) {
                        // Parse "weekly:Wed:10:00" -> day=Wed, time=10:00.
                        // Take everything after "weekly:" as `rest`, then
                        // split on the FIRST colon: day = rest.substringBefore(':'),
                        // time = rest.substringAfter(':'). The previous
                        // substringAfterLast(':') returned "00" (just the
                        // minutes), silently scheduling tasks for midnight.
                        val rest = scheduleSpec.substringAfter("weekly:")
                        val dayName = rest.substringBefore(':')
                        val time = rest.substringAfter(':')
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
                    true
                }.getOrDefault(false)
                if (created) {
                    "Scheduled automation created: $scheduleSpec → '$promptText'"
                } else {
                    "Failed to create scheduled automation: $scheduleSpec → '$promptText'"
                }
            }
            else -> null
        }
    }

    /**
     * Lookup a skill's enabled flag from [skillRepo]. Skills not present in
     * the repository (internal helpers like gallery-search, open-bookmark,
     * behavior-replay) default to enabled — there's no toggle to disable them.
     */
    private fun isSkillEnabled(skillId: String): Boolean {
        val skill = skillRepo.skills.value.firstOrNull { it.id == skillId } ?: return true
        return skill.enabled
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
        // Filter the skill list by the user's enabled/disabled toggles so
        // disabled skills are not advertised to the LLM. Skills not present
        // in the SkillRepository (internal helpers like gallery-search,
        // open-bookmark, behavior-replay) are always shown — there's no
        // toggle to disable them.
        val skillLines = listOf(
            "gallery-qa" to "- skill:gallery-qa(20)             — scan latest 20 photos into memory",
            "gallery-search" to "- skill:gallery-search(parrot)     — search gallery by keyword",
            "capcut-theme-video" to "- skill:capcut-theme-video(parrot) — stage theme photos for CapCut",
            "clipboard-to-shortcut" to "- skill:clipboard-to-shortcut(name)— save clipboard URL as a bookmark",
            "open-bookmark" to "- skill:open-bookmark(name)        — launch a saved bookmark by name",
            "behavior-replay" to "- skill:behavior-replay(id)        — replay a recorded behavior skill",
            "app-search" to "- skill:app-search(com.reddit.frontpage:query) — launch app + search",
            "amazon-search" to "- skill:app-search(com.amazon.mShop.android:query) — launch Amazon + search",
            "skill-creator" to "- skill:skill-creator(description) — LLM-draft a new SKILL.md",
            "scheduled-automation" to "- skill:scheduled-automation(60|prompt) — schedule interval task",
            "scheduled-automation" to "- skill:scheduled-automation(weekly:Wed:10:00|prompt) — schedule weekly task",
        )
        skillLines.filter { isSkillEnabled(it.first) }.forEach { appendLine(it.second) }
        appendLine()
        appendLine("Rules:")
        appendLine("- For questions, explanations, summaries, or advice: put the full answer in")
        appendLine("  THOUGHT and use ACTION: done. Do NOT use device actions for conversational replies.")
        appendLine("- Only use device actions (tap/swipe/type/launch) when the user explicitly asks")
        appendLine("  to automate something on the device.")
        appendLine("- Stop with done when the user's request is satisfied.")
        appendLine("- Never repeat an action that already failed twice.")
        appendLine("- Model config, model provider, and channel (webhook) config CANNOT be changed")
        appendLine("  by you — the user configures these explicitly from the Settings screen.")
        appendLine("- Be concise. No markdown.")
        appendLine()
        // ---- Self-learning: inject lessons from past sessions ----
        // The LearningEngine queries the lesson store for lessons matching
        // the current screen fingerprint. If any are found, they're appended
        // here so the LLM can avoid known-bad actions and repeat known-good ones.
        val fingerprint = episodeRecorder.fingerprint(observation)
        val lessons = runCatching { learning.lessonsForPrompt(fingerprint, sessionId = sessionId) }.getOrNull()
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
        // ---- Long-term Memory & working memory injection (Hermes style) ----
        // Bounded: pin entries first, then sort by createdAt desc, cap at 50 to
        // avoid unbounded context-window growth as the user accumulates memories.
        val maxMemoryEntries = 50
        val memoryEntries = memoryRepo.entries.value
            .sortedWith(compareByDescending<MemoryEntry> { it.pinned }.thenByDescending { it.createdAt })
            .take(maxMemoryEntries)
        if (memoryEntries.isNotEmpty()) {
            appendLine("---- Long-term Memory & Facts (Self-learning) ----")
            memoryEntries.forEach { entry ->
                val pinIndicator = if (entry.pinned) "[PINNED]" else ""
                appendLine("- ${entry.kind}: ${entry.content} $pinIndicator")
            }
            val total = memoryRepo.entries.value.size
            if (total > maxMemoryEntries) {
                appendLine("... ($total total, showing top $maxMemoryEntries by pin + recency)")
            }
            appendLine()
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

    /**
     * Incremental history builder — fetches all messages only on the first
     * call per session, then only appends new messages on subsequent steps.
     * Avoids fetching + deserializing the full Room message list every step.
     */
    private val historyCache = java.util.concurrent.ConcurrentHashMap<String, MutableList<LlmClient.Message>>()

    private suspend fun buildHistory(sessionId: String): List<LlmClient.Message> {
        val cached = historyCache.getOrPut(sessionId) { mutableListOf() }
        val session = sessions.getById(sessionId) ?: return cached.toList()
        if (cached.size == session.messages.size) return cached.toList()
        val startIdx = cached.size
        for (i in startIdx until session.messages.size) {
            val msg = session.messages[i]
            cached.add(LlmClient.Message(role = msg.role.name.lowercase(), content = msg.content))
        }
        return cached.toList()
    }

    private fun clearHistoryCache(sessionId: String) {
        historyCache.remove(sessionId)
    }

    private suspend fun isStopRequested(sessionId: String): Boolean {
        // Stop if the user requested it OR the session was deleted / externally stopped.
        if (stopMutex.withLock { sessionId in stopSet }) return true
        val s = sessions.getById(sessionId) ?: return true
        if (s.status == SessionStatus.STOPPED) return true
        return false
    }

    /**
     * Cheap polling fingerprint used ONLY by the post-action stabilization
     * while-loop. Returns a string derived from the accessibility root's
     * packageName + childCount — sufficient to detect "did the screen
     * change?" without re-snapshotting the whole tree + SHA-256 hashing it.
     *
     * This is intentionally NOT the same fingerprint as
     * [EpisodeRecorder.fingerprint] — that one is a stable cross-session
     * screen signature; this one is a transient "is the screen still moving?"
     * probe. They serve different purposes and should not be mixed.
     *
     * Returns an empty string if the accessibility service is disconnected
     * or the root is null — the stabilization loop will treat empty == empty
     * as "stable" and exit, which is the correct behavior when there's no
     * tree to observe (no point polling).
     */
    /**
     * O(1) stabilization fingerprint — reads root.packageName:childCount
     * WITHOUT building the full tree. Previous impl built the entire tree
     * (~10-50KB string) then took first 80 chars — wasted 12+ tree traversals/step.
     */
    private fun cheapStabilizationFingerprint(): String {
        return scheduler.stabilizationFingerprint()
    }

    @Volatile
    private var avgStabilizationTimeMs = 150L

    private fun getAdaptiveInitialDelay(): Long {
        return (avgStabilizationTimeMs * 0.5).toLong().coerceIn(30L, 200L)
    }

    private fun getAdaptiveCap(): Long {
        return 400L  // reduced from 600ms
    }

    private fun getAdaptivePollInterval(): Long {
        return 80L  // increased from 50ms (fewer CPU wake-ups)
    }

    private fun updateStabilizationMetrics(elapsedMs: Long) {
        avgStabilizationTimeMs = ((avgStabilizationTimeMs * 0.7) + (elapsedMs * 0.3)).toLong()
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
