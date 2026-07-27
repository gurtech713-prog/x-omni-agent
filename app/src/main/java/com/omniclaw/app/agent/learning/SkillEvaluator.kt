package com.omniclaw.app.agent.learning

import com.omniclaw.app.data.local.LessonDao
import com.omniclaw.app.data.model.Session
import com.omniclaw.app.data.model.SessionStatus
import com.omniclaw.app.data.session.SessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Skill evaluator for tracking learning engine effectiveness.
 *
 * Measures lesson quality and application success rates to improve future
 * lesson injection and auto-skill creation.
 */
@Singleton
class SkillEvaluator @Inject constructor(
    @ApplicationContext private val ctx: android.content.Context,
    private val lessonDao: LessonDao,
    private val sessionRepo: SessionRepository,
) {
    data class LessonQualityScore(
        val accuracy: Float,      // % of times lesson was applied correctly
        // A-L8 FIX: `relevance` and `successRate` are now nullable (were
        // hardcoded placeholder Floats 0.8f / 0.7f). The placeholders lied to
        // consumers — the engine doesn't actually track these metrics yet, so
        // UIs displayed "80% relevant / 70% success" for every lesson. null
        // lets consumers render "—" for unknown values instead of fake data.
        val relevance: Float?,
        val successRate: Float?,
        val totalApplications: Int,
        val successfulApplications: Int,
    )

    /** Evaluate a specific lesson's quality based on its history. */
    suspend fun evaluateLesson(lessonId: String): LessonQualityScore? = withContext(Dispatchers.IO) {
        val lesson = lessonDao.getById(lessonId) ?: return@withContext null
        
        // Count applications and successes using per-lesson ID queries added to LessonDao.
        val appCountRaw = lessonDao.getApplicationCountById(lessonId)
        val applications = appCountRaw ?: 0
        val successes = lessonDao.getSuccessfulApplicationsById(lessonId)

        if (applications == 0) {
            return@withContext LessonQualityScore(
                accuracy = 0f,
                relevance = null,
                successRate = null,
                totalApplications = 0,
                successfulApplications = 0,
            )
        }
        
        LessonQualityScore(
            // A-L9 FIX: clamp accuracy to [0, 1]. With per-lesson counters
            // updated from multiple call sites (recordDirectLesson,
            // reflectOnEpisode, trackImprovementMetrics) the success count
            // could exceed the application count via a race or a retroactive
            // application increment, yielding accuracy > 1.0 (e.g. 1.25) —
            // which broke UIs that rendered the value as "125%".
            accuracy = (successes.toFloat() / applications).coerceIn(0f, 1f),
            relevance = null,   // A-L8: not tracked yet — surface as unknown.
            successRate = null, // A-L8: not tracked yet — surface as unknown.
            totalApplications = applications,
            successfulApplications = successes,
        )
    }

    /** Track improvement metrics for a session. */
    suspend fun trackImprovementMetrics(sessionId: String) = withContext(Dispatchers.IO) {
        val session = sessionRepo.getById(sessionId) ?: return@withContext
        
        // Compare against previous sessions with similar fingerprints
        // This is a simplified version - full implementation would need more context
        val lessonsApplied = lessonDao.countLessonsForSession(sessionId)
        val isSuccess = session.status == SessionStatus.DONE
        
        if (isSuccess && lessonsApplied > 0) {
            // Reinforce lessons applied in successful sessions
            lessonDao.reinforceLessonsForSession(sessionId)
        }
    }

    /** Get overall learning metrics. */
    suspend fun getLearningMetrics(): LearningMetrics = withContext(Dispatchers.IO) {
        val totalLessons = lessonDao.count()
        val activeLessons = lessonDao.countActive()
        val avgConfidence = lessonDao.averageConfidence() ?: 0f
        
        LearningMetrics(
            totalLessons = totalLessons,
            activeLessons = activeLessons,
            averageConfidence = avgConfidence,
            totalSessions = sessionRepo.sessions.value.size,
        )
    }

    data class LearningMetrics(
        val totalLessons: Int,
        val activeLessons: Int,
        val averageConfidence: Float,
        val totalSessions: Int,
    )
}
