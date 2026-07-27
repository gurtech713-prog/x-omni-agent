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
            clearHistoryCache(sessionId)
            learning.clearLessonCache(sessionId)
            episodeRecorder.finish(sessionId, "DONE")
            scope.launch { runCatchingCancellable { learning.runPostSessionPipeline(sessionId) } }
            return
        }

        // PERF-FIX (slow agent response): fetch modelConfig + agentTuning in
        // PARALLEL instead of sequentially. Both are independent DataStore reads
        // (and modelConfig also decrypts API keys via SecureStorage). On step 1
        // they were sequential on the critical path, adding ~20-100ms of pure
        // wait time before the LLM call could even start. coroutineScope + async
        // lets them overlap; if either fails, the other is cancelled automatically.
        val (cfg, tuning) = coroutineScope {
            val cfgAsync = async { settings.modelConfig.first() }
            val tuningAsync = async { settings.agentTuning.first() }
            Pair(cfgAsync.await(), tuningAsync.await())
        }
        // Plan-then-act state (created on step 1, replanned when stuck).
        var plan: Planner.Plan? = null

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
        val maxSteps = tuning.maxSteps
        // De-hardcoded: shadow the class-level default with the tunable value.
        val stepTimeoutMs = tuning.stepTimeoutMs
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
            // # STRATEGY CHANGE: No screenshots during pure chat.
            //
            // On step 1, classify the user's prompt: if it looks conversational
            // (question, explanation, drafting, greeting, etc.), SKIP the entire
            // observation pipeline — no accessibility snapshot, no MediaProjection
            // frame pull, no screenshot, no VLM call. The LLM doesn't need any
            // screen context to answer "What is the capital of France?"
            //
            // On step ≥ 2 (i.e. the LLM has emitted a non-`done` ACTION and we're
            // now mid-automation), we DO observe — but only via the cheap
            // accessibility tree snapshot, and only fall back to screenshot+VLM
            // if the tree is empty/sparse AND a VLM key is configured.
            //
            // This eliminates the privacy-invasive screenshot-then-VLM cycle
            // that previously fired on EVERY chat message whenever the
            // accessibility service wasn't connected.
            val isLikelyChatOnly = step == 1 && promptLooksConversational(prompt)
            var observation = if (isLikelyChatOnly) "" else scheduler.snapshot()
            var usedVision = false
            // Heuristic: if the tree is empty / very short / looks unparseable, ask the VLM (if VLM API key is configured).
            // SKIPPED entirely for chat-only turns — no screenshot, no VLM call.
            if (!isLikelyChatOnly && cfg.vlmApiKey.isNotBlank() && (observation.isBlank() || observation.length < 80 || observation.contains("accessibility service not connected", ignoreCase = true))) {
                // Prefer the continuous MediaProjection stream if available, else fall back to one-shot screenshot.
                var png: ByteArray? = ScreenCaptureService.latestFrameBytes()
                if (png == null || png.isEmpty()) png = scheduler.screenshot()
                if (png != null && png.isNotEmpty()) {
                    val vlmAnswer = runCatchingCancellable {
                        vlm.describe(png, "Describe the current screen. List interactive elements with their approximate tap coordinates (x, y). Be concise.")
                    }.getOrNull()
                    if (!vlmAnswer.isNullOrBlank()) {
                        observation = "[VISION FALLBACK]\n$vlmAnswer"
                        usedVision = true
                        Log.i(TAG, "Session $sessionId step $step: used vision fallback (${png.size} bytes)")
                    }
                }
            } else if (isLikelyChatOnly) {
                Log.i(TAG, "Session $sessionId step $step: conversational prompt detected — skipping screenshot & VLM probe")
            }
            // ---- Hermes-style planning: create a plan on the first step ----
            // PERF-FIX (slow agent response): SKIP the planner entirely for
            // conversational prompts. planner.makePlan() is a full extra LLM
            // roundtrip that doubles first-token latency on step 1 — it's only
            // useful for multi-step device automation, where the plan guides
            // the loop's check-off-as-you-go behavior. For a chat-only turn
            // ("What is the capital of France?") the plan would be discarded
            // immediately when the LLM emits ACTION: done, so the roundtrip
            // was pure latency with no benefit.
            if (step == 1 && tuning.enablePlanner && plan == null && !isLikelyChatOnly) {
                plan = planner.makePlan(cfg, prompt, observation)
            }
            val systemMsg = LlmClient.Message(
                role = "system",
                content = buildSystemPrompt(observation, recentActions, usedVision, sessionId, step, plan)
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
                var structuredUsage: LlmUsage? = null
                // ---- PRIMARY: Hermes-style structured tool-calling (fail-closed) ----
                // Request the device_action tool. If the model returns a structured
                // tool_call, synthesize a canonical THOUGHT/ACTION text so the existing
                // downstream parsing/dispatch/verify runs unchanged - but the action now
                // comes from a VALIDATED JSON object (no regex, no silent tap(0,0)), and
                // usage uses the provider's REAL token counts.
                if (tuning.useStructuredTools && toolsDisabledSessions[sessionId] != true) {
                    // FIX (live thinking + openai-compat agent failed):
                    //
                    // PREVIOUSLY: this path called llm.complete() (NON-streaming)
                    // and emitted ONE Thought event AFTER the full response
                    // returned. The user stared at a blank chat bubble for the
                    // full LLM latency (1-8s on typical providers). That's why
                    // "live thinking is not showing" — there was no streaming
                    // on this path at all.
                    //
                    // NOW: we use llm.streamWithTools() — a real SSE stream
                    // that emits both `delta.content` (the visible thinking
                    // text, token-by-token) AND `delta.tool_calls[i]` chunks
                    // (the structured tool call, arguments arriving in
                    // fragments that we concatenate by index). The user sees
                    // live thinking text the moment the first token arrives,
                    // and the tool_call is assembled as the stream progresses.
                    //
                    // If the provider returns HTTP 400 / 422 (typically "I
                    // don't support the `tools` parameter"), we mark the
                    // session as tools-disabled and fall through to the plain
                    // streaming path below, which sends no `tools` array. This
                    // is the fix for "openai-compat agent failed" — many
                    // self-hosted / older OpenAI-compat endpoints (Ollama,
                    // llama.cpp, some China providers) reject `tools` and the
                    // old code would burn through the structured-tools call,
                    // the streaming call, AND the non-streaming fallback
                    // before surfacing the error.
                    _events.tryEmit(Event.Thought(sessionId, step, "Thinking…", isFinal = false))

                    val thoughtBuilder = StringBuilder()
                    // tool_call arguments arrive in fragments — accumulate per index.
                    val toolCallArgs = mutableMapOf<Int, StringBuilder>()
                    // tool_call id+name arrive only in the FIRST delta for each
                    // index; subsequent deltas for the same index have nulls.
                    val toolCallMeta = mutableMapOf<Int, Pair<String?, String?>>()
                    var streamHadToolCall = false
                    var streamHadText = false
                    var lastEmitMs = 0L

                    try {
                        // Use plain withTimeout + collect (NOT runCatchingCancellable)
                        // because we want to INSPECT the exception below to decide
                        // whether to disable tools for the session. runCatchingCancellable
                        // would swallow the exception into Result.failure and we'd
                        // lose the HTTP status code.
                        //
                        // CancellationException is re-thrown by the explicit catch
                        // below — same contract as runCatchingCancellable, but with
                        // access to the non-cancellation exception for inspection.
                        withTimeout(stepTimeoutMs) {
                            llm.streamWithTools(
                                provider = cfg.provider,
                                baseUrl = cfg.baseUrl,
                                apiKey = cfg.apiKey,
                                model = cfg.model,
                                messages = listOf(systemMsg) + history,
                                temperature = cfg.temperature,
                                maxTokens = cfg.maxTokens,
                                tools = listOf(DeviceToolSchema.SPEC),
                                toolChoice = "auto",
                            ).collect { chunk ->
                                when (chunk) {
                                    is LlmClient.StreamChunk.TextDelta -> {
                                        thoughtBuilder.append(chunk.text)
                                        streamHadText = true
                                        // Throttle UI emissions to ~20/sec so we
                                        // don't drown the main thread with dozens
                                        // of recompositions per second. Always
                                        // emit the first few chars so the user
                                        // sees feedback instantly.
                                        val now = System.currentTimeMillis()
                                        if (now - lastEmitMs >= 50L || thoughtBuilder.length < 15) {
                                            lastEmitMs = now
                                            _events.tryEmit(Event.Thought(sessionId, step, thoughtBuilder.toString()))
                                        }
                                    }
                                    is LlmClient.StreamChunk.ToolCallDelta -> {
                                        streamHadToolCall = true
                                        toolCallArgs.getOrPut(chunk.index) { StringBuilder() }
                                            .append(chunk.argumentsChunk)
                                        // Merge id+name — they arrive in the first
                                        // delta for each index, null in subsequent.
                                        val existing = toolCallMeta[chunk.index]
                                        toolCallMeta[chunk.index] = Pair(
                                            chunk.id ?: existing?.first,
                                            chunk.name ?: existing?.second,
                                        )
                                    }
                                    is LlmClient.StreamChunk.Done -> {
                                        // finishReason available if needed for
                                        // length-based budget logic; not used here.
                                    }
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Timeout / user-stop / supersession — propagate to the
                        // outer handler so the session is marked FAILED or
                        // exits silently. Same contract as runCatchingCancellable.
                        throw e
                    } catch (e: com.omniclaw.app.data.llm.LlmException) {
                        // HTTP 400 / 422 typically means the provider doesn't
                        // support the `tools` parameter. Mark the session so
                        // subsequent steps skip the structured-tools path and
                        // go straight to plain streaming (which sends no tools).
                        // This is the key fix for "openai-compat agent failed"
                        // — we don't keep retrying with tools that the provider
                        // rejects.
                        val msg = e.message.orEmpty()
                        if (msg.contains("HTTP 400") || msg.contains("HTTP 422")) {
                            toolsDisabledSessions[sessionId] = true
                            Log.w(TAG, "Session $sessionId: provider rejected tools ($msg) — disabling structured tools for this session, falling back to plain streaming")
                        }
                        // For other HTTP errors (5xx, 429) or parse errors,
                        // don't disable tools permanently — just fall through
                        // to the streaming path below for this one step.
                    } catch (e: Exception) {
                        // Any other exception (IOException, parse error, etc.)
                        // — don't disable tools, just fall through to the
                        // streaming path below for this one step.
                        Log.w(TAG, "Session $sessionId step $step: streamWithTools failed (${e::class.simpleName}: ${e.message}) — falling back to plain streaming")
                    }

                    // If we got a tool_call, assemble the canonical
                    // THOUGHT/ACTION text from the accumulated arguments.
                    if (streamHadToolCall) {
                        val firstCallIdx = toolCallArgs.keys.minOrNull() ?: 0
                        val args = toolCallArgs[firstCallIdx]?.toString() ?: "{}"
                        val parsed = runCatching { DeviceToolSchema.parse(args) }.getOrNull()
                        if (parsed != null) {
                            val canonical = DeviceToolSchema.toActionLine(parsed.action)
                            streamedThought = buildString {
                                append("THOUGHT: ")
                                // Prefer the structured `thought` field; fall
                                // back to the streamed text (some providers
                                // emit reasoning in `content` before the
                                // tool_call, not inside the tool args).
                                appendLine(parsed.thought.ifBlank {
                                    thoughtBuilder.toString().ifBlank { "Acting on the plan." }
                                })
                                when {
                                    parsed.done -> appendLine("ACTION: done")
                                    canonical != null -> appendLine("ACTION: $canonical")
                                    else -> appendLine("ACTION: invalid")
                                }
                            }.trimEnd()
                            // structuredUsage stays null — token counts aren't
                            // reliably provided in streaming SSE. The estimate
                            // computed below will be used instead.
                            _events.tryEmit(Event.Thought(sessionId, step, streamedThought!!, isFinal = true))
                        }
                    } else if (streamHadText) {
                        // No tool_call — the model returned plain text (a
                        // conversational reply). Use the accumulated text as
                        // the thought. The downstream action parser looks for
                        // "ACTION:" lines; if none, it treats it as a "done"
                        // reply, which is exactly what we want for chat.
                        streamedThought = thoughtBuilder.toString()
                        _events.tryEmit(Event.Thought(sessionId, step, streamedThought!!, isFinal = true))
                    }
                    // If neither text nor tool_call arrived (empty response),
                    // streamedThought stays null and we fall through to the
                    // plain streaming path below as a last-resort retry.
                }
                // Per-step timeout — prevents a single slow LLM call (or a
                // hung connection) from blocking the session for 10+ minutes.
                // Cancellation propagates to the underlying OkHttp call via
                // suspendCancellableCoroutine (see LlmClient.stream).
                //
                // CHAT-FLOW FIX (Bug 8): track the partial-stream text so that
                // if streaming fails mid-generation, we can either keep the
                // partial text (preferred — it's what the user was reading) or
                // emit a clearing Thought before the non-streaming fallback
                // produces different text. Without this, the streaming bubble
                // would suddenly vanish and be replaced by entirely different
                // text from the fallback call, disorienting the user.
                var partialStreamText: String? = null
                if (streamedThought == null) runCatchingCancellable {
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
                            partialStreamText = thoughtBuilder.toString()
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= 50L || thoughtBuilder.length < 15) {
                                lastEmitMs = now
                                _events.tryEmit(Event.Thought(sessionId, step, thoughtBuilder.toString()))
                            }
                        }
                        if (thoughtBuilder.isNotEmpty()) {
                            streamedThought = thoughtBuilder.toString()
                            // CHAT-FLOW FIX: emit isFinal=true so the UI knows
                            // streaming is done and TTS can fire ONCE on the
                            // final text (not on every intermediate token).
                            _events.tryEmit(Event.Thought(sessionId, step, streamedThought!!, isFinal = true))
                        }
                    }
                }
                // CHAT-FLOW FIX (Bug 8): if streaming failed but produced
                // partial text, prefer the partial over the non-streaming
                // fallback. The user was reading the partial; replacing it
                // with entirely different text from a fresh LLM call is
                // disorienting and discards the user's reading progress.
                // Only fall back to non-streaming if we got NOTHING from stream.
                if (streamedThought == null && partialStreamText != null && partialStreamText!!.length >= 20) {
                    streamedThought = partialStreamText
                    // Emit a final Thought so the streaming bubble commits
                    // to the partial text (the uiMessages combine will stop
                    // showing it as a streaming bubble once isFinal=true).
                    _events.tryEmit(Event.Thought(sessionId, step, streamedThought!!, isFinal = true))
                    Log.w(TAG, "Session $sessionId step $step: stream failed mid-generation, keeping partial text (${partialStreamText!!.length} chars)")
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
                    usage = structuredUsage ?: LlmUsage(promptEstimate, completionEstimate, promptEstimate + completionEstimate)
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
                // A-C2 FIX: distinguish a step-level timeout (from the inner
                // `withTimeout(stepTimeoutMs)` — the current coroutine is still
                // active, only the inner child was cancelled) from a session-
                // level timeout (from the outer `withTimeoutOrNull(sessionTimeoutMs)`
                // — the current coroutine is also cancelled, so isActive is false).
                //
                // Both throw TimeoutCancellationException because the outer
                // withTimeoutOrNull propagates its TimeoutCancellationException
                // down to children via parentCancelled → cancel(parent.exception).
                // Without this distinction, the session-level timeout was caught
                // here, treated as a step-level timeout, and `return`-ed — which
                // made runLoopInner return normally to withTimeoutOrNull, so
                // `completedNormally` became `true` and the timed-out session was
                // mis-classified as successful. Re-throwing propagates the
                // session-level timeout up to withTimeoutOrNull, which converts
                // it to `null` → `completedNormally = false` → FAILED branch.
                if (e is kotlinx.coroutines.TimeoutCancellationException &&
                    currentCoroutineContext().isActive
                ) {
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
                    // Session-level cancellation (withTimeoutOrNull timeout, user
                    // stop, or session supersession) — propagate to the outer
                    // withTimeoutOrNull so it can convert to `null` and run the
                    // FAILED / STOPPED branch.
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
                // CHAT-FLOW FIX (Bug 9): persist a SYSTEM note so the user can
                // see WHY the session ended when they reopen it later. Previously
                // only the ephemeral event stream carried the message — the
                // persisted session had no record of the budget cap.
                scope.launch {
                    sessions.appendMessage(
                        sessionId,
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = ChatMessage.Role.SYSTEM,
                            content = msg,
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                }
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
                // A-H5 FIX: a refusal from handleSkillAction must NOT be reported
                // as a successful dispatch — otherwise the LLM believes the
                // skill executed and never tries an alternative approach.
                // Detect the two refusal patterns ("disabled by the user" for
                // toggled-off skills and "must be configured from the Settings
                // screen" for privileged skill actions refused for security)
                // and surface them as a tool-call error so the LLM retries
                // with a different strategy.
                val isRefusal = skillResult.contains("disabled by the user", ignoreCase = true) ||
                    skillResult.contains("must be configured from the Settings screen", ignoreCase = true)
                Pair(!isRefusal, skillResult)
            } else {
                val deviceAction = parseDeviceAction(action)
                if (deviceAction == null) {
                    // FAIL-CLOSED: a malformed tap/swipe must NOT fire a default gesture.
                    // A-H3 FIX: parseDeviceAction now returns null for unknown actions
                    // (was NoOp) — so an "ACTION: invalid" synthesized from a malformed
                    // structured tool-call ALSO surfaces here as a clear tool-result
                    // error to the LLM, rather than silently dispatching as success.
                    Pair(false, "error: could not parse valid coordinates from: $action")
                } else {
                    val dispatchOk = runCatchingCancellable { scheduler.dispatch(deviceAction) }.getOrDefault(false)
                    Pair(dispatchOk, if (dispatchOk) "ok" else "error")
                }
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
            val verifyResult = verifier.verifyLastDetailed(sessionId, call, preSnapshot = observation)
            val verifyOk = verifyResult.ok
            // Hermes-style plan tracking: a verified-successful action advances the plan.
            // A-L3 FIX: route through Plan.markNextStepDone() — PlanStep.markDone()
            // now returns a copy (the field is a val), so the previous
            // `plan?.nextStep?.let { it.markDone() }` form discarded the copy
            // and never actually advanced the plan.
            if (verifyOk) plan?.markNextStepDone()
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
            scope.launch { runCatchingCancellable { learning.recordDirectLesson(sessionId, fFp, fAs, outcome, observation) } }
            if (!verifyOk) {
                // GROUNDED self-correction: feed the model WHY the action failed
                // (diagnostic reason + whether the screen changed) instead of a
                // generic "try a different approach" string.
                val screenNote = if (verifyResult.postFingerprint == currentFingerprint) "The screen did NOT change." else "The screen changed."
                sessions.appendMessage(
                    sessionId,
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatMessage.Role.SYSTEM,
                        content = "Previous action FAILED (reason: ${verifyResult.reason}). Action was: ${call.name}. $screenNote Diagnose the cause and try a different, specific action.",
                        timestamp = System.currentTimeMillis(),
                    )
                )
                // Hermes-style stuck detection. SuccessMonitor.isStuck() was
                // previously DEAD CODE - now it triggers a replan (when a plan is
                // active) so the agent changes strategy instead of burning steps.
                if (verifier.isStuck(sessionId, tuning.stuckThreshold)) {
                    val stuckReason = "Stuck: ${tuning.stuckThreshold} consecutive failures (last: ${verifyResult.reason})."
                    if (tuning.enablePlanner && plan != null) {
                        plan = planner.replan(cfg, prompt, observation, plan!!, stuckReason)
                        sessions.appendMessage(
                            sessionId,
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = ChatMessage.Role.SYSTEM,
                                content = "Agent replanned. $stuckReason",
                                timestamp = System.currentTimeMillis(),
                            )
                        )
                    }
                    verifier.reset(sessionId)
                }
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
            runCatchingCancellable { learning.runPostSessionPipeline(sessionId) }
        }
    }

    private fun parseThoughts(text: String): List<String> =
        Regex("(?m)^THOUGHT:\\s*(.+)$").findAll(text).map { it.groupValues[1].trim() }.toList()

    private fun parseDeviceAction(action: String): DeviceAction? {
        val s = action.trim()
        return when {
            s.startsWith("tap", ignoreCase = true) -> {
                val m = Regex("(?i)tap\\s*\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)").find(s)
                val x = m?.groupValues?.getOrNull(1)?.toFloatOrNull()?.roundToInt()
                val y = m?.groupValues?.getOrNull(2)?.toFloatOrNull()?.roundToInt()
                // FAIL-CLOSED: a coordinate parse miss must NOT default to tap(0,0).
                if (x == null || y == null) null else DeviceAction.Tap(x, y)
            }
            s.startsWith("swipe", ignoreCase = true) -> {
                val m = Regex("(?i)swipe\\s*\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)").find(s)
                run {
                    val x1 = m?.groupValues?.getOrNull(1)?.toFloatOrNull()?.roundToInt()
                    val y1 = m?.groupValues?.getOrNull(2)?.toFloatOrNull()?.roundToInt()
                    val x2 = m?.groupValues?.getOrNull(3)?.toFloatOrNull()?.roundToInt()
                    val y2 = m?.groupValues?.getOrNull(4)?.toFloatOrNull()?.roundToInt()
                    // FAIL-CLOSED: missing coordinates -> null (never a bogus swipe).
                    if (x1 == null || y1 == null || x2 == null || y2 == null) null
                    else DeviceAction.Swipe(x1, y1, x2, y2)
                }
            }
            s.startsWith("type", ignoreCase = true) -> {
                // Use lazy/non-greedy match to handle quoted text correctly.
                val m = Regex("(?i)type\\s*\\(\\s*\"(.*?)\"\\s*\\)").find(s)
                val unquoted = m?.groupValues?.getOrNull(1)
                    ?: Regex("(?i)type\\s*\\(\\s*(.+?)\\s*\\)").find(s)?.groupValues?.getOrNull(1)
                val text = unquoted?.takeIf { it.isNotBlank() }?.trim() ?: return null
                DeviceAction.Type(text)
            }
            s.startsWith("launch", ignoreCase = true) || s.startsWith("open_app", ignoreCase = true) || s.startsWith("openapp", ignoreCase = true) -> {
                val m = Regex("(?i)(?:launch|open_app|openapp)\\s*\\(\\s*(?:\"|')?(.*?)(?:\"|')?\\s*\\)").find(s)
                val pkg = m?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.trim() ?: return null
                DeviceAction.Launch(pkg)
            }
            s.startsWith("back", ignoreCase = true) -> DeviceAction.Back
            s.startsWith("home", ignoreCase = true) -> DeviceAction.Home
            s.startsWith("screenshot", ignoreCase = true) -> DeviceAction.Screenshot
            // A-H3 FIX: unknown action strings return null (was NoOp). The
            // caller treats null as a tool-call error returned to the LLM,
            // so a malformed "ACTION: invalid" synthesized from a bad
            // structured tool-call no longer silently dispatches as success.
            else -> null
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
        // A-L5 + A-L6 FIX: skill IDs may contain digits (e.g. custom-1, app-search-2)
        // and args may contain nested parens (e.g. scheduled-automation(weekly:Wed:10:00|prompt)).
        // The old regex `([a-z\-]+)\s*\(\s*(.*?)\s*\)` was non-greedy on the arg and
        // excluded digits, so `skill:custom-1(bar(baz))` captured `custom` (without the
        // `-1`) and `bar(baz` (truncated at the first `)`). The new regex is greedy on
        // the arg and anchored to end-of-string, so nested parens are preserved and
        // digit-containing IDs match.
        val m = Regex("(?i)skill:([a-z0-9\\-]+)\\s*\\((.*)\\)$").find(action) ?: return null
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
                val count = runCatchingCancellable { gallery.syncMemory(n) }.getOrDefault(0)
                logger.logInfo(sessionIdForSkill, 0, "gallery-sync: $count photos")
                "Gallery memory sync completed (scanned $count photos)."
            }
            "gallery-search" -> {
                val photos = runCatchingCancellable { gallery.search(arg) }.getOrDefault(emptyList())
                logger.logInfo(sessionIdForSkill, 0, "gallery-search '$arg': ${photos.size} hits")
                "Gallery search found ${photos.size} results for '$arg'."
            }
            "capcut-theme-video" -> {
                val uris = runCatchingCancellable { gallery.stageForTheme(arg) }.getOrDefault(emptyList())
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
                val ok = runCatchingCancellable {
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
                val launched = runCatchingCancellable { scheduler.dispatch(DeviceAction.Launch(pkg)) }.getOrDefault(false)
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
                val result = runCatchingCancellable {
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
                val created = if (scheduleSpec.startsWith("weekly:")) {
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
                    // A-L7 FIX: unknown day name returns an error message to
                    // the LLM instead of silently defaulting to Wednesday (4).
                    // The previous behavior meant a typo like "weekly:Wde:10:00"
                    // would schedule the task for Wednesday without any feedback.
                    val day = dayMap[dayName]
                        ?: return "Unknown day '$dayName'. Use Sun/Mon/Tue/Wed/Thu/Fri/Sat."
                    runCatchingCancellable {
                        com.omniclaw.app.cron.ScheduledTaskWorker.scheduleWeekly(
                            // A-M7 FIX: use the full UUID (was .take(8)) — truncated
                            // UUIDs collided across rapid invocations, overwriting one
                            // another's WorkManager unique-work entry.
                            ctx, java.util.UUID.randomUUID().toString(),
                            "Agent-created weekly", promptText, setOf(day), time,
                        )
                        true
                    }.getOrDefault(false)
                } else {
                    val minutes = scheduleSpec.toLongOrNull() ?: 60L
                    runCatchingCancellable {
                        com.omniclaw.app.cron.ScheduledTaskWorker.scheduleInterval(
                            // A-M7 FIX: full UUID (was .take(8)).
                            ctx, java.util.UUID.randomUUID().toString(),
                            "Agent-created interval", promptText, minutes,
                        )
                        true
                    }.getOrDefault(false)
                }
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
        plan: Planner.Plan? = null,
    ): String = buildString {
        appendLine("You are X-OmniClaw, an edge-native multimodal Android agent with self-learning.")
        planner.renderForPrompt(plan)?.let { appendLine(); appendLine(it) }
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
            // A-L4 NOTE: the duplicate `scheduled-automation` key is INTENTIONAL —
            // this is a listOf (not mapOf), so both entries survive. They show
            // TWO usage EXAMPLES (interval vs weekly) for the same skill ID;
            // both are filtered together by isSkillEnabled, so the duplicate
            // has no functional impact. Keeping both lets the LLM see both
            // argument shapes in the prompt.
            "scheduled-automation" to "- skill:scheduled-automation(weekly:Wed:10:00|prompt) — schedule weekly task",
        )
        // PERF-FIX (slow agent response): build the enabled map ONCE per step
        // instead of calling isSkillEnabled() (which does a linear scan of
        // skillRepo.skills.value) for each of the 12 skill lines. 12 × O(n)
        // scans per step on the critical path before the LLM call.
        val skillEnabledMap = skillRepo.skills.value.associateBy { it.id }
        skillLines.filter { (id, _) ->
            // Skills not present in the repo default to enabled.
            skillEnabledMap[id]?.enabled ?: true
        }.forEach { appendLine(it.second) }
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
        //
        // PERF-FIX (slow agent response): cache the last (fingerprint, lessons)
        // pair per session. During automation the fingerprint changes every
        // step (different screen state), so this cache mostly helps the
        // chat-only path where the observation (and thus fingerprint) is the
        // same empty string across the single step. It also de-dupes redundant
        // Room queries when buildSystemPrompt is called more than once per
        // step (e.g. by the planner).
        val fingerprint = episodeRecorder.fingerprint(observation)
        val cached = lastLessonCache[sessionId]
        val lessons = if (cached != null && cached.first == fingerprint) {
            cached.second
        } else {
            val fresh = runCatchingCancellable { learning.lessonsForPrompt(fingerprint, sessionId = sessionId) }.getOrNull()
            lastLessonCache[sessionId] = fingerprint to fresh
            fresh
        }
        if (lessons != null) {
            appendLine(lessons)
            // Emit a LessonsApplied event so the chat UI can show a transparency
            // hint ("applied N lessons from past sessions"). Count is derived
            // from the number of [AVOID]/[USE]/[LOOP] tags in the injected text.
            // A-H1 FIX: the previous regex `\\[(AVOID|USE|LOOP|NOTE)\\]` required
            // the closing `]` immediately after the tag, but lessonsForPrompt
            // emits lines like `[AVOID conf=2] <text>` — `[AVOID ` has a space
            // (not `]`) after the tag. Using `\\b` (word boundary) instead of
            // `\\]` matches both `[AVOID]` and `[AVOID conf=2]`.
            val lessonCount = Regex("\\[(AVOID|USE|LOOP|NOTE)\\b").findAll(lessons).count()
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
        val automationPatterns = listOf(
            Regex("\\b(tap|click|press|double[- ]?tap|long[- ]?press)\\s+(on\\s+)?(the\\s+)?[a-z0-9]"),
            Regex("\\b(open|launch|start|go\\s+to|switch\\s+to)\\s+(com\\.|[a-z]+\\s*(app|on|in|for))"),
            Regex("\\bswipe\\s+(up|down|left|right|to)\\b"),
            Regex("\\bscroll\\s+(up|down|left|right|to|until)\\b"),
            Regex("\\btype\\s+[\"']?[a-z0-9]"),
            Regex("\\b(take\\s+a\\s+)?screenshot\\b"),
            Regex("\\b(play|pause|skip|next|previous)\\s+(music|song|video|track|on)\\b"),
            Regex("\\b(search|find|look\\s+up)\\s+.+\\s+(on|in|for)\\s+(reddit|youtube|google|amazon|taobao|twitter|instagram|tiktok|spotify|netflix|maps|play store|app store|settings)\\b"),
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
