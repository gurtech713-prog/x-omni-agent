package com.omniclaw.app.gateway

import android.content.Context
import com.omniclaw.app.service.AgentForegroundService
import com.omniclaw.app.service.HaloOverlayService
import com.omniclaw.app.service.ScreenCaptureService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service gateway abstraction layer.
 *
 * Decouples the agent loop from direct service dependencies, making testing
 * easier and allowing for mock implementations in unit tests.
 */
@Singleton
class ServiceGateway @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    /** Start the foreground service for agent operation. */
    fun startForegroundService() {
        runCatching { AgentForegroundService.start(ctx) }
    }

    /** Stop the foreground service. */
    fun stopForegroundService() {
        runCatching { AgentForegroundService.stop(ctx) }
    }

    /** Show halo status update. */
    fun showHaloStatus(text: String) {
        runCatching { HaloOverlayService.start(ctx, text) }
    }

    /** Hide the halo overlay. */
    fun hideHalo() {
        runCatching { HaloOverlayService.stop(ctx) }
    }

    /** Capture current screen as WebP bytes (M-21: payload is WebP, not PNG). */
    suspend fun captureScreen(): ByteArray? {
        return ScreenCaptureService.latestFrameBytes()
    }

    /** Check if screen capture is currently running. */
    fun isScreenCapturing(): Boolean {
        return ScreenCaptureService.isRunning()
    }

    /** Request screen capture permission. */
    fun requestScreenCapture() {
        // Delegate to the MediaProjection permission launcher holder (audit M-45);
        // the previous empty body silently did nothing.
        runCatching { com.omniclaw.app.ScreenCaptureRequestHolder.request() }
    }
}
