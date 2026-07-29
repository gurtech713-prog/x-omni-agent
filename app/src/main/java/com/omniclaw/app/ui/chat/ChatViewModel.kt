package com.omniclaw.app.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omniclaw.app.BuildConfig
import com.omniclaw.app.agent.AgentLoop
import com.omniclaw.app.data.model.Session
import com.omniclaw.app.data.model.SessionStatus
import com.omniclaw.app.data.model.ChatMessage
import com.omniclaw.app.data.session.SessionRepository
import com.omniclaw.app.data.prefs.SettingsRepository
import com.omniclaw.app.data.prefs.UiPrefs
import com.omniclaw.app.data.prefs.ModelConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val agent: AgentLoop,
    private val settings: SettingsRepository,
    private val ttsManager: com.omniclaw.app.voice.TextToSpeechManager,
    private val audioRecorder: com.omniclaw.app.voice.AudioRecorder,
    private val sttClient: com.omniclaw.app.voice.SttClient,
    private val json: kotlinx.serialization.json.Json,
) : ViewModel() {

    val uiPrefs: StateFlow<UiPrefs> = settings.uiPrefs.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UiPrefs()
    )

    val modelConfig: StateFlow<ModelConfig> = settings.modelConfig.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ModelConfig()
    )

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    val activeSession: StateFlow<Session?> = combine(_activeId, sessions.sessions) { id, list ->
        list.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _events = MutableStateFlow<List<AgentLoop.Event>>(emptyList())
    val events: StateFlow<List<AgentLoop.Event>> = _events.asStateFlow()

    /**
     * Per-session token usage counter for the cost dashboard. Tracks cumulative
     * tokens across all steps in the active session. Updated when
     * [AgentLoop.Event.StepFinished] events arrive (which carry [LlmUsage]).
     */
    private val _sessionTokens = MutableStateFlow(0L)
    val sessionTokens: StateFlow<Long> = _sessionTokens.asStateFlow()

    /**
     * Retry the last user message. Used by the "Retry" button on failed sessions.
     * Finds the last USER message in the active session and re-sends it.
     */
    fun retryLastMessage() {
        val session = activeSession.value ?: return
        val lastUserMsg = session.messages.lastOrNull { it.role == ChatMessage.Role.USER }
        if (lastUserMsg != null) {
            Log.i(TAG, "Retrying last user message: ${lastUserMsg.content}")
            send(lastUserMsg.content)
        }
    }

    // ---- Voice input (microphone button in Composer) ----

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /**
     * Start recording audio for voice-to-text input. When [stopRecording] is
     * called, the audio is transcribed via [SttClient] and the resulting text
     * is emitted via [_transcribedText] for the Composer to display.
     */
    fun startRecording() {
        if (_isRecording.value) return
        val file = audioRecorder.start() ?: run {
            Log.w(TAG, "Failed to start recording — permission denied or mic busy")
            return
        }
        _isRecording.value = true
        Log.i(TAG, "Started voice recording: ${file.absolutePath}")
    }

    private val _transcribedText = MutableStateFlow<String?>(null)
    val transcribedText: StateFlow<String?> = _transcribedText.asStateFlow()

    /**
     * Stop recording and transcribe the audio. The transcribed text is emitted
     * via [transcribedText] so the Composer can insert it into the text field.
     */
    fun stopRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false
        val file = audioRecorder.stop() ?: run {
            Log.w(TAG, "Recording produced no file")
            return
        }
        Log.i(TAG, "Stopped recording, transcribing: ${file.absolutePath}")
        viewModelScope.launch {
            val text = runCatchingCancellable { sttClient.transcribe(file) }.getOrNull()
            if (text.isNullOrBlank()) {
                Log.w(TAG, "Transcription returned empty")
                _transcribedText.value = ""
            } else {
                Log.i(TAG, "Transcribed: $text")
                _transcribedText.value = text
            }
            // Clean up the temp audio file
            runCatching { file.delete() }
        }
    }

    /** Clear the transcribed text (called after the Composer consumes it). */
    fun clearTranscribedText() {
        _transcribedText.value = null
    }

    /**
     * Export the active session as a JSON string. Includes all messages,
     * tool calls, thoughts, and metadata. Returns null if no active session.
     */
    fun exportSession(): String? {
        val session = activeSession.value ?: return null
        return runCatching {
            val export = kotlinx.serialization.json.buildJsonObject {
                put("sessionId", session.id)
                put("title", session.title)
                put("createdAt", session.createdAt)
                put("status", session.status.name)
                put("stepCount", session.stepCount)
                put("tokenUsage", session.tokenUsage)
                put("messages", kotlinx.serialization.json.JsonArray(session.messages.map { msg ->
                    kotlinx.serialization.json.buildJsonObject {
                        put("id", msg.id)
                        put("role", msg.role.name)
                        put("content", msg.content)
                        put("timestamp", msg.timestamp)
                        if (msg.toolCalls.isNotEmpty()) {
                            put("toolCalls", kotlinx.serialization.json.JsonArray(msg.toolCalls.map { tc ->
                                kotlinx.serialization.json.buildJsonObject {
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    put("args", tc.args)
                                    put("result", tc.result ?: "")
                                    put("ok", tc.ok)
                                }
                            }))
                        }
                        if (msg.thoughts.isNotEmpty()) {
                            put("thoughts", kotlinx.serialization.json.JsonArray(msg.thoughts.map {
                                kotlinx.serialization.json.JsonPrimitive(it)
                            }))
                        }
                    }
                }))
            }
            json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), export)
        }.getOrNull()
    }

    /**
     * Bounded backing buffer for [events]. Appends are O(1) on an [ArrayDeque],
     * unlike the previous `(_events.value + e).takeLast(200)` which performed
     * two O(n) list copies on every agent event (dozens/sec during token
     * streaming), causing GC pressure and main-thread jank. Guarded by
     * [eventsLock]; the StateFlow is republished with an immutable snapshot
     * after each append (M-37).
     */
    private val eventsLock = Any()
    private val eventBuffer = ArrayDeque<AgentLoop.Event>(200)

    val uiMessages: StateFlow<List<ChatMessage>> = combine(
        activeSession,
        events
    ) { session, eventList ->
        if (session == null) return@combine emptyList()
        val baseMessages = session.messages
        if (session.status != SessionStatus.RUNNING) return@combine baseMessages

        // Find if there is an active streaming thought.
        // Only the LAST event matters — if it's a Thought, the LLM is still
        // streaming (or just finished) and we append a temporary assistant
        // bubble showing the live text. Once any other event arrives
        // (ToolCall, StepFinished, Completed, etc.) the streaming bubble
        // disappears because the real assistant message has already been
        // appended to session.messages by AgentLoop.
        val lastEvent = eventList.lastOrNull()
        if (lastEvent is AgentLoop.Event.Thought && !lastEvent.isFinal) {
            val streamingId = "streaming-thought-${lastEvent.sessionId}-${lastEvent.step}"
            // Append temporary assistant message for live streaming thought.
            baseMessages + ChatMessage(
                id = streamingId,
                role = ChatMessage.Role.ASSISTANT,
                content = lastEvent.text,
                timestamp = System.currentTimeMillis()
            )
        } else {
            baseMessages
        }
    }
        // U-M13: combine re-emits on every event token (each Thought delta
        // updates eventList). Without distinctUntilChanged, stateIn republishes
        // a structurally-equal list when an event flips a non-message field,
        // allocating a fresh List<ChatMessage> and triggering a LazyColumn
        // recomposition for nothing. ChatMessage is a data class so structural
        // equality short-circuits the allocation.
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Append [e] to the bounded [eventBuffer] and republish [events] (M-37). */
    private fun appendEvent(e: AgentLoop.Event) {
        val snapshot = synchronized(eventsLock) {
            eventBuffer.addLast(e)
            while (eventBuffer.size > 200) eventBuffer.removeFirst()
            eventBuffer.toList()
        }
        _events.value = snapshot
    }

    /** Clear both the backing buffer and the published [events] (M-37). */
    private fun clearEvents() {
        synchronized(eventsLock) { eventBuffer.clear() }
        _events.value = emptyList()
    }

    init {
        Log.d(TAG, "ChatViewModel initialized")
        // SESSION RECOVERY FIX: when the ViewModel is recreated (process kill,
        // config change, or navigation pop+restore), _activeId is null. Without
        // this recovery, the user's next message would ALWAYS create a new
        // session — even if they had an active conversation seconds ago.
        //
        // On init, if there's no active session, grab the most recent session
        // from the repository and set it as active. This way the user can
        // continue their last conversation without the app starting a fresh
        // chat on every message. We skip sessions that are FAILED or STOPPED
        // (those are genuinely terminal and shouldn't be continued).
        viewModelScope.launch {
            if (_activeId.value == null) {
                val recent = sessions.sessions.value
                    .filter { it.status != SessionStatus.FAILED && it.status != SessionStatus.STOPPED }
                    .maxByOrNull { it.lastActiveAt }
                if (recent != null) {
                    Log.i(TAG, "Recovered last active session: ${recent.id} (status=${recent.status})")
                    _activeId.value = recent.id
                }
            }
        }
        // Subscribe to agent events so the UI can show them live.
        // Filter by the active session so the event log only shows the current
        // session's steps, not a global mix.
        viewModelScope.launch {
            agent.events.collect { e ->
                // PERF-FIX (slow agent response): gate the per-event Log.d with
                // BuildConfig.DEBUG. During streaming, dozens of Thought events
                // fire per second — each previously triggered `e.toString()` on
                // a data class containing the FULL accumulated thought text,
                // effectively re-serializing the growing string every ~50ms.
                // That's GC pressure + Logcat I/O on the collector (main)
                // thread, competing with the UI for CPU. In release builds
                // (where Log.d is a no-op anyway) the toString() allocation
                // still happened before the call. BuildConfig.DEBUG short-
                // circuits the whole expression in release.
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Received agent event for session ${e.sessionId}: ${e::class.simpleName}")
                }
                val activeId = _activeId.value
                if (activeId == null || e.sessionId == activeId) {
                    appendEvent(e)

                    // CHAT-FLOW FIX (Bug 4): TTS strategy.
                    //
                    // PREVIOUSLY: TTS fired on EVERY Thought(isFinal=true),
                    // which fires once per step. For a 5-step device task, TTS
                    // spoke 5 times — once per intermediate "THOUGHT: I'll tap
                    // X / ACTION: tap(...)". Worse, the raw text including
                    // "THOUGHT:" and "ACTION:" scaffolding was passed verbatim
                    // to the TTS engine, so the user heard "T-H-O-U-G-H-T colon
                    // … A-C-T-I-O-N colon tap 100 200".
                    //
                    // NOW: TTS fires ONLY on Event.Completed (the terminal
                    // event of a conversational turn), and only the cleaned
                    // reply text is spoken. Intermediate step thoughts are
                    // silent (they're internal reasoning, not user-facing
                    // replies). For multi-step device tasks, the user hears
                    // only the final summary at the end.
                    if (e is AgentLoop.Event.Completed && uiPrefs.value.ttsEnabled) {
                        val cleaned = cleanThoughtForSpeech(e.finalText)
                        if (cleaned.isNotBlank()) {
                            Log.d(TAG, "Triggering TTS speak (Completed, cleaned): $cleaned")
                            ttsManager.speak(cleaned)
                        }
                    }
                    // Track cumulative token usage for the cost dashboard.
                    if (e is AgentLoop.Event.StepFinished) {
                        _sessionTokens.value = _sessionTokens.value + e.usage.totalTokens
                    }
                    // Reset the token counter when a new session starts.
                    if (e is AgentLoop.Event.StepStarted && e.step == 1) {
                        _sessionTokens.value = 0L
                    }
                }
            }
        }
    }

    /**
     * Strip the canonical "THOUGHT:" / "ACTION:" scaffolding from a thought
     * before passing it to TTS or display. The agent loop's internal format
     * (e.g. "THOUGHT: Sure, here's the answer.\nACTION: done") is meant for
     * parsing, not for the user's ears.
     *
     * CHAT-FLOW FIX (Bug 4): TTS previously spoke the raw scaffolding verbatim,
     * producing garbled audio like "T-H-O-U-G-H-T colon … A-C-T-I-O-N colon done".
     */
    private fun cleanThoughtForSpeech(raw: String): String {
        if (raw.isBlank()) return ""
        val lines = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("THOUGHT:", ignoreCase = true) }
            .filterNot { it.startsWith("ACTION:", ignoreCase = true) }
            .filterNot { it.startsWith("PLAN:", ignoreCase = true) }
            .filterNot { it.startsWith("OBSERVATION:", ignoreCase = true) }
            .filterNot { it.startsWith("[VISION", ignoreCase = true) }
            .toList()
        return lines.joinToString(" ").trim()
    }

    fun newSession(): String {
        Log.i(TAG, "Creating a new chat session")
        val s = sessions.create("New session")
        _activeId.value = s.id
        // Clear the event log so step/thought/tool-call entries from the
        // previously active session don't bleed into the fresh one.
        clearEvents()
        return s.id
    }

    fun open(id: String) {
        Log.i(TAG, "Opening session with ID: $id")
        // CHAT-FLOW FIX (Bug 11): set _activeId SYNCHRONOUSLY before the
        // coroutine hop. Previously _activeId was set inside viewModelScope.launch,
        // so if the user navigated to a session and typed+sends within the
        // same frame window, send() read the OLD _activeId.value and
        // dispatched the message to the previous session. The launch block
        // now only handles the not-found → null case + clearEvents().
        _activeId.value = id
        viewModelScope.launch {
            val session = sessions.getByIdSnapshot(id) ?: sessions.getById(id)
            if (session == null) {
                Log.w(TAG, "open($id) called for a session that doesn't exist (deleted?) — clearing activeId")
                _activeId.value = null
            }
            // Clear the event log so step/thought/tool-call entries from the
            // previously active session don't bleed into the newly opened one.
            clearEvents()
        }
    }

    fun send(text: String) {
        // CHAT-FLOW FIX (Bug 7): validate non-empty before doing anything.
        // OverlayService (voice), DeepLinkManager, and ScheduledTaskWorker all
        // call send() directly without the Composer's isNotBlank() guard.
        // An empty prompt creates a session, appends an empty USER message,
        // and sends an empty prompt to the LLM, which returns a confused or
        // empty response.
        if (text.isBlank()) {
            Log.w(TAG, "send() called with blank text — ignoring")
            return
        }
        ttsManager.stop()
        // CHAT-FLOW FIX (Bug 2 + Bug 10): DO NOT clearEvents() here
        // unconditionally. Previously clearEvents() ran synchronously on
        // the Main thread BEFORE the launch block decided whether to reuse
        // or create a session. If a session was actively streaming (e.g. the
        // supersession path, or any non-Composer entry point such as voice
        // input), the streaming-thought bubble vanished instantly because
        // uiMessages' combine saw `eventList.lastOrNull() == null`.
        //
        // Now: only clear events in the new-session branch. For the reuse
        // branch, the agent loop's own events will refresh the buffer
        // naturally; prior steps' thoughts/tool-calls remain visible (which
        // is what the user expects in an ongoing conversation).
        viewModelScope.launch {
            val existingId = _activeId.value
            val existingSession = existingId?.let { sessions.getByIdSnapshot(it) }
            // CHAT-CONTINUATION FIX: previously DONE was treated as terminal, but
            // AgentLoop sets status=DONE after EVERY completed conversational turn
            // (a normal reply, a "done" action, max-steps reached, etc). That meant
            // the very next user message ALWAYS created a new session — the user
            // could never continue a conversation in the same chat.
            //
            // Now only FAILED and STOPPED are treated as terminal (abnormal end
            // states where continuing makes no sense). A DONE session is a normal
            // "agent finished its turn" state and the user can keep chatting in
            // the same thread. IDLE/RUNNING/DONE all reuse the existing session.
            val existingIsTerminal = existingSession?.status?.let {
                it == SessionStatus.FAILED || it == SessionStatus.STOPPED
            } == true
            val id = if (existingId == null || existingSession == null || existingIsTerminal) {
                // LAST-CHANCE RECOVERY: before creating a brand-new session,
                // check if there's a recent non-terminal session we can revive.
                // This catches the race where the init-block recovery hasn't
                // completed yet (it's async) but the user already typed and
                // sent a message. Without this, the first message after a
                // ViewModel recreation would always create a new session even
                // though a perfectly good DONE session exists.
                val recoverable = sessions.sessions.value
                    .filter { it.status != SessionStatus.FAILED && it.status != SessionStatus.STOPPED }
                    .maxByOrNull { it.lastActiveAt }
                if (recoverable != null && !existingIsTerminal) {
                    Log.d(TAG, "Recovering recent session ${recoverable.id} (status=${recoverable.status}) instead of creating new")
                    _activeId.value = recoverable.id
                    recoverable.id
                } else {
                    Log.d(TAG, "Creating a new session for message: $text")
                    // CHAT-FLOW FIX (Bug 2): only clear the event log when
                    // actually starting a new session. The reuse path preserves
                    // prior step/thought events for ongoing-conversation context.
                    clearEvents()
                    newSession()
                }
            } else {
                Log.d(TAG, "Reusing existing session $existingId (status=${existingSession.status}) for message: $text")
                existingId
            }
            // Use getByIdSnapshot (in-memory) so we don't race with the DB write
            // that create() fires asynchronously. If the session isn't in memory yet
            // (shouldn't happen — create() adds it synchronously to the StateFlow),
            // we fall back to creating another one.
            val s = sessions.getByIdSnapshot(id) ?: run {
                Log.d(TAG, "Session $id not found in memory, creating another one")
                val newId = newSession()
                sessions.getByIdSnapshot(newId) ?: return@launch
            }
            Log.i(TAG, "Sending message to agent (session: $id): $text")
            agent.start(s, text)
        }
    }

    fun stop() {
        // CHAT-5 FIX: prefer the activeId, but fall back to the activeSession's
        // id if activeId is null (can happen briefly during ViewModel init or
        // if _activeId was reset). Previously, if activeId was null, stop()
        // silently did nothing — the user could press Stop repeatedly with no
        // effect while the agent kept running. Now we also check the
        // activeSession StateFlow for the currently-displayed session id.
        val activeId = _activeId.value ?: activeSession.value?.id
        Log.i(TAG, "Stopping agent loop for session: $activeId")
        activeId?.let { id ->
            viewModelScope.launch {
                agent.stop(id)
            }
        }
        ttsManager.stop()
    }

    override fun onCleared() {
        Log.d(TAG, "ChatViewModel cleared")
        super.onCleared()
        ttsManager.stop()
    }

    fun isRunning(): Boolean =
        activeSession.value?.status == SessionStatus.RUNNING

    companion object {
        private const val TAG = "ChatViewModel"

        /** runCatching that re-throws CancellationException. */
        private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
            Result.success(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
