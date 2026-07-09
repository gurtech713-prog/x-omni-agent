package com.omniclaw.app.data.local

import android.content.Context
import android.util.Log
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
 * Powers the local-LLM fallback path: when the user picks a "local-*" model
 * (e.g. a Gemma / TinyLlama .tflite flatbuffer), this engine loads it onto
 * the device and runs inference without any network call.
 *
 * Design:
 *   - One [Interpreter] per loaded model file; cached by file path.
 *   - Inference is serialized per-engine via a Mutex — the LiteRT interpreter
 *     is not thread-safe, and concurrent run() calls corrupt the I/O buffers.
 *   - NNAPI delegate is preferred (Android 8.1+); GPU delegate is opt-in.
 *   - Models in /assets are extracted to filesDir on first load (LiteRT
 *     requires a seekable File — AssetManager fds break mmap on some devices).
 *
 * Uses the stable `org.tensorflow.lite.Interpreter` API (LiteRT 1.x). The
 * newer `com.google.ai.edge.litert.Model` API is 2.x-alpha and not
 * production-ready.
 */
@Singleton
class LiteRtEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    /** A loaded model + its interpreter + optional delegates. */
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

    /**
     * Run inference on a model with a single float[] input → single float[] output.
     *
     * The caller must know the model's input/output tensor shapes and size the
     * arrays accordingly. The output array is allocated by the caller and
     * filled by the interpreter.
     *
     * @param modelPath  Absolute path, "assets://path", or bare filename under "models/"
     * @param input      Pre-sized float array matching the input tensor
     * @param outputSize Expected output array size
     * @param useGpu     Enable GPU delegate (opt-in; some int8 models fail GPU)
     * @param useNnapi   Enable NNAPI delegate (default true; safe cross-vendor)
     */
    suspend fun runFloatSingle(
        modelPath: String,
        input: FloatArray,
        outputSize: Int,
        useGpu: Boolean = false,
        useNnapi: Boolean = true,
    ): FloatArray = withContext(Dispatchers.IO) {
        val loaded = getOrLoad(modelPath, useGpu, useNnapi)
        loaded.mutex.withLock {
            val output = FloatArray(outputSize)
            // Interpreter.run takes (input, output) where both are arrays or
            // multi-dimensional arrays matching the tensor shapes. For a 1-D
            // float input/output, pass the arrays directly.
            loaded.interpreter.run(input, output)
            output
        }
    }

    /**
     * Run inference on a model with a single int[] input → single float[] output.
     *
     * This is the correct entry point for causal-LM token-ID inputs: TFLite LLM
     * models declare their input tensor as int32 (token IDs), NOT float.
     * Passing a FloatArray to an int32 input tensor throws
     * IllegalArgumentException at interpreter.run(). Use this method for any
     * model whose input tensor dtype is int32.
     *
     * @param modelPath  Absolute path, "assets://path", or bare filename under "models/"
     * @param input      Pre-sized int array (token IDs) matching the input tensor
     * @param outputSize Expected output array size (vocab size for an LM head)
     * @param useGpu     Enable GPU delegate (opt-in; some int8 models fail GPU)
     * @param useNnapi   Enable NNAPI delegate (default true; safe cross-vendor)
     */
    suspend fun runIntSingle(
        modelPath: String,
        input: IntArray,
        outputSize: Int,
        useGpu: Boolean = false,
        useNnapi: Boolean = true,
    ): FloatArray = withContext(Dispatchers.IO) {
        val loaded = getOrLoad(modelPath, useGpu, useNnapi)
        loaded.mutex.withLock {
            val output = FloatArray(outputSize)
            loaded.interpreter.run(input, output)
            output
        }
    }

    /**
     * Run inference with arbitrary input/output. The caller provides a map of
     * input-index → array and a map of output-index → array; both are passed
     * directly to the interpreter.
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
            // Sort input keys so sparse maps (e.g. {0, 2}) don't NPE on the
            // missing middle index. Previously Array(inputs.size) indexed by
            // 0..size-1, which NPE'd when a key was missing.
            val sortedKeys = inputs.keys.sorted()
            val inputsArray = Array(sortedKeys.size) { inputs[sortedKeys[it]] ?: error("missing input ${sortedKeys[it]}") }
            loaded.interpreter.runForMultipleInputsOutputs(inputsArray, outputs)
        }
    }

    /**
     * Get the input tensor shape (as int array) for a model. Useful for
     * callers that need to size their input buffers.
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

    /** Release all cached variants of a model (any delegate combination). Safe to call multiple times. */
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

    /** Release all cached models (e.g. on app background / low memory). */
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
    // Internal
    // ------------------------------------------------------------------

    private suspend fun getOrLoad(modelPath: String, useGpu: Boolean, useNnapi: Boolean): LoadedModel {
        // Cache key MUST include useGpu/useNnapi so a model first loaded with
        // NNAPI isn't returned for a later GPU request (wrong delegate). The
        // interpreter is built once with a fixed delegate set; swapping
        // delegates requires rebuilding the interpreter.
        val key = normalizeKey(modelPath, useGpu, useNnapi)
        cacheMutex.withLock { cache[key]?.let { return it } }
        val file = resolveToFile(modelPath)
        val options = Interpreter.Options()
        var gpuDelegate: GpuDelegate? = null
        var nnApiDelegate: NnApiDelegate? = null
        // NNAPI is the safest cross-vendor accelerator.
        if (useNnapi) {
            runCatching {
                nnApiDelegate = NnApiDelegate()
                options.addDelegate(nnApiDelegate)
            }.onFailure { Log.w(TAG, "NNAPI delegate unavailable: ${it.message}") }
        }
        // GPU delegate is opt-in — some quantized (int8) models fail GPU compilation.
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
        if (outFile.exists() && outFile.length() > 0) return outFile

        ctx.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        if (outFile.length() == 0L) {
            throw LiteRtException("Asset '$assetPath' extracted as empty file — model not found in APK")
        }
        Log.i(TAG, "Extracted LiteRT model '$assetPath' -> ${outFile.absolutePath} (${outFile.length()} bytes)")
        return outFile
    }

    companion object {
        private const val TAG = "LiteRtEngine"
    }
}

class LiteRtException(message: String) : RuntimeException(message)
