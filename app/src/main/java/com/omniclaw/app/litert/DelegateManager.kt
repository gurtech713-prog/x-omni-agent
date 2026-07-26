package com.omniclaw.app.litert

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages LiteRT delegate lifecycle (NNAPI, GPU, XNNPACK) with automatic
 * fallback.
 *
 * Delegate creation order on first load:
 *   1. NNAPI (if available + enabled) — best cross-vendor acceleration.
 *   2. GPU (if explicitly requested + available) — fast for float32 models.
 *   3. XNNPACK (CPU, always available) — predictable performance baseline.
 *
 * If the chosen delegate fails at interpreter-creation time (some int8
 * models fail GPU compilation), [DelegateManager] falls back to the next
 * delegate in the chain. If ALL delegates fail, it falls back to a plain
 * CPU interpreter — inference still works, just slower.
 *
 * Thread count selection: [selectThreadCount] reads the device's CPU count
 * and available RAM, returning a thread count that balances parallelism
 * against memory pressure. On a typical 8-core phone with 6GB RAM, this
 * returns 4 (half the cores, leaving headroom for the UI).
 */
@Singleton
class DelegateManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    /** Which delegates are enabled. NNAPI on by default; GPU off (opt-in). */
    data class DelegateConfig(
        val enableNnapi: Boolean = true,
        val enableGpu: Boolean = false,
        val enableXnnpack: Boolean = true,
        val threadCount: Int = 4,
    )

    /**
     * Build a delegate chain for the given config. Returns a [DelegateSet]
     * containing the created delegates + options, ready to pass to
     * [org.tensorflow.lite.Interpreter.Options].
     *
     * If NNAPI creation throws, we fall back to GPU. If GPU throws, we fall
     * back to XNNPACK. If XNNPACK is unavailable (shouldn't happen on modern
     * Android), we return an empty set (plain CPU).
     */
    fun buildDelegates(config: DelegateConfig): DelegateSet {
        val options = org.tensorflow.lite.Interpreter.Options()
        val delegates = mutableListOf<Any>()
        var gpuDelegate: org.tensorflow.lite.gpu.GpuDelegate? = null
        var nnApiDelegate: org.tensorflow.lite.nnapi.NnApiDelegate? = null

        if (config.enableNnapi) {
            runCatching {
                nnApiDelegate = org.tensorflow.lite.nnapi.NnApiDelegate()
                options.addDelegate(nnApiDelegate)
                delegates.add(nnApiDelegate!!)
            }.onFailure { Log.w(TAG, "NNAPI delegate unavailable: ${it.message}") }
        }

        if (config.enableGpu && nnApiDelegate == null) {
            // Only try GPU if NNAPI didn't load — mixing delegates is undefined.
            runCatching {
                gpuDelegate = org.tensorflow.lite.gpu.GpuDelegate()
                options.addDelegate(gpuDelegate)
                delegates.add(gpuDelegate!!)
            }.onFailure { Log.w(TAG, "GPU delegate unavailable: ${it.message}") }
        }

        // XNNPACK is always enabled via setUseXNNPACK(true) on the options.
        if (config.enableXnnpack && nnApiDelegate == null && gpuDelegate == null) {
            runCatching { options.setUseXNNPACK(true) }
                .onFailure { Log.w(TAG, "XNNPACK unavailable: ${it.message}") }
        }

        options.setNumThreads(config.threadCount)
        return DelegateSet(options, nnApiDelegate, gpuDelegate, delegates.toList())
    }

    /**
     * Select an optimal thread count for the current device.
     *
     * Heuristic: use min(CPU cores / 2, 4) on devices with >= 4GB AVAILABLE RAM;
     * use 2 on lower-end devices to avoid memory pressure. We measure
     * `availMem` (not `totalMem`) — a 6GB device running 4GB of background apps
     * should pick the small-thread-count path the same as a 2GB device.
     */
    fun selectThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val availMb = availMemoryMb()
        val totalMb = totalMemoryMb()
        return when {
            // Conservative: based on available memory, not total.
            availMb >= 2 * 1024 && totalMb >= 6 * 1024 -> (cores / 2).coerceIn(2, 6)
            availMb >= 1 * 1024 && totalMb >= 4 * 1024 -> (cores / 2).coerceIn(2, 4)
            else -> 2
        }
    }

    private fun availMemoryMb(): Long {
        return runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            (mi?.availMem ?: (1L * 1024 * 1024 * 1024)) / (1024 * 1024)
        }.getOrDefault(1024L)
    }

    private fun totalMemoryMb(): Long {
        return runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            (mi?.totalMem ?: (4L * 1024 * 1024 * 1024)) / (1024 * 1024)
        }.getOrDefault(4096L)
    }

    /** A built set of delegates + options, ready for interpreter construction. */
    data class DelegateSet(
        val options: org.tensorflow.lite.Interpreter.Options,
        val nnApiDelegate: org.tensorflow.lite.nnapi.NnApiDelegate?,
        val gpuDelegate: org.tensorflow.lite.gpu.GpuDelegate?,
        val allDelegates: List<Any>,
    ) {
        /** Close all delegates. Safe to call multiple times. */
        fun close() {
            runCatching { nnApiDelegate?.close() }
            runCatching { gpuDelegate?.close() }
        }
    }

    companion object {
        private const val TAG = "DelegateManager"
    }
}
