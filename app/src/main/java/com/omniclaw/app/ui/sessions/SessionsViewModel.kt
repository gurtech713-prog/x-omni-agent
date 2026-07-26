package com.omniclaw.app.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omniclaw.app.behavior.BehaviorRecorder
import com.omniclaw.app.data.model.Session
import com.omniclaw.app.data.session.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val sessionRepo: SessionRepository,
    private val behaviorRecorder: BehaviorRecorder,
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
        refreshBehaviors()
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
                e.printStackTrace()
                _isLoading.value = false
            }
        }
    }

    fun refreshBehaviors() {
        _savedBehaviors.value = behaviorRecorder.listSaved()
    }

    fun newSession(): String {
        return sessionRepo.create("New session").id
    }

    fun stop(sessionId: String) {
        sessionRepo.stop(sessionId)
    }

    fun delete(sessionId: String) {
        viewModelScope.launch {
            sessionRepo.delete(sessionId)
        }
    }

    fun deleteSession(sessionId: String) = delete(sessionId)

    fun clearAllSessions() {
        viewModelScope.launch {
            sessionRepo.clearAll()
        }
    }

    fun startRecording() {
        behaviorRecorder.startRecording()
    }

    fun stopAndSaveRecording(name: String, triggerPhrase: String) {
        behaviorRecorder.stopAndSave(name, triggerPhrase)
        refreshBehaviors()
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
        refreshBehaviors()
    }

    companion object {
        private const val TAG = "SessionsViewModel"
    }
}
