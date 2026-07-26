package com.omniclaw.app.litert

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules LiteRT inferences with profiling, OOM recovery, and warm-up.
 *
 * This is the production replacement for the ad-hoc inference methods on
 * [LiteRtEngine]. It delegates to:
 *   - [ModelManager] for model file resolution.
 *   - [DelegateManager] for delegate lifecycle.
 *   - [InterpreterPool] for limited concurrent inference.
 *   - [TensorCache] for reusable I/O buffers.
 *
 * Profiling: every inference is timed and recorded. The [stats] flow
 * exposes per-model latency percentiles for the diagnostics UI.
 *
 * OOM recovery: if an interpreter construction fails with OutOfMemoryError,
 * the scheduler evicts all cached interpreters + tensors and retries once.
 * If the retry also fails, it throws [LiteRtOomException].
 *
 * Warm-up: [warmUp] runs a dummy inference (all-zeros input) on a freshly
 * loaded model to force delegate compilation + JIT. Subsequent real
 * inferences are then fast. Warm-up runs on a background coroutine so the
 * caller doesn't block.
 */
@Singleton
class InferenceScheduler @Inject constructor(
    private val modelManager: ModelManager,
    private val delegateManager: DelegateManager,
    private val interpreterPool: InterpreterPool,
    private val tensorCache: TensorCache,
) {

    data class ModelStats(
        val modelPath: String,
        val inferenceCount: Long,
        val totalLatencyMs: Long,
        val maxLatencyMs: Long,
        val failureCount: Long,
    )

    private val _stats = MutableStateFlow<Map<String, ModelStats>>(emptyMap())
    val stats: StateFlow<Map<String, ModelStats>> = _stats.asStateFlow()

    private val statsLock = Mutex()
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Run a single-input-float inference with full lifecycle management.
     *
     * @param modelPath  Absolute path, "assets://path", or bare filename.
     * @param input      Pre-sized FloatArray matching the input tensor.
     * @param outputSize Expected output array size.
     * @param useGpu     Enable GPU delegate.
     * @param useNnapi   Enable NNAPI delegate (default true).
     */
    suspend fun runFloatSingle(
        modelPath: String,
        input: FloatArray,
        outputSize: Int,
        useGpu: Boolean = false,
        useNnapi: Boolean = true,
    ): FloatArray = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val result = runWithOomRecovery(modelPath, useGpu, useNnapi) { interp ->
                val output = tensorCache.outputFloat(modelPath, outputSize)
                interp.run(input, output)
                output
            }
            recordSuccess(modelPath, System.currentTimeMillis() - start)
            result
        } catch (e: Exception) {
            recordFailure(modelPath)
            throw e
        }
    }

    /**
     * Run a single-input-int inference (the correct path for causal-LM
     * token-ID inputs, which are int32 not float).
     */
    suspend fun runIntSingle(
        modelPath: String,
        input: IntArray,
        outputSize: Int,
        useGpu: Boolean = false,
        useNnapi: Boolean = true,
    ): FloatArray = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val result = runWithOomRecovery(modelPath, useGpu, useNnapi) { interp ->
                val output = tensorCache.outputFloat(modelPath, outputSize)
                interp.run(input, output)
                output
            }
            recordSuccess(modelPath, System.currentTimeMillis() - start)
            result
        } catch (e: Exception) {
            recordFailure(modelPath)
            throw e
        }
    }

    /**
     * Run an inference with a warm-up pass first. The warm-up runs a dummy
     * all-zeros input through the model to trigger delegate compilation.
     * Use this for latency-sensitive paths (e.g. real-time agent loop).
     */
    fun warmUp(modelPath: String, inputSize: Int, outputSize: Int) {
        warmupScope.launch {
            runCatching {
                runIntSingle(modelPath, IntArray(inputSize), outputSize)
            }.onFailure { Log.w(TAG, "warm-up failed for $modelPath: ${it.message}") }
        }
    }

    /**
     * Run an inference with OOM recovery: if the interpreter construction
     * throws OutOfMemoryError, evict all cached interpreters + tensors and
     * retry once on CPU. If the retry also OOMs, surface as [LiteRtOomException]
     * so callers can fall back to a cloud LLM instead of crashing the process.
     */
    private suspend fun <T> runWithOomRecovery(
        modelPath: String,
        useGpu: Boolean,
        useNnapi: Boolean,
        block: (Interpreter) -> T,
    ): T {
        return try {
            runWithPool(modelPath, useGpu, useNnapi, block)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OOM during inference for $modelPath — evicting caches and retrying on CPU", e)
            runCatching { interpreterPool.closeAll() }
            tensorCache.clearAll()
            // Retry on CPU only (no GPU/NNAPI delegates — both can be the source of native OOM).
            try {
                runWithPool(modelPath, useGpu = false, useNnapi = false, block)
            } catch (e2: OutOfMemoryError) {
                Log.e(TAG, "OOM recovery failed for $modelPath — surfacing as LiteRtOomException", e2)
                throw LiteRtOomException(
                    "On-device inference OOM for $modelPath — freed all native caches but retry still failed. " +
                        "First OOM: ${e.message}. Second OOM: ${e2.message}",
                    e2,
                )
            }
        }
    }

    private suspend fun <T> runWithPool(
        modelPath: String,
        useGpu: Boolean,
        useNnapi: Boolean,
        block: (Interpreter) -> T,
    ): T {
        // maxPoolSize=2 allows limited parallelism for back-to-back embedding
        // / single-shot inference calls without serializing them through a
        // single interpreter (which would defeat the pool abstraction).
        val lease = interpreterPool.acquire(modelPath, useGpu, useNnapi, maxPoolSize = 2) {
            createInterpreter(modelPath, useGpu, useNnapi)
        }
        return try {
            block(lease.interpreter)
        } finally {
            lease.close()
        }
    }

    private fun createInterpreter(
        modelPath: String,
        useGpu: Boolean,
        useNnapi: Boolean,
    ): InterpreterPool.PooledInterpreter {
        val file = modelManager.resolveToFile(modelPath)
        val config = DelegateManager.DelegateConfig(
            enableNnapi = useNnapi,
            enableGpu = useGpu,
            enableXnnpack = true,
            threadCount = delegateManager.selectThreadCount(),
        )
        val delegateSet = delegateManager.buildDelegates(config)
        val interpreter = runCatching { Interpreter(file, delegateSet.options) }
            .getOrElse {
                // Interpreter construction failed — close delegates + rethrow.
                delegateSet.close()
                throw it
            }
        return InterpreterPool.PooledInterpreter(
            interpreter = interpreter,
            gpuDelegate = delegateSet.gpuDelegate,
            nnApiDelegate = delegateSet.nnApiDelegate,
            mutex = Mutex(),
        )
    }

    private suspend fun recordSuccess(modelPath: String, latencyMs: Long) {
        statsLock.withLock {
            val current = _stats.value.toMutableMap()
            val prev = current[modelPath]
            current[modelPath] = ModelStats(
                modelPath = modelPath,
                inferenceCount = (prev?.inferenceCount ?: 0) + 1,
                totalLatencyMs = (prev?.totalLatencyMs ?: 0) + latencyMs,
                maxLatencyMs = maxOf(prev?.maxLatencyMs ?: 0, latencyMs),
                failureCount = prev?.failureCount ?: 0,
            )
            _stats.value = current
        }
    }

    private suspend fun recordFailure(modelPath: String) {
        statsLock.withLock {
            val current = _stats.value.toMutableMap()
            val prev = current[modelPath]
            current[modelPath] = ModelStats(
                modelPath = modelPath,
                inferenceCount = prev?.inferenceCount ?: 0,
                totalLatencyMs = prev?.totalLatencyMs ?: 0,
                maxLatencyMs = prev?.maxLatencyMs ?: 0,
                failureCount = (prev?.failureCount ?: 0) + 1,
            )
            _stats.value = current
        }
    }

    /** Release all pooled interpreters + tensors. Call on app background / low memory. */
    suspend fun shutdown() {
        interpreterPool.closeAll()
        tensorCache.clearAll()
    }

    companion object {
        private const val TAG = "InferenceScheduler"
    }
}

class LiteRtOomException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
