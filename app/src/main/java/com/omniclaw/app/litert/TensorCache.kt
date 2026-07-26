package com.omniclaw.app.litert

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches pre-allocated input/output tensors for LiteRT models.
 *
 * [Interpreter.run] requires the caller to pass pre-sized arrays matching
 * the model's tensor shapes. Allocating a new array per inference is
 * wasteful (the GC churn is significant at high inference rates). This
 * cache holds reusable arrays keyed by (modelPath, tensorRole, size).
 *
 * Thread safety: the internal map is concurrent. The arrays themselves are
 * NOT thread-safe — callers must ensure exclusive access during inference
 * (the [InterpreterPool] / [LiteRtEngine] mutex handles this).
 */
@Singleton
class TensorCache @Inject constructor() {

    private data class TensorKey(
        val modelPath: String,
        val role: Role,
        val size: Int,
    ) {
        enum class Role { INPUT_FLOAT, INPUT_INT, OUTPUT_FLOAT }
    }

    private val cache = ConcurrentHashMap<TensorKey, Any>()

    /**
     * Get or create a cached FloatArray of [size] for (modelPath, INPUT_FLOAT).
     * The returned array may contain stale data from a previous inference —
     * callers must overwrite it before use.
     */
    fun inputFloat(modelPath: String, size: Int): FloatArray {
        val key = TensorKey(modelPath, TensorKey.Role.INPUT_FLOAT, size)
        return cache.computeIfAbsent(key) { FloatArray(size) } as FloatArray
    }

    /** Get or create a cached IntArray of [size] for (modelPath, INPUT_INT). */
    fun inputInt(modelPath: String, size: Int): IntArray {
        val key = TensorKey(modelPath, TensorKey.Role.INPUT_INT, size)
        return cache.computeIfAbsent(key) { IntArray(size) } as IntArray
    }

    /** Get or create a cached FloatArray of [size] for (modelPath, OUTPUT_FLOAT). */
    fun outputFloat(modelPath: String, size: Int): FloatArray {
        val key = TensorKey(modelPath, TensorKey.Role.OUTPUT_FLOAT, size)
        return cache.computeIfAbsent(key) { FloatArray(size) } as FloatArray
    }

    /** Clear all cached tensors for a specific model. */
    fun clearForModel(modelPath: String) {
        cache.keys.filter { it.modelPath == modelPath }.forEach { cache.remove(it) }
    }

    /** Clear the entire cache. */
    fun clearAll() = cache.clear()

    /** Total number of cached tensors. */
    val size: Int get() = cache.size

    companion object {
        private const val TAG = "TensorCache"
    }
}
