package com.omniclaw.app.data.local

import android.content.Context
import android.util.Log
import com.omniclaw.app.litert.InferenceScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
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
     */
    suspend fun inputShape(modelPath: String, index: Int = 0): IntArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val loaded = getOrLoad(modelPath, useGpu = false, useNnapi = false)
                loaded.interpreter.getInputTensor(index).shape()
            }.getOrNull()
        }

    /**
     * Get the output tensor shape (as int array) for a model.
     */
    suspend fun outputShape(modelPath: String, index: Int = 0): IntArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val loaded = getOrLoad(modelPath, useGpu = false, useNnapi = false)
                loaded.interpreter.getOutputTensor(index).shape()
            }.getOrNull()
        }

    /** Release all cached variants of a model. */
    suspend fun unload(modelPath: String) {
        val pathPrefix = modelPath.removePrefix("assets://").lowercase()
        val removed = cacheMutex.withLock {
            val toRemove = cache.entries.filter { it.key.startsWith("$pathPrefix|") }.map { it.key }
            toRemove.mapNotNull { cache.remove(it) }
        }
        removed.forEach {
            runCatching { it.interpreter.close() }
            it.gpuDelegate?.close()
            it.nnApiDelegate?.close()
        }
    }

    /** Release all cached models. */
    suspend fun unloadAll() {
        val all = cacheMutex.withLock {
            val copy = cache.values.toList()
            cache.clear()
            copy
        }
        all.forEach {
            runCatching { it.interpreter.close() }
            it.gpuDelegate?.close()
            it.nnApiDelegate?.close()
        }
        runCatching { scheduler.shutdown() }
    }

    /** True if LiteRT native libs loaded successfully. */
    val isAvailable: Boolean by lazy {
        runCatching {
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

    private val cache = mutableMapOf<String, LoadedModel>()
    private val cacheMutex = Mutex()

    private suspend fun getOrLoad(modelPath: String, useGpu: Boolean, useNnapi: Boolean): LoadedModel {
        val key = normalizeKey(modelPath, useGpu, useNnapi)
        cacheMutex.withLock { cache[key]?.let { return it } }
        val file = resolveToFile(modelPath)
        val options = Interpreter.Options()
        var gpuDelegate: GpuDelegate? = null
        var nnApiDelegate: NnApiDelegate? = null
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
        return cacheMutex.withLock {
            cache[key]?.let { duplicate ->
                runCatching { interpreter.close() }
                gpuDelegate?.close()
                nnApiDelegate?.close()
                return@withLock duplicate
            }
            cache[key] = loaded
            loaded
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
        val outFile = File(outDir, assetPath.replace('/', '_'))
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
