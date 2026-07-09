package com.omniclaw.app.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omniclaw.app.data.memory.MemoryRepository
import com.omniclaw.app.data.model.MemoryEntry
import com.omniclaw.app.data.model.MemoryEntry.MemoryKind
import com.omniclaw.app.data.model.Skill
import com.omniclaw.app.data.skill.SkillRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val repo: MemoryRepository,
    private val skillRepo: SkillRepository,
) : ViewModel() {

    val entries: StateFlow<List<MemoryEntry>> = repo.entries
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Skills are now surfaced on the Memory tab (previously a separate Skills
    // tab). The bottom nav was reduced from 6 to 5 destinations to comply
    // with Material Design's NavigationBar limit.
    val skills: StateFlow<List<Skill>> = skillRepo.skills
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init { viewModelScope.launch { skillRepo.reload() } }

    fun pin(id: String, pinned: Boolean) = repo.pin(id, pinned)
    fun forget(id: String) = repo.forget(id)
    fun clearWorking() = repo.clearWorking()
    fun toggleSkill(id: String, enabled: Boolean) {
        viewModelScope.launch { skillRepo.setEnabled(id, enabled) }
    }
}
