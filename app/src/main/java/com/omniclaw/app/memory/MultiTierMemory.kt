package com.omniclaw.app.memory

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-tier memory system for the agent.
 *
 * Mirrors the cognitive-science model of human memory:
 *
 *   1. **Short-term** — the current session's transient state (last N
 *      observations + actions). Cleared on session end.
 *   2. **Working** — facts extracted from the current conversation that
 *      are useful for the immediate task but not worth persisting.
 *   3. **Conversation** — the full message history of active sessions,
 *      for context window injection.
 *   4. **Task** — per-task scratch space (e.g. "user asked for a summary
 *      of today's photos" — the task memory holds the photo list).
 *   5. **Long-term** — durable, cross-session facts (user preferences,
 *      recurring patterns). Persisted to Room via [MemoryRepository].
 *   6. **Reflection** — lessons learned by the [LearningEngine] after
 *      each session. Persisted to Room via [LessonDao].
 *   7. **Knowledge** — distilled, high-confidence facts promoted from
 *      long-term memory after multiple reinforcements.
 *
 * Tiers 1-4 are in-memory (ephemeral); tiers 5-7 are persisted.
 *
 * This class manages the in-memory tiers. The persisted tiers are owned
 * by [com.omniclaw.app.data.memory.MemoryRepository] (long-term + working)
 * and [com.omniclaw.app.data.local.LessonDao] (reflection). The agent
 * loop queries all tiers via [retrieveRelevant] which merges results.
 */
@Singleton
class MultiTierMemory @Inject constructor() {

    /** A single memory entry, tagged with its tier. */
    data class Memory(
        val id: String,
        val tier: Tier,
        val content: String,
        val createdAt: Long,
        val confidence: Float = 1.0f,
        val lastAccessedAt: Long = createdAt,
        val accessCount: Int = 0,
        val tags: Set<String> = emptySet(),
    ) {
        enum class Tier { SHORT_TERM, WORKING, CONVERSATION, TASK, LONG_TERM, REFLECTION, KNOWLEDGE }
    }

    // In-memory tiers (SHORT_TERM, WORKING, TASK). CONVERSATION is held by
    // SessionRepository. LONG_TERM / REFLECTION / KNOWLEDGE are in Room.
    private val shortTerm = ConcurrentHashMap<String, MutableList<Memory>>()
    private val working = ConcurrentHashMap<String, MutableList<Memory>>()
    private val task = ConcurrentHashMap<String, MutableList<Memory>>()

    /** Add a short-term memory for [sessionId]. */
    fun addShortTerm(sessionId: String, content: String, tags: Set<String> = emptySet()) {
        val m = Memory(
            id = java.util.UUID.randomUUID().toString().take(8),
            tier = Memory.Tier.SHORT_TERM,
            content = content,
            createdAt = System.currentTimeMillis(),
            tags = tags,
        )
        // computeIfAbsent is atomic, but the returned MutableList is not thread-safe;
        // guard the mutation so concurrent addShortTerm calls for the same session
        // can't race on the same list (corruption / ConcurrentModificationException).
        shortTerm.computeIfAbsent(sessionId) { mutableListOf() }.let { list ->
            synchronized(list) {
                list.add(m)
                // Cap at 50 entries — short-term memory is transient.
                while (list.size > 50) list.removeAt(0)
            }
        }
    }

    /** Add a working memory for [sessionId]. */
    fun addWorking(sessionId: String, content: String, tags: Set<String> = emptySet()) {
        val m = Memory(
            id = java.util.UUID.randomUUID().toString().take(8),
            tier = Memory.Tier.WORKING,
            content = content,
            createdAt = System.currentTimeMillis(),
            tags = tags,
        )
        working.computeIfAbsent(sessionId) { mutableListOf() }.let { list ->
            synchronized(list) {
                list.add(m)
                while (list.size > 30) list.removeAt(0)
            }
        }
    }

    /** Add a task memory for [taskId]. */
    fun addTask(taskId: String, content: String, tags: Set<String> = emptySet()) {
        val m = Memory(
            id = java.util.UUID.randomUUID().toString().take(8),
            tier = Memory.Tier.TASK,
            content = content,
            createdAt = System.currentTimeMillis(),
            tags = tags,
        )
        task.computeIfAbsent(taskId) { mutableListOf() }.let { list ->
            synchronized(list) {
                list.add(m)
                while (list.size > 100) list.removeAt(0)
            }
        }
    }

    /** Retrieve all in-memory entries for [sessionId] (short-term + working). */
    fun retrieveForSession(sessionId: String): List<Memory> {
        val st = shortTerm[sessionId]?.let { synchronized(it) { it.toList() } } ?: emptyList()
        val wk = working[sessionId]?.let { synchronized(it) { it.toList() } } ?: emptyList()
        return st + wk
    }

    /** Retrieve task memory for [taskId]. */
    fun retrieveTask(taskId: String): List<Memory> =
        task[taskId]?.let { synchronized(it) { it.toList() } } ?: emptyList()

    /**
     * Retrieve memories relevant to [query] — simple tag + substring match.
     *
     * For production semantic retrieval, see [SemanticSearchEngine].
     */
    fun retrieveRelevant(sessionId: String, query: String, limit: Int = 10): List<Memory> {
        val all = retrieveForSession(sessionId)
        val q = query.lowercase()
        return all
            .map { m -> m to scoreRelevance(m, q) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** Score relevance of [m] to [query] (lowercase). Higher = more relevant. */
    private fun scoreRelevance(m: Memory, query: String): Float {
        val content = m.content.lowercase()
        var score = 0f
        var matched = false
        // Tag match: high signal.
        if (m.tags.any { query.contains(it.lowercase()) }) {
            score += 2f
            matched = true
        }
        // Content substring: medium signal.
        if (content.contains(query)) {
            score += 1f
            matched = true
        }
        if (!matched) return 0f
        // Recency boost: newer memories score higher.
        val ageMs = System.currentTimeMillis() - m.createdAt
        val recencyBoost = (1f - (ageMs.toFloat() / (60 * 60 * 1000))).coerceIn(0f, 1f) * 0.5f
        score += recencyBoost
        return score
    }

    /**
     * Clear all in-memory tiers for [sessionId] (on session end).
     *
     * V-M9: also clears the TASK tier keyed by the same id. Many callers
     * use the session id as the task id when running a single task per
     * session; previously those task entries leaked across sessions.
     */
    fun clearSession(sessionId: String) {
        shortTerm.remove(sessionId)
        working.remove(sessionId)
        task.remove(sessionId)
    }

    /** Clear task memory for [taskId]. */
    fun clearTask(taskId: String) {
        task.remove(taskId)
    }

    /** Clear all in-memory tiers (on app shutdown / low memory). */
    fun clearAll() {
        shortTerm.clear()
        working.clear()
        task.clear()
    }

    /** Snapshot counts per tier (for diagnostics). */
    fun tierCounts(): Map<Memory.Tier, Int> = mapOf(
        Memory.Tier.SHORT_TERM to shortTerm.values.sumOf { synchronized(it) { it.size } },
        Memory.Tier.WORKING to working.values.sumOf { synchronized(it) { it.size } },
        Memory.Tier.TASK to task.values.sumOf { synchronized(it) { it.size } },
    )
}
