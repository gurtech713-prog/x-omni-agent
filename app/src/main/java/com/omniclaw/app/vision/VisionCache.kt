package com.omniclaw.app.vision

import android.util.LruCache
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    // V-L9: CacheEntry carries its precomputed byte size so sizeOf() doesn't
    // re-call toByteArray() on every LruCache insertion/eviction (which can
    // happen many times per second during a busy vision loop).
    data class CacheEntry(
        val text: String,
        val sizeBytes: Int,
        val createdAt: Long,
        val imageHash: String,
        val questionHash: String,
    )

    private val cache = object : LruCache<String, CacheEntry>(maxSizeBytes) {
        override fun sizeOf(key: String, value: CacheEntry): Int =
            value.sizeBytes + 64 // overhead estimate
    }

    /** Scheduled executor for periodic expired-entry sweeps. */
    private val sweepExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "VisionCacheSweep").apply { isDaemon = true }
    }

    init {
        // Sweep expired entries every 60 seconds to prevent stale entries
        // from accumulating when they are never looked up again.
        sweepExecutor.scheduleAtFixedRate({ sweepExpired() }, 60, 60, TimeUnit.SECONDS)
    }

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
            sizeBytes = response.toByteArray().size,
            createdAt = System.currentTimeMillis(),
            imageHash = hashSampled(imageBytes),
            questionHash = hash(question.toByteArray()),
        )
        cache.put(key, entry)
    }

    /**
     * V-M1: shut down the periodic sweep executor. Call from the owning
     * pipeline's teardown (e.g. VisionPipeline.shutdown()) so the daemon
     * thread doesn't outlive its cache. Safe to call multiple times.
     */
    fun close() {
        sweepExecutor.shutdownNow()
    }

    /** Clear the cache (e.g. on memory pressure). */
    fun clear() = cache.evictAll()

    /** Number of cached entries. */
    val size: Int get() = cache.size()

    /** Remove all expired entries from the cache. */
    fun sweepExpired() {
        val now = System.currentTimeMillis()
        cache.snapshot().keys.filter { key ->
            val entry = cache.get(key)
            entry != null && now - entry.createdAt > TTL_MS
        }.forEach { key ->
            cache.remove(key)
        }
    }

    private fun buildKey(imageBytes: ByteArray, question: String): String =
        hashSampled(imageBytes) + ":" + hash(question.toByteArray())

    /**
     * Hash a sampled prefix of the image (first 4 KB + last 4 KB) instead of
     * the full multi-MB payload. This reduces SHA-256 cost from ~5-15 ms to
     * <1 ms while still providing a unique-enough key for cache lookups.
     */
    private fun hashSampled(data: ByteArray): String {
        val sampleSize = 4096
        val sampled = if (data.size <= sampleSize * 2) {
            data
        } else {
            data.copyOfRange(0, sampleSize) + data.copyOfRange(data.size - sampleSize, data.size)
        }
        return hash(sampled)
    }

    private fun hash(data: ByteArray): String {
        // V-M2: emit the full 64-char hex digest instead of truncating to 16
        // chars (64 bits). A 64-bit cache key collides meaningfully once the
        // index grows past ~4B entries (birthday paradox); with full SHA-256
        // the collision probability is negligible. The hash is also used as
        // the LruCache KEY, so a collision returns the wrong cached response.
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Cache entries expire after 5 minutes — screens change frequently. */
        private const val TTL_MS = 5 * 60 * 1000L
    }
}
