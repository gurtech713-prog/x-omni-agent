package com.omniclaw.app.agent.learning

import com.omniclaw.app.data.model.Lesson
import com.omniclaw.app.data.model.ToolCall
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records the trajectory of a single agent session as a list of [EpisodeStep]s.
 *
 * Each step captures:
 *   - the screen observation (accessibility tree or VLM description)
 *   - a coarse [screenFingerprint] for cross-session matching
 *   - the action the agent took
 *   - the outcome (success / failure / loop / neutral)
 *
 * The recorder is per-session and in-memory — it does NOT persist directly.
 * On session end, [LearningEngine.reflectOnEpisode] reads the recorded steps,
 * extracts lessons, and persists them via [com.omniclaw.app.data.local.LessonDao].
 *
 * Design: the recorder is cheap to construct (just an ArrayDeque) so we don't
 * need a pool — one instance per session, held by the AgentLoop.
 */
@Singleton
class EpisodeRecorder @Inject constructor() {

    /** A single step in an episode — (observation, action, outcome). */
    data class EpisodeStep(
        val stepIndex: Int,
        val observation: String,
        val screenFingerprint: String,
        val action: String,
        val actionSignature: String,
        val outcome: Lesson.LessonOutcome,
        val toolCallId: String?,
        val timestamp: Long,
    )

    /** Per-session episode. Keyed by sessionId so multiple parallel sessions don't collide. */
    private data class Episode(
        val sessionId: String,
        val userPrompt: String,
        val steps: MutableList<EpisodeStep> = mutableListOf(),
        var finalStatus: String = "RUNNING",
    )

    private val episodes = mutableMapOf<String, Episode>()
    private val lock = Any()

    /** Start recording a new episode for [sessionId]. */
    fun start(sessionId: String, userPrompt: String) {
        synchronized(lock) {
            episodes[sessionId] = Episode(sessionId, userPrompt)
        }
    }

    /** Record one step. Called by AgentLoop after each action is verified. */
    fun recordStep(
        sessionId: String,
        stepIndex: Int,
        observation: String,
        action: String,
        call: ToolCall,
        verifyOk: Boolean,
    ) {
        val fingerprint = fingerprint(observation)
        val actionSig = normalizeAction(action)
        val outcome = when {
            !call.ok -> Lesson.LessonOutcome.FAILURE
            !verifyOk -> Lesson.LessonOutcome.FAILURE
            else -> Lesson.LessonOutcome.SUCCESS
        }
        synchronized(lock) {
            val ep = episodes.getOrPut(sessionId) { Episode(sessionId, "") }
            ep.steps.add(
                EpisodeStep(
                    stepIndex = stepIndex,
                    observation = observation.take(500),  // truncate for storage
                    screenFingerprint = fingerprint,
                    action = action,
                    actionSignature = actionSig,
                    outcome = outcome,
                    toolCallId = call.id,
                    timestamp = System.currentTimeMillis(),
                )
            )
        }
    }

    /** Mark a loop-detected step (agent repeated the same action). */
    fun recordLoop(sessionId: String, stepIndex: Int, observation: String, action: String) {
        synchronized(lock) {
            val ep = episodes.getOrPut(sessionId) { Episode(sessionId, "") }
            ep.steps.add(
                EpisodeStep(
                    stepIndex = stepIndex,
                    observation = observation.take(500),
                    screenFingerprint = fingerprint(observation),
                    action = action,
                    actionSignature = normalizeAction(action),
                    outcome = Lesson.LessonOutcome.LOOP,
                    toolCallId = null,
                    timestamp = System.currentTimeMillis(),
                )
            )
        }
    }

    /** Mark the episode as completed (DONE / FAILED / STOPPED). */
    fun finish(sessionId: String, finalStatus: String) {
        synchronized(lock) {
            episodes[sessionId]?.let { it.finalStatus = finalStatus }
        }
    }

    /** Get the full episode for reflection. Returns null if no episode was recorded. */
    fun getEpisode(sessionId: String): List<EpisodeStep>? = synchronized(lock) {
        episodes[sessionId]?.steps?.toList()
    }

    /** Get the user prompt that started this episode. */
    fun getUserPrompt(sessionId: String): String? = synchronized(lock) {
        episodes[sessionId]?.userPrompt
    }

    /** Get the final status of the episode. */
    fun getFinalStatus(sessionId: String): String? = synchronized(lock) {
        episodes[sessionId]?.finalStatus
    }

    /** Clear the episode for [sessionId] from memory after reflection is done. */
    fun clear(sessionId: String) {
        synchronized(lock) { episodes.remove(sessionId) }
    }

    /**
     * Compute a coarse fingerprint of a screen observation.
     *
     * We take the observation text, normalize whitespace, extract the first
     * 200 characters, and SHA-256 hash → first 8 hex chars. This gives a
     * stable fingerprint that matches "similar" screens (same app, same
     * top-level layout) without requiring an exact string match.
     *
     * Two screens with the same fingerprint are treated as "the same screen"
     * for lesson-matching purposes. This is deliberately coarse — fine-grained
     * matching (e.g. by individual node IDs) would over-fit and rarely match
     * across sessions.
     */
    fun fingerprint(observation: String): String {
        val normalized = observation
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)
        if (normalized.isEmpty()) return "empty"
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }

    /**
     * Normalize an action string to a signature suitable for cross-session
     * matching. Strips whitespace and lowercases. Examples:
     *   "tap(500, 800)"     -> "tap(500,800)"
     *   "launch(com.app)"   -> "launch(com.app)"
     *   "skill:gallery-qa"  -> "skill:gallery-qa"
     */
    fun normalizeAction(action: String): String =
        action.trim().lowercase().replace(Regex("\\s+"), "")
}
