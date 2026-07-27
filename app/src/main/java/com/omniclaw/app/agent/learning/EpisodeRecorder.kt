package com.omniclaw.app.agent.learning

import com.omniclaw.app.data.model.Lesson
import com.omniclaw.app.data.model.ToolCall
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
 * Design: thread-safe via synchronized LinkedHashMap. The recorder is held by the
 * singleton AgentLoop scope, so multiple sessions can record concurrently
 * without corrupting each other's episodes.
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

    /**
     * Hard cap on the number of concurrent in-memory episodes. Reached only
     * if many sessions are abandoned (user kills the app mid-session) without
     * [clear] being called. When exceeded, the oldest episode (by insertion
     * order) is evicted to bound memory use.
     */
    private val maxEpisodes = 50

    // LinkedHashMap with removeEldestEntry for insertion-order eviction,
    // wrapped in synchronized blocks for thread safety.
    private val episodes = object : LinkedHashMap<String, Episode>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Episode>?): Boolean {
            return size > maxEpisodes
        }
    }

    /** Start recording a new episode for [sessionId]. */
    fun start(sessionId: String, userPrompt: String) {
        synchronized(episodes) {
            // putIfAbsent ensures re-starting the same session doesn't overwrite
            // an existing episode that may already have recorded steps.
            if (!episodes.containsKey(sessionId)) {
                episodes[sessionId] = Episode(sessionId, userPrompt)
            }
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
        val step = EpisodeStep(
            stepIndex = stepIndex,
            observation = observation.take(500),  // truncate for storage
            screenFingerprint = fingerprint,
            action = action,
            actionSignature = actionSig,
            outcome = outcome,
            toolCallId = call.id,
            timestamp = System.currentTimeMillis(),
        )
        // A-M6 FIX: combine the episode-map lookup and the step-list mutation
        // under a SINGLE lock on `episodes`. The previous two-block form
        // (`synchronized(episodes) { episodes[sessionId] }` to read, then
        // `synchronized(ep.steps) { ep.steps.add(step) }` to mutate) had a
        // window between the two locks where another thread could call
        // `clear(sessionId)` — removing the episode from the map while the
        // step list was still reachable via the captured `ep` reference.
        // The step would then be added to a stale episode that the rest of
        // the system believed was cleared, silently resurrecting recorded
        // steps for a session that was supposed to be reset. Holding the
        // `episodes` lock across the add (in addition to the inner
        // `ep.steps` lock, which guards against concurrent readers like
        // getEpisode) closes the window.
        synchronized(episodes) {
            val ep = episodes[sessionId] ?: return
            synchronized(ep.steps) {
                ep.steps.add(step)
            }
        }
    }

    /** Mark a loop-detected step (agent repeated the same action). */
    fun recordLoop(sessionId: String, stepIndex: Int, observation: String, action: String) {
        val ep = synchronized(episodes) { episodes[sessionId] } ?: return
        val step = EpisodeStep(
            stepIndex = stepIndex,
            observation = observation.take(500),
            screenFingerprint = fingerprint(observation),
            action = action,
            actionSignature = normalizeAction(action),
            outcome = Lesson.LessonOutcome.LOOP,
            toolCallId = null,
            timestamp = System.currentTimeMillis(),
        )
        synchronized(ep.steps) {
            ep.steps.add(step)
        }
    }

    /** Mark the episode as completed (DONE / FAILED / STOPPED). */
    fun finish(sessionId: String, finalStatus: String) {
        synchronized(episodes) { episodes[sessionId] }?.let {
            it.finalStatus = finalStatus
        }
    }

    /** Get the full episode for reflection. Returns null if no episode was recorded. */
    fun getEpisode(sessionId: String): List<EpisodeStep>? {
        val ep = synchronized(episodes) { episodes[sessionId] } ?: return null
        synchronized(ep.steps) {
            return ep.steps.toList()
        }
    }

    /** Get the user prompt that started this episode. */
    fun getUserPrompt(sessionId: String): String? =
        synchronized(episodes) { episodes[sessionId]?.userPrompt }

    /** Get the final status of the episode. */
    fun getFinalStatus(sessionId: String): String? =
        synchronized(episodes) { episodes[sessionId]?.finalStatus }

    /** Clear the episode for [sessionId] from memory after reflection is done. */
    fun clear(sessionId: String) {
        synchronized(episodes) { episodes.remove(sessionId) }
    }

    /**
     * Compute a coarse fingerprint of a screen observation.
     *
     * We take the observation text, normalize whitespace, extract the first
     * 200 characters, and compute an sdbm polynomial hash of the normalized
     * first 200 chars → first 8 hex chars. This gives a stable fingerprint
     * that matches "similar" screens (same app, same top-level layout)
     * without requiring an exact string match.
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
        // PERFORMANCE: sdbm polynomial hash (1us) instead of SHA-256 (50us)
        var hash = 0L
        for (c in normalized) {
            hash = (c.code.toLong()) + (hash shl 6) + (hash shl 16) - hash
        }
        return java.lang.Long.toHexString(hash and 0xFFFF_FFFFL).padStart(8, '0').take(8)
    }

    /**
     * Normalize an action string to a signature suitable for cross-session
     * matching. Strips whitespace and lowercases. Examples:
     *   "tap(500, 800)"     -> "tap(500,800)"
     *   "launch(com.app)"   -> "launch(com.app)"
     *   "skill:gallery-qa"  -> "skill:gallery-qa"
     *
     * A-C3 FIX: whitespace is stripped ONLY outside quoted regions. The
     * previous implementation `replace(Regex("\\s+"), "")` collapsed
     * whitespace inside quoted strings too, so `type("hello world")` was
     * normalized to `type("helloworld")` — silently changing the action's
     * semantic content. The state machine below preserves the bytes
     * between unescaped `"` characters verbatim.
     */
    fun normalizeAction(action: String): String {
        val s = action.trim().lowercase()
        val sb = StringBuilder(s.length)
        var inQuotes = false
        for (c in s) {
            when {
                c == '"' -> { inQuotes = !inQuotes; sb.append(c) }
                c.isWhitespace() && !inQuotes -> { /* skip whitespace outside quotes */ }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
