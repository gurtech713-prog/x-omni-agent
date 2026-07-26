package com.omniclaw.app.vision

import android.util.LruCache
import java.security.MessageDigest

/**
 * LRU cache for VLM responses, keyed by (image-hash + question-hash).
 *
 * Avoids re-querying the VLM for the same screenshot + question pair, which
 * is common in the agent loop when the screen hasn't changed between steps
 * (e.g. the agent is reasoning, not acting).
 *
 * The cache is bounded by [maxSizeBytes] (default 4MB — ~20 entries at
 * 200KB per cached response). Entries are evicted in LRU order when the
 * cache exceeds the bound.
 *
 * Thread safety: [android.util.LruCache] is thread-safe by design (uses
 * `synchronized` internally). Safe to call from any thread.
 */
class VisionCache(
    maxSizeBytes: Int = 4 * 1024 * 1024,
) {
    private val cache = object : LruCache<String, CacheEntry>(maxSizeBytes) {
        override fun sizeOf(key: String, value: CacheEntry): Int =
            value.text.toByteArray().size + 64 // overhead estimate
    }

    data class CacheEntry(
        val text: String,
        val createdAt: Long,
        val imageHash: String,
        val questionHash: String,
    )

    /** Look up a cached response. Returns null if not present or expired. */
    fun get(imageBytes: ByteArray, question: String): String? {
        val key = buildKey(imageBytes, question)
        val entry = cache.get(key) ?: return null
        if (System.currentTimeMillis() - entry.createdAt > TTL_MS) {
            cache.remove(key)
            return null
        }
        return entry.text
    }

    /** Store a response in the cache. */
    fun put(imageBytes: ByteArray, question: String, response: String) {
        val key = buildKey(imageBytes, question)
        val entry = CacheEntry(
            text = response,
            createdAt = System.currentTimeMillis(),
            imageHash = hash(imageBytes),
            questionHash = hash(question.toByteArray()),
        )
        cache.put(key, entry)
    }

    /** Clear the cache (e.g. on memory pressure). */
    fun clear() = cache.evictAll()

    /** Number of cached entries. */
    val size: Int get() = cache.size()

    private fun buildKey(imageBytes: ByteArray, question: String): String =
        hash(imageBytes) + ":" + hash(question.toByteArray())

    private fun hash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    companion object {
        /** Cache entries expire after 5 minutes — screens change frequently. */
        private const val TTL_MS = 5 * 60 * 1000L
    }
}
