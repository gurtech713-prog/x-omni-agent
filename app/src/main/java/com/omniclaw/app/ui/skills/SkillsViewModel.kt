package com.omniclaw.app.ui.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omniclaw.app.data.model.Skill
import com.omniclaw.app.data.skill.SkillRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SkillsViewModel @Inject constructor(
    private val repo: SkillRepository,
) : ViewModel() {
    val skills: StateFlow<List<Skill>> = repo.skills
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init { viewModelScope.launch { repo.reload() } }

    fun toggle(id: String, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(id, enabled) }
    }
}
