package com.omniclaw.app.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omniclaw.app.behavior.BehaviorRecorder
import com.omniclaw.app.data.model.Session
import com.omniclaw.app.data.session.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val behaviorRecorder: BehaviorRecorder,
) : ViewModel() {

    val sessions: StateFlow<List<Session>> = sessionRepository.sessions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isRecording = behaviorRecorder.isRecording

    // Private mutable, public read-only — prevents external mutation.
    private val _savedBehaviors = MutableStateFlow(behaviorRecorder.listSaved())
    val savedBehaviors: StateFlow<List<BehaviorRecorder.RecordedSkill>> = _savedBehaviors.asStateFlow()

    fun stop(id: String) = sessionRepository.stop(id)
    fun delete(id: String) = sessionRepository.delete(id)
    fun newSession(): String = sessionRepository.create("New session").id

    /** Refresh the saved-behaviors list from disk. Call on app foreground or after
     *  the agent loop creates a skill via skill:skill-creator / skill:behavior-replay. */
    fun refreshBehaviors() {
        _savedBehaviors.value = behaviorRecorder.listSaved()
    }

    /** Start recording device actions for behavior cloning. */
    fun startRecording() {
        behaviorRecorder.startRecording()
        refreshBehaviors()
    }

    /** Stop recording and save as a reusable skill. Returns the new skill ID, or null. */
    fun stopAndSaveRecording(name: String, triggerPhrase: String): String? {
        val id = behaviorRecorder.stopAndSave(name, triggerPhrase)
        refreshBehaviors()
        return id
    }

    /** Cancel an in-progress recording without saving. */
    fun cancelRecording() {
        behaviorRecorder.cancel()
    }

    /** Replay a previously-recorded behavior skill. */
    fun replay(skillId: String) {
        viewModelScope.launch { behaviorRecorder.replay(skillId) }
    }
}
