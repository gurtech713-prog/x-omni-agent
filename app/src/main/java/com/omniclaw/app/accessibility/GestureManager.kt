package com.omniclaw.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Manages gesture dispatch (tap, long-click, swipe, scroll) with reliable
 * completion callbacks, timeout protection, and retry on cancellation.
 *
 * The stock [AccessibilityService.dispatchGesture] is fire-and-forget: it
 * returns `true` when the gesture is *scheduled*, not when it *executes*.
 * For scheduled-but-cancelled gestures (common during animations or when a
 * system dialog steals focus), it returns `true` but the
 * [GestureResultCallback.onCancelled] fires later — leaving the agent
 * believing the tap landed.
 *
 * This manager solves that by:
 *  1. Wrapping every gesture in a suspend coroutine that resumes on either
 *     [GestureResultCallback.onCompleted] or [onCancelled].
 *  2. Applying a per-gesture timeout (default 800ms) so a stuck callback
 *     doesn't hang the agent loop.
 *  3. Retrying cancelled gestures up to [AccessibilityRetryPolicy.maxAttempts]
 *     times with exponential backoff.
 *
 * All gestures are dispatched on the accessibility service's main looper
 * (required by the platform). The suspend functions switch to the caller's
 * dispatcher between attempts.
 */
class GestureManager(
    private val service: AccessibilityService,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val policy: AccessibilityRetryPolicy = AccessibilityRetryPolicy.Default,
) {

    private val totalGestures = AtomicLong(0)
    private val successfulGestures = AtomicLong(0)
    private val cancelledGestures = AtomicLong(0)
    private val timedOutGestures = AtomicLong(0)

    /**
     * Dispatch a tap at (x, y) and suspend until the gesture completes,
     * is cancelled, or times out.
     *
     * @return true if the gesture completed successfully on at least one
     *   attempt; false if all attempts were cancelled or timed out.
     */
    suspend fun tap(x: Int, y: Int): Boolean =
        dispatchWithRetry(
            strokeDurationMs = 60,
            timeoutMs = 500,
            describe = { "tap($x,$y)" },
        ) { pathBuilder ->
            pathBuilder.moveTo(x.toFloat(), y.toFloat())
        }

    /**
     * Dispatch a long-click (press-and-hold) at (x, y).
     *
     * Long-click requires a stroke duration >= 500ms (the platform's
     * long-press threshold). We use 600ms to be safe across OEMs.
     */
    suspend fun longClick(x: Int, y: Int): Boolean =
        dispatchWithRetry(
            strokeDurationMs = 600,
            timeoutMs = 1200,
            describe = { "longClick($x,$y)" },
        ) { pathBuilder ->
            pathBuilder.moveTo(x.toFloat(), y.toFloat())
        }

    /**
     * Dispatch a swipe from (x1, y1) to (x2, y2).
     *
     * Swipe duration is 300ms — fast enough to register as a fling on most
     * apps, slow enough that scrollable containers catch it as a drag.
     */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean =
        dispatchWithRetry(
            strokeDurationMs = 300,
            timeoutMs = 800,
            describe = { "swipe($x1,$y1,$x2,$y2)" },
        ) { pathBuilder ->
            pathBuilder.moveTo(x1.toFloat(), y1.toFloat())
            pathBuilder.lineTo(x2.toFloat(), y2.toFloat())
        }

    /**
     * Dispatch a vertical scroll by [dy] pixels at horizontal center [x].
     * Negative dy scrolls up (toward top), positive scrolls down.
     */
    suspend fun scrollVertical(x: Int, dy: Int): Boolean {
        val y1 = if (dy > 0) 800 else 400
        val y2 = y1 - dy
        return swipe(x, y1, x, y2)
    }

    /**
     * Dispatch a horizontal scroll by [dx] pixels at vertical center [y].
     * Negative dx scrolls left, positive scrolls right.
     */
    suspend fun scrollHorizontal(y: Int, dx: Int): Boolean {
        val x1 = if (dx > 0) 800 else 400
        val x2 = x1 - dx
        return swipe(x1, y, x2, y)
    }

    // ---- Internal ----

    private suspend fun dispatchWithRetry(
        strokeDurationMs: Long,
        timeoutMs: Long,
        describe: () -> String,
        buildPath: (Path) -> Unit,
    ): Boolean {
        val desc = describe()
        for (attempt in 0 until policy.maxAttempts) {
            val delay = policy.delayForAttempt(attempt)
            if (delay > 0) kotlinx.coroutines.delay(delay)

            val result = dispatchOnce(strokeDurationMs, timeoutMs, desc, buildPath)
            when (result) {
                GestureResult.COMPLETED -> {
                    successfulGestures.incrementAndGet()
                    return true
                }
                GestureResult.CANCELLED -> {
                    cancelledGestures.incrementAndGet()
                    Log.w(TAG, "$desc cancelled on attempt ${attempt + 1}/${policy.maxAttempts}")
                }
                GestureResult.TIMED_OUT -> {
                    timedOutGestures.incrementAndGet()
                    Log.w(TAG, "$desc timed out on attempt ${attempt + 1}/${policy.maxAttempts}")
                }
            }
        }
        Log.w(TAG, "$desc failed after ${policy.maxAttempts} attempts")
        return false
    }

    private suspend fun dispatchOnce(
        strokeDurationMs: Long,
        timeoutMs: Long,
        desc: String,
        buildPath: (Path) -> Unit,
    ): GestureResult {
        totalGestures.incrementAndGet()
        val path = Path().also(buildPath)
        val stroke = GestureDescription.StrokeDescription(path, 0, strokeDurationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(GestureResult.COMPLETED)
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(GestureResult.CANCELLED)
                    }
                }
                try {
                    val scheduled = service.dispatchGesture(gesture, callback, handler)
                    if (!scheduled) {
                        // dispatchGesture returned false — the gesture was not
                        // even scheduled (e.g. service is disconnected). Resume
                        // immediately with CANCELLED.
                        if (cont.isActive) cont.resume(GestureResult.CANCELLED)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "$desc dispatchGesture threw: ${e.message}")
                    if (cont.isActive) cont.resume(GestureResult.CANCELLED)
                }
                cont.invokeOnCancellation { /* gesture can't be cancelled mid-flight */ }
            }
        } ?: GestureResult.TIMED_OUT
    }

    private enum class GestureResult { COMPLETED, CANCELLED, TIMED_OUT }

    /** Snapshot of gesture statistics for diagnostics. */
    fun stats(): GestureStats = GestureStats(
        totalDispatched = totalGestures.get(),
        successful = successfulGestures.get(),
        cancelled = cancelledGestures.get(),
        timedOut = timedOutGestures.get(),
    )

    data class GestureStats(
        val totalDispatched: Long,
        val successful: Long,
        val cancelled: Long,
        val timedOut: Long,
    ) {
        val successRate: Float get() = if (totalDispatched == 0L) 0f else successful.toFloat() / totalDispatched
    }

    companion object {
        private const val TAG = "GestureManager"
    }
}
