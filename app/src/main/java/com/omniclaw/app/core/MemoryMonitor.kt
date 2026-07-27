package com.omniclaw.app.core

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Memory pressure monitor for graceful degradation.
 *
 * Monitors available memory and triggers cache cleanup when under pressure.
 * Used by VisionPipeline, BitmapPool, and other memory-intensive components.
 */
@Singleton
class MemoryMonitor @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    /** Threshold for low memory (100MB free). */
    private val LOW_MEMORY_THRESHOLD = 100L * 1024 * 1024
    
    /** Threshold for critical memory (50MB free). */
    private val CRITICAL_MEMORY_THRESHOLD = 50L * 1024 * 1024
    
    data class MemoryInfo(
        val totalMemory: Long,
        val freeMemory: Long,
        val usedMemory: Long,
        val memoryClass: Int,
        val isLowMemory: Boolean,
        val isCriticalMemory: Boolean,
    )
    
    fun getMemoryInfo(): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val total = runtime.totalMemory()
        val free = runtime.freeMemory()
        val used = total - free

        // U-L7: safe-cast the system service — previously `as ActivityManager`
        // would ClassCastException if some OEM / test context returned a
        // different implementation. Returning a degraded MemoryInfo (no
        // system-level lowMemory / availMem signal) is preferable to crashing.
        val activityManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return MemoryInfo(
                totalMemory = total,
                freeMemory = free,
                usedMemory = used,
                memoryClass = 0,
                isLowMemory = false,
                isCriticalMemory = false,
            )
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        return MemoryInfo(
            totalMemory = total,
            freeMemory = free,
            usedMemory = used,
            memoryClass = activityManager.memoryClass,
            isLowMemory = memInfo.availMem < LOW_MEMORY_THRESHOLD || memInfo.lowMemory,
            isCriticalMemory = memInfo.availMem < CRITICAL_MEMORY_THRESHOLD,
        )
    }
    
    fun shouldDisableVisionFallback(): Boolean {
        val info = getMemoryInfo()
        // Disable vision fallback on low memory to save API costs and processing
        return info.isLowMemory
    }
    
    fun shouldReleaseCaches(): Boolean {
        val info = getMemoryInfo()
        return info.isCriticalMemory
    }
}
