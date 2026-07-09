package com.omniclaw.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omniclaw.app.agent.AgentLoop
import com.omniclaw.app.data.model.Session
import com.omniclaw.app.data.model.SessionStatus
import com.omniclaw.app.data.session.SessionRepository
import com.omniclaw.app.data.prefs.SettingsRepository
import com.omniclaw.app.data.prefs.UiPrefs
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
) : ViewModel() {

    val uiPrefs: StateFlow<UiPrefs> = settings.uiPrefs.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UiPrefs()
    )

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    val activeSession: StateFlow<Session?> = combine(_activeId, sessions.sessions) { id, list ->
        list.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _events = MutableStateFlow<List<AgentLoop.Event>>(emptyList())
    val events: StateFlow<List<AgentLoop.Event>> = _events.asStateFlow()

    init {
        // Subscribe to agent events so the UI can show them live.
        // Filter by the active session so the event log only shows the current
        // session's steps, not a global mix.
        viewModelScope.launch {
            agent.events.collect { e ->
                val activeId = _activeId.value
                if (activeId == null || e.sessionId == activeId) {
                    _events.value = (_events.value + e).takeLast(200)
                }
            }
        }
    }

    fun newSession(): String {
        val s = sessions.create("New session")
        _activeId.value = s.id
        return s.id
    }

    fun open(id: String) { _activeId.value = id }

    fun send(text: String) {
        // Create a new session if none is active or the active one is no longer running.
        val existingId = _activeId.value
        val existingSession = existingId?.let { sessions.getByIdSnapshot(it) }
        val id = if (existingId == null || existingSession == null) {
            newSession()
        } else {
            existingId
        }
        // Use getByIdSnapshot (in-memory) so we don't race with the DB write
        // that create() fires asynchronously. If the session isn't in memory yet
        // (shouldn't happen — create() adds it synchronously to the StateFlow),
        // we fall back to creating another one.
        val s = sessions.getByIdSnapshot(id) ?: run {
            val newId = newSession()
            sessions.getByIdSnapshot(newId) ?: return
        }
        viewModelScope.launch {
            agent.start(s, text)
        }
    }

    fun stop() {
        _activeId.value?.let { id ->
            viewModelScope.launch {
                agent.stop(id)
            }
        }
    }

    fun isRunning(): Boolean =
        activeSession.value?.status == SessionStatus.RUNNING
}
