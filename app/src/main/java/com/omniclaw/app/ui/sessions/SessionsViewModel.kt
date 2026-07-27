package com.omniclaw.app.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omniclaw.app.agent.AgentLoop
import com.omniclaw.app.behavior.BehaviorRecorder
import com.omniclaw.app.data.model.Session
import com.omniclaw.app.data.session.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val sessionRepo: SessionRepository,
    private val behaviorRecorder: BehaviorRecorder,
    private val agent: AgentLoop,
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val isRecording: StateFlow<Boolean> = behaviorRecorder.isRecording

    private val _savedBehaviors = MutableStateFlow<List<BehaviorRecorder.RecordedSkill>>(emptyList())
    val savedBehaviors: StateFlow<List<BehaviorRecorder.RecordedSkill>> = _savedBehaviors.asStateFlow()

    // Tracks the single perpetual sessions collector so refresh() cancels the
    // previous one instead of stacking a new never-completing collector each call.
    private var sessionsJob: Job? = null

    init {
        loadSessions()
        viewModelScope.launch { refreshBehaviors() }
    }

    fun loadSessions() {
        sessionsJob?.cancel()
        sessionsJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                // StateFlow collection never completes, so clear the loading flag on
                // each emission (the first marks initial load done) instead of in an
                // unreachable `finally` block.
                sessionRepo.sessions.collectLatest { sessionList ->
                    _sessions.value = sessionList
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                // Re-throw cancellation so structured concurrency isn't broken
                // (H-32): swallowing CancellationException would prevent the
                // collector job from being cancelled cleanly on refresh()/onCleared.
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
                _isLoading.value = false
            }
        }
    }

    suspend fun refreshBehaviors() {
        // listSaved() does synchronous disk I/O; hop to IO so we never block the
        // main thread (H-31). Callers invoke this from viewModelScope.launch.
        val saved = withContext(Dispatchers.IO) { behaviorRecorder.listSaved() }
        _savedBehaviors.value = saved
    }

    fun newSession(): String {
        return sessionRepo.create("New session").id
    }

    fun stop(sessionId: String) {
        viewModelScope.launch {
            agent.stop(sessionId)
            sessionRepo.stop(sessionId)
        }
    }

    fun delete(sessionId: String) {
        viewModelScope.launch {
            agent.stop(sessionId)
            sessionRepo.delete(sessionId)
        }
    }

    fun deleteSession(sessionId: String) = delete(sessionId)

    fun startRecording() {
        behaviorRecorder.startRecording()
    }

    fun stopAndSaveRecording(name: String, triggerPhrase: String) {
        behaviorRecorder.stopAndSave(name, triggerPhrase)
        viewModelScope.launch { refreshBehaviors() }
    }

    fun cancelRecording() {
        behaviorRecorder.cancel()
    }

    fun replay(skillId: String) {
        viewModelScope.launch {
            behaviorRecorder.replay(skillId)
        }
    }

    fun refresh() {
        loadSessions()
        viewModelScope.launch { refreshBehaviors() }
    }

    companion object {
        private const val TAG = "SessionsViewModel"
    }
}
