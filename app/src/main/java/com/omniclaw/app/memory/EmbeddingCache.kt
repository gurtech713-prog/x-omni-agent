package com.omniclaw.app.memory

import android.util.LruCache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cache for embedding vectors, keyed by document ID.
 *
 * Avoids re-computing embeddings for unchanged documents. Backed by an
 * [LruCache] bounded to ~1 MB (maxSize = 1024 KB) so it evicts the
 * least-recently-used entries instead of growing without bound as memories
 * accumulate.
 */
@Singleton
class EmbeddingCache @Inject constructor() {

    private val cache = object : LruCache<String, FloatArray>(1024) {
        override fun sizeOf(key: String, value: FloatArray): Int {
            // Measure entries in KB so maxSize = 1024 ≈ 1 MB. Each embedding
            // is 256 floats × 4 bytes = 1 KB; coerce to ≥1 so sizeOf is never
            // zero (LruCache requires positive entry sizes).
            return (value.size * 4 / 1024).coerceAtLeast(1)
        }
    }

    fun put(docId: String, embedding: FloatArray) {
        cache.put(docId, embedding)
    }

    fun get(docId: String): FloatArray? = cache.get(docId)

    fun remove(docId: String) { cache.remove(docId) }

    fun clear() = cache.evictAll()

    /** Remove entries not in [keepIds] (e.g. stale documents). */
    fun prune(keepIds: Set<String>) {
        val toRemove = cache.snapshot().keys.filter { it !in keepIds }
        toRemove.forEach { cache.remove(it) }
    }

    val size: Int get() = cache.size()
}
