package com.omniclaw.app.memory

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cache for embedding vectors, keyed by document ID.
 *
 * Avoids re-computing embeddings for unchanged documents. Backed by a
 * ConcurrentHashMap — no eviction policy (callers should call [prune]
 * periodically to bound memory). For production, swap to an LruCache.
 */
@Singleton
class EmbeddingCache @Inject constructor() {

    private val cache = ConcurrentHashMap<String, FloatArray>()

    fun put(docId: String, embedding: FloatArray) {
        cache[docId] = embedding
    }

    fun get(docId: String): FloatArray? = cache[docId]

    fun remove(docId: String) { cache.remove(docId) }

    fun clear() = cache.clear()

    /** Remove entries not in [keepIds] (e.g. stale documents). */
    fun prune(keepIds: Set<String>) {
        val toRemove = cache.keys.filter { it !in keepIds }
        toRemove.forEach { cache.remove(it) }
    }

    val size: Int get() = cache.size
}
