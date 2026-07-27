package com.omniclaw.app.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val agent: AgentLoop,
    private val settings: SettingsRepository,
    private val ttsManager: com.omniclaw.app.voice.TextToSpeechManager,
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
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        // Subscribe to agent events so the UI can show them live.
        // Filter by the active session so the event log only shows the current
        // session's steps, not a global mix.
        viewModelScope.launch {
            agent.events.collect { e ->
                Log.d(TAG, "Received agent event for session ${e.sessionId}: $e")
                val activeId = _activeId.value
                if (activeId == null || e.sessionId == activeId) {
                    appendEvent(e)

                    // On-device Text-To-Speech integration:
                    // Only trigger TTS on the FINAL thought of each step
                    // (isFinal = true). During streaming, intermediate Thought
                    // events fire for every token delta — calling TTS on each
                    // would invoke the engine dozens of times per second,
                    // each interrupting the previous, producing garbled audio
                    // and stressing the TTS engine. The isFinal flag is set
                    // by AgentLoop after the LLM call completes.
                    //
                    // We also skip the ellipsis check (previously the only
                    // guard) because it was ineffective — streaming deltas
                    // don't end with "…", they end with whatever the LLM
                    // last emitted.
                    if (e is AgentLoop.Event.Thought && e.isFinal && uiPrefs.value.ttsEnabled) {
                        Log.d(TAG, "Triggering TTS speak (final thought, step ${e.step}): ${e.text}")
                        ttsManager.speak(e.text)
                    }
                }
            }
        }
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
        viewModelScope.launch {
            val session = sessions.getByIdSnapshot(id) ?: sessions.getById(id)
            if (session == null) {
                Log.w(TAG, "open($id) called for a session that doesn't exist (deleted?) — clearing activeId")
                _activeId.value = null
            } else {
                _activeId.value = id
            }
            clearEvents()
        }
    }

    fun send(text: String) {
        ttsManager.stop()
        clearEvents()
        // CHAT-2 FIX: create a fresh session if the active one is terminal
        // (DONE / FAILED / STOPPED). Previously, sending a message into a
        // completed session appended the user message + set status=RUNNING,
        // but AgentLoop.start() then re-launched the loop which called
        // isStopRequested() — that check saw the (briefly-RUNNING) status
        // and proceeded, BUT the session's prior messages (including the
        // final "DONE" assistant message) were still in history, confusing
        // the LLM. Worse, if the user sent a second message while the first
        // was still RUNNING, both messages landed in the same session and
        // the loop only saw the latest prompt — the first prompt was lost
        // (no re-run because the loop was already active).
        //
        // The correct UX: once a session is terminal, the next user message
        // starts a NEW session (preserving the old one in the Sessions list
        // for reference). This matches how ChatGPT / Claude / every chat app
        // behaves — a "done" conversation is archived, and the user starts
        // fresh.
        // M-38 FIX: the getByIdSnapshot() lookups (and newSession()/create())
        // touch the session store synchronously. send() is invoked from the
        // Compose Composer on the Main thread, so doing that work inline risks
        // an ANR. Move it into the viewModelScope.launch block (the same block
        // that already runs agent.start) so the DB access no longer happens on
        // the synchronous Main-thread entry path.
        viewModelScope.launch {
            val existingId = _activeId.value
            val existingSession = existingId?.let { sessions.getByIdSnapshot(it) }
            // CHAT-2 FIX: a terminal session (DONE/FAILED/STOPPED) is archived - the
            // next message starts a fresh session instead of reusing finished history.
            val existingIsTerminal = existingSession?.status?.let {
                it == SessionStatus.DONE || it == SessionStatus.FAILED || it == SessionStatus.STOPPED
            } == true
            val id = if (existingId == null || existingSession == null || existingIsTerminal) {
                Log.d(TAG, "Creating a new session for message: $text")
                newSession()
            } else {
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
    }
}
