package com.omniclaw.app.ui.memory

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omniclaw.app.data.local.LessonDao
import com.omniclaw.app.data.local.LessonEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Exposes learned lessons to the Memory screen's "Learned Lessons" section.
 *
 * Lessons are written by [com.omniclaw.app.agent.learning.LearningEngine] during
 * agent execution; this VM is read-only — it just surfaces them for the user
 * to inspect and optionally delete.
 *
 * NOTE: Must be annotated `@HiltViewModel` so that `hiltViewModel()` in
 * [MemoryScreen] can obtain an instance via Hilt's default VM factory.
 * Without it, Hilt falls back to the no-arg default factory which cannot
 * satisfy the `LessonDao` constructor parameter and crashes at runtime.
 */
@HiltViewModel
class LessonsViewModel @Inject constructor(
    private val lessonDao: LessonDao,
) : ViewModel() {

    val lessons: StateFlow<List<LessonEntity>> = lessonDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun forget(id: String) {
        Log.i(TAG, "Forgetting/deleting learned lesson: $id")
        viewModelScope.launch { lessonDao.delete(id) }
    }

    fun clearAll() {
        Log.i(TAG, "Clearing all learned lessons")
        viewModelScope.launch { lessonDao.clearAll() }
    }

    companion object {
        private const val TAG = "LessonsViewModel"
    }
}
