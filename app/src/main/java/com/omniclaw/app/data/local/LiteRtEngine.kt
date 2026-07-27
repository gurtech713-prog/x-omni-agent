package com.omniclaw.app.data.local

import android.content.Context
import android.util.Log
import com.omniclaw.app.litert.InferenceScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT (formerly TensorFlow Lite) on-device inference engine.
 *
 * This class is a **facade** over the [InferenceScheduler] production pipeline.
 * The single-input float/int paths ([runFloatSingle] / [runIntSingle]) delegate
 * to the scheduler; the multi-input [run] + [inputShape] / [outputShape] +
 * [unload] / [unloadAll] paths use a small in-house cache (they exercise APIs
 * the scheduler doesn't yet expose). Both paths share the [DelegateManager]
 * fallback policy (GPU → NNAPI → CPU) via the scheduler.
 *
 * Uses the stable `org.tensorflow.lite.Interpreter` API (LiteRT 1.x).
 */
@Singleton
class LiteRtEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val scheduler: InferenceScheduler,
) {

    /**
     * Run inference on a model with a single float[] input → single float[] output.
     * Delegates to [InferenceScheduler.runFloatSingle].
     */
    suspend fun runFloatSingle(
        modelPath: String,
        input: FloatArray,
        outputSize: Int,
        useGpu: Boolean = false,
        useNnapi: Boolean = true,
    ): FloatArray = scheduler.runFloatSingle(modelPath, input, outputSize, useGpu, useNnapi)

    /**
     * Run inference on a model with a single int[] input → single float[] output.
     * Delegates to [InferenceScheduler.runIntSingle].
     */
    suspend fun runIntSingle(
        modelPath: String,
        input: IntArray,
        outputSize: Int,
        useGpu: Boolean = false,
        useNnapi: Boolean = true,
    ): FloatArray = scheduler.runIntSingle(modelPath, input, outputSize, useGpu, useNnapi)

    /**
     * Run inference with arbitrary input/output. Uses the local interpreter
     * cache (the scheduler doesn't yet expose a multi-input API).
     *
     * Throws [LiteRtException] if any input index is missing — this is a
     * caller-programmer error, not a user-triggerable condition.
     */
    suspend fun run(
        modelPath: String,
        inputs: Map<Int, Any>,
        outputs: Map<Int, Any>,
        useGpu: Boolean = false,
        useNnapi: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        val loaded = getOrLoad(modelPath, useGpu, useNnapi)
        loaded.mutex.withLock {
            val sortedKeys = inputs.keys.sorted()
            val inputsArray = Array(sortedKeys.size) { idx ->
                inputs[sortedKeys[idx]]
                    ?: throw LiteRtException("Missing input tensor at index ${sortedKeys[idx]} for model $modelPath")
            }
            loaded.interpreter.runForMultipleInputsOutputs(inputsArray, outputs)
        }
    }

    /**
     * Get the input tensor shape (as int array) for a model.
     *
     * D-C1: acquire the per-model [LoadedModel.mutex] while reading the tensor
     * shape so a concurrent [unload] can't close the interpreter mid-call
     * (which would otherwise throw IllegalStateException / use-after-free in
     * native code).
     */
    suspend fun inputShape(modelPath: String, index: Int = 0): IntArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val loaded = getOrLoad(modelPath, useGpu = false, useNnapi = false)
                loaded.mutex.withLock { loaded.interpreter.getInputTensor(index).shape() }
            }.getOrNull()
        }

    /**
     * Get the output tensor shape (as int array) for a model.
     *
     * D-C1: acquire the per-model [LoadedModel.mutex] while reading the tensor
     * shape so a concurrent [unload] can't close the interpreter mid-call.
     *
     * D-H3: the shape probe historically loaded a second (non-NNAPI) interpreter
     * per model and left it cached — wasting RAM. We now (a) memoize the result
     * in [shapeCache] so the probe only runs once per (model,index), and
     * (b) call [unload] immediately after the probe to release the probe
     * interpreter back to the OS.
     */
    suspend fun outputShape(modelPath: String, index: Int = 0): IntArray? {
        val cacheKey = "$modelPath#$index"
        shapeCache[cacheKey]?.let { return it }
        val shape = withContext(Dispatchers.IO) {
            runCatching {
                val loaded = getOrLoad(modelPath, useGpu = false, useNnapi = false)
                loaded.mutex.withLock { loaded.interpreter.getOutputTensor(index).shape() }
            }.getOrNull()
        }
        // Unload the probe interpreter (and any sibling variants) to free memory
        // — the probe loads a CPU-only interpreter that the caller usually does
        // not need (the real inference path goes through runIntSingle / scheduler).
        runCatching { unload(modelPath) }
        shapeCache[cacheKey] = shape
        return shape
    }

    /** Release all cached variants of a model. */
    suspend fun unload(modelPath: String) {
        val pathPrefix = modelPath.removePrefix("assets://").lowercase()
        // Remove all variants (gpu=*, nnapi=*) for this model path. ConcurrentHashMap
        // entries are CompletableDeferred<LoadedModel>; we await each so we can
        // close the underlying interpreter + delegates safely. A still-pending
        // load (rare — only if unload races with a getOrLoad that just won the
        // putIfAbsent) will throw on await, which runCatching swallows.
        val toRemove = cache.keys.filter { it.startsWith("$pathPrefix|") }
        val removed = toRemove.mapNotNull { cache.remove(it) }
        removed.forEach { deferred ->
            runCatching {
                val loaded = deferred.await()
                // Drain any in-flight inference before closing (H-24): the Interpreter
                // is not thread-safe, so closing while another coroutine holds the
                // per-model mutex inside run() would be a native use-after-free.
                loaded.mutex.withLock {
                    runCatching { loaded.interpreter.close() }
                    loaded.gpuDelegate?.close()
                    loaded.nnApiDelegate?.close()
                }
            }
        }
    }

    /**
     * Release all cached models.
     *
     * D-L6: do NOT call `scheduler.shutdown()` here — the scheduler is shared
     * with the rest of the app and shutting it down would break subsequent
     * inference calls. Only the local cache is released.
     */
    suspend fun unloadAll() {
        val all = synchronized(evictionLock) {
            val copy = cache.values.toList()
            cache.clear()
            copy
        }
        all.forEach { deferred ->
            // Drain any in-flight load + in-flight inference before closing
            // (H-24) — see unload(). A deferred that hasn't completed yet will
            // throw once we completeExceptionally below, so guard with runCatching.
            runCatching {
                val loaded = deferred.await()
                loaded.mutex.withLock {
                    runCatching { loaded.interpreter.close() }
                    loaded.gpuDelegate?.close()
                    loaded.nnApiDelegate?.close()
                }
            }
        }
        shapeCache.clear()
    }

    /** True if LiteRT native libs loaded successfully. */
    val isAvailable: Boolean by lazy {
        runCatching {
            // Force the native library to load eagerly (M-50). Class-load success
            // alone does NOT imply libtensorflowlite_jni.so loaded; an ABI mismatch
            // would otherwise surface later as a cryptic UnsatisfiedLinkError /
            // native crash at first inference. Probing System.loadLibrary here makes
            // that failure show up as a clean `false` from isAvailable.
            System.loadLibrary("tensorflowlite_jni")
            Interpreter::class.java
            true
        }.getOrElse {
            Log.w(TAG, "LiteRT not available: ${it.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // Internal — retained for the multi-input run() + inputShape()/outputShape()
    // paths which the scheduler doesn't yet cover. Will be migrated in a
    // future iteration.
    // ------------------------------------------------------------------

    private class LoadedModel(
        val modelFile: File,
        val interpreter: Interpreter,
        val gpuDelegate: GpuDelegate?,
        val nnApiDelegate: NnApiDelegate?,
    ) {
        val mutex = Mutex()
    }

    /**
     * Per-key load coordination. Each entry is a [CompletableDeferred] that is
     * completed by the coroutine that wins the `putIfAbsent` race for that key;
     * concurrent loaders of the SAME key `await()` the winner instead of
     * double-constructing an Interpreter. Concurrent loaders of DIFFERENT keys
     * no longer block each other (the previous `cacheMutex.withLock` held the
     * lock across multi-second Interpreter construction, serializing all
     * loads — D-H4).
     */
    private val cache = ConcurrentHashMap<String, CompletableDeferred<LoadedModel>>()

    /**
     * Caps the number of cached interpreter variants (D-H4). Each variant can
     * hold hundreds of MB of native memory; an unbounded cache would OOM on a
     * device that loads multiple model families. Eviction is FIFO-ish (we drop
     * an arbitrary key via `cache.keys.first()`) — true LRU would require a
     * LinkedHashMap wrapper, which is overkill for a cap of 2.
     */
    private val maxEntries = 2
    private val evictionLock = Any()

    /**
     * Cached output-shape probe results (D-H3). Keyed by `"$modelPath#$index"`.
     */
    private val shapeCache = ConcurrentHashMap<String, IntArray?>()

    private suspend fun getOrLoad(modelPath: String, useGpu: Boolean, useNnapi: Boolean): LoadedModel {
        val key = normalizeKey(modelPath, useGpu, useNnapi)
        // Fast path: already loaded.
        cache[key]?.await()?.let { return it }
        // Slow path: try to claim the loader slot for this key.
        val deferred = CompletableDeferred<LoadedModel>()
        val winner = cache.putIfAbsent(key, deferred) ?: deferred
        if (winner !== deferred) return winner.await()
        try {
            // Bounded cache (D-H4): evict the oldest variant if we'd exceed the cap.
            // We collect the deferreds to evict INSIDE the synchronized block (which
            // only touches the ConcurrentHashMap — no suspend calls), then close them
            // OUTSIDE the lock. Closing requires `await()` + `mutex.withLock`, both
            // of which are suspend calls — calling them inside `synchronized` would
            // hold the JVM monitor across suspension and throw
            // IllegalMonitorStateException when the coroutine resumes on a different
            // thread (the monitor is per-thread, not per-coroutine).
            val toEvict: List<CompletableDeferred<LoadedModel>> = synchronized(evictionLock) {
                val evicted = mutableListOf<CompletableDeferred<LoadedModel>>()
                while (cache.size > maxEntries) {
                    // `cache.keys` is ConcurrentHashMap's KeySetView (Iterable<String>).
                    // We pick an arbitrary key — true LRU would require a LinkedHashMap,
                    // but for a cap of 2 any eviction policy is sufficient to prevent
                    // unbounded growth.
                    val oldestKey = cache.keys.first()
                    cache.remove(oldestKey)?.let { evicted.add(it) }
                }
                evicted
            }
            for (evictedDeferred in toEvict) {
                runCatching {
                    val evicted = evictedDeferred.await()
                    evicted.mutex.withLock {
                        runCatching { evicted.interpreter.close() }
                        evicted.gpuDelegate?.close()
                        evicted.nnApiDelegate?.close()
                    }
                }
            }
            val file = resolveToFile(modelPath)
            val options = Interpreter.Options()
            // D-C5: wrap delegate construction + Interpreter creation in try/catch
            // so a failure (e.g. GPU delegate OOM, NNAPI unsupported on this ABI)
            // closes the delegates we already created. Without this, every failed
            // construction leaked one or both delegates' native memory.
            var gpuDelegate: GpuDelegate? = null
            var nnApiDelegate: NnApiDelegate? = null
            try {
                if (useNnapi) {
                    runCatching {
                        nnApiDelegate = NnApiDelegate()
                        options.addDelegate(nnApiDelegate)
                    }.onFailure { Log.w(TAG, "NNAPI delegate unavailable: ${it.message}") }
                }
                if (useGpu) {
                    runCatching {
                        gpuDelegate = GpuDelegate()
                        options.addDelegate(gpuDelegate)
                    }.onFailure { Log.w(TAG, "GPU delegate unavailable: ${it.message}") }
                }
                options.setNumThreads(2)
                val interpreter = Interpreter(file, options)
                val loaded = LoadedModel(file, interpreter, gpuDelegate, nnApiDelegate)
                deferred.complete(loaded)
                return loaded
            } catch (t: Throwable) {
                runCatching { gpuDelegate?.close() }
                runCatching { nnApiDelegate?.close() }
                throw t
            }
        } catch (t: Throwable) {
            cache.remove(key)
            deferred.completeExceptionally(t)
            throw t
        }
    }

    private fun normalizeKey(modelPath: String, useGpu: Boolean = false, useNnapi: Boolean = true): String {
        val path = modelPath.removePrefix("assets://").lowercase()
        return "$path|gpu=$useGpu|nnapi=$useNnapi"
    }

    private fun resolveToFile(modelPath: String): File {
        val absolute = File(modelPath)
        if (absolute.isAbsolute && absolute.exists()) return absolute

        val assetPath = when {
            modelPath.startsWith("assets://") -> modelPath.removePrefix("assets://")
            modelPath.startsWith("models/") -> modelPath
            modelPath.contains('/') -> modelPath
            else -> "models/$modelPath"
        }
        val outDir = File(ctx.filesDir, "litert_models").apply { mkdirs() }
        // Preserve the asset's subdirectory structure (M-27): flattening '/' to '_'
        // made distinct assets like "models/a/m.tflite" and "models/a_m.tflite"
        // collide on the same extracted file.
        //
        // D-M4: canonicalize the resolved file path and verify it stays inside
        // outDir. Without this, a modelPath containing ".." (e.g.
        // "models/../../databases/omniclaw.db") could traverse out of the
        // litert_models directory and overwrite arbitrary app files.
        val outFile = File(outDir, assetPath).canonicalFile
        val canonicalOutDir = outDir.canonicalFile
        require(outFile.path.startsWith(canonicalOutDir.path + File.separator)) {
            "Refusing to resolve model path outside litert_models: $modelPath"
        }
        outFile.parentFile?.mkdirs()
        // Validate the extracted file is non-empty AND its size matches the
        // asset's size — a partial extract (e.g. crash during copy, full disk)
        // would otherwise produce a corrupt interpreter.
        val assetSize = runCatching { ctx.assets.openFd(assetPath).length }.getOrDefault(-1L)
        if (outFile.exists() && outFile.length() > 0 && (assetSize < 0 || outFile.length() == assetSize)) {
            return outFile
        }
        if (outFile.exists()) {
            // Stale / partial — delete and re-extract.
            runCatching { outFile.delete() }
        }

        ctx.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        if (outFile.length() == 0L) {
            throw LiteRtException("Asset '$assetPath' extracted as empty file — model not found in APK")
        }
        if (assetSize > 0 && outFile.length() != assetSize) {
            runCatching { outFile.delete() }
            throw LiteRtException("Asset '$assetPath' extract size mismatch (asset=$assetSize, extracted=${outFile.length()}) — possibly corrupt APK")
        }
        Log.i(TAG, "Extracted LiteRT model '$assetPath' -> ${outFile.absolutePath} (${outFile.length()} bytes)")
        return outFile
    }

    companion object {
        private const val TAG = "LiteRtEngine"
    }
}

class LiteRtException(message: String) : RuntimeException(message)
