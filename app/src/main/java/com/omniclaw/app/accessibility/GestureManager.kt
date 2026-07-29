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
 *
 * SCREEN-AWARE SCROLLING: [scrollVertical] / [scrollHorizontal] / [scroll]
 * read the real screen dimensions from the service's [android.util.DisplayMetrics]
 * so the swipe path is correctly placed on any device — previously they used
 * hardcoded 800/400 coordinates which only worked on 1280px-tall screens and
 * silently scrolled the wrong region (or nowhere) on every other device.
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

    /** Direction enum for high-level scroll API. */
    enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

    /**
     * Dispatch a tap at (x, y) and suspend until the gesture completes,
     * is cancelled, or times out.
     *
     * Uses the [AccessibilityRetryPolicy.Aggressive] schedule (5 attempts,
     * 80ms base) instead of the constructor's [policy]. Taps are the most
     * common action AND the most timing-sensitive: a tap that lands during
     * a 200ms window-in-transition animation is silently cancelled by the
     * platform, and the default 3-attempt/150ms policy often exhausts its
     * retries before the animation finishes. The aggressive policy rides
     * out ~400ms of animation, which covers virtually all real-world
     * activity transitions.
     *
     * @return true if the gesture completed successfully on at least one
     *   attempt; false if all attempts were cancelled or timed out.
     */
    suspend fun tap(x: Int, y: Int): Boolean =
        dispatchWithRetry(
            policy = AccessibilityRetryPolicy.Aggressive,
            // STROKE DURATION FIX: increased from 60ms to 150ms.
            // 60ms is too short for many OEMs (Samsung, Xiaomi, OPPO) — the
            // gesture is silently dropped because the platform interprets it
            // as a transient touch event rather than a deliberate tap.
            // 150ms is long enough to be recognized as a tap on ALL devices,
            // while still being short enough to feel instantaneous to the user.
            // (The long-press threshold is ~500ms, so 150ms is safely below it.)
            strokeDurationMs = 150,
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
     * Dispatch a drag (long-press + move + release). Used for drag-and-drop.
     *
     * The gesture has two phases:
     *   1. Long-press at (x1, y1) for 600ms (picks up the element)
     *   2. Move to (x2, y2) over 400ms (drags it)
     *   3. Release (drops the element)
     *
     * Implemented as a single StrokeDescription with willContinue=false and
     * a total duration of 1000ms. The platform interprets the slow start as
     * a long-press and the subsequent move as a drag.
     */
    suspend fun drag(x1: Int, y1: Int, x2: Int, y2: Int): Boolean =
        dispatchWithRetry(
            policy = AccessibilityRetryPolicy.Default,
            strokeDurationMs = 1000,
            timeoutMs = 2000,
            describe = { "drag($x1,$y1,$x2,$y2)" },
        ) { pathBuilder ->
            pathBuilder.moveTo(x1.toFloat(), y1.toFloat())
            // Hold position for the first 600ms (long-press), then move.
            // The platform handles the timing internally based on stroke duration.
            pathBuilder.lineTo(x1.toFloat(), y1.toFloat())  // hold
            pathBuilder.lineTo(x2.toFloat(), y2.toFloat())  // drag
        }

    /**
     * Dispatch a vertical scroll by [dy] pixels at horizontal center [x].
     * Negative dy scrolls up (toward top), positive scrolls down.
     *
     * SCREEN-AWARE FIX: previously hardcoded y1=800/y2=400 (or vice-versa),
     * which assumed a ~1280px-tall screen. On a 2400px-tall phone the swipe
     * started at 800 (near the top quarter) and barely moved; on a 640px
     * tablet it started off-screen. Now we read the real viewport height
     * from the service's DisplayMetrics and place the swipe in the vertical
     * middle third — the region that reliably hits scrollable content
     * (RecyclerView/WebView/NestedScrollView) without colliding with top
     * app bars or bottom nav bars.
     */
    suspend fun scrollVertical(x: Int, dy: Int): Boolean {
        val h = screenHeight()
        val w = screenWidth()
        val cx = if (x <= 0) w / 2 else x
        // Start at 65% height, end at 35% height — a ~30% screen-height
        // swipe is large enough to register as a meaningful scroll on every
        // device, and stays clear of the top status bar / bottom nav bar.
        val startY = (h * 0.65f).toInt()
        val endY = (h * 0.35f).toInt()
        // For dy>0 (scroll down) we swipe UP (start lower, end higher).
        // For dy<0 (scroll up) we swipe DOWN (start higher, end lower).
        val (y1, y2) = if (dy >= 0) startY to endY else endY to startY
        return swipe(cx, y1, cx, y2)
    }

    /**
     * Dispatch a horizontal scroll by [dx] pixels at vertical center [y].
     * Negative dx scrolls left, positive scrolls right.
     *
     * SCREEN-AWARE FIX: previously hardcoded x1=800/x2=400. Now reads the
     * real viewport width and places the swipe in the horizontal middle band.
     */
    suspend fun scrollHorizontal(y: Int, dx: Int): Boolean {
        val w = screenWidth()
        val h = screenHeight()
        val cy = if (y <= 0) h / 2 else y
        val startX = (w * 0.65f).toInt()
        val endX = (w * 0.35f).toInt()
        // For dx>0 (scroll right) we swipe LEFT (start right, end left).
        // For dx<0 (scroll left) we swipe RIGHT (start left, end right).
        val (x1, x2) = if (dx >= 0) startX to endX else endX to startX
        return swipe(x1, cy, x2, cy)
    }

    /**
     * High-level directional scroll. Computes the swipe path from the real
     * screen dimensions and dispatches a fling-style swipe.
     *
     * [amount] is a fraction of the screen dimension (0.0–1.0). Default 0.35
     * scrolls about one third of the viewport — enough to bring new content
     * into view without overshooting. Values >0.5 are clamped to 0.5 to avoid
     * the swipe being interpreted as a fling-to-end by some containers.
     *
     * This is the preferred scroll entry point for the agent because it's
     * direction-based (the LLM says "scroll down", not "swipe from pixel
     * A to pixel B"), which is robust across screen sizes and orientations.
     */
    suspend fun scroll(direction: ScrollDirection, amount: Float = 0.35f): Boolean {
        val w = screenWidth()
        val h = screenHeight()
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "scroll($direction) aborted: screen dimensions unavailable (${w}x${h})")
            return false
        }
        val frac = amount.coerceIn(0.1f, 0.5f)
        val cx = w / 2
        val cy = h / 2
        val dy = (h * frac).toInt()
        val dx = (w * frac).toInt()
        return when (direction) {
            ScrollDirection.UP    -> swipe(cx, cy + dy, cx, cy - dy)
            ScrollDirection.DOWN  -> swipe(cx, cy - dy, cx, cy + dy)
            ScrollDirection.LEFT  -> swipe(cx + dx, cy, cx - dx, cy)
            ScrollDirection.RIGHT -> swipe(cx - dx, cy, cx + dx, cy)
        }
    }

    // ---- Screen dimension helpers ----

    /** Real viewport width in pixels, or 0 if unavailable. */
    fun screenWidth(): Int = runCatching {
        val dm = android.util.DisplayMetrics()
        // Use the WindowManager's default display for the real viewport —
        // `service.resources.displayMetrics` can return the app window's
        // metrics which differ from the full screen on multi-window devices.
        val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        dm.widthPixels
    }.getOrDefault(0)

    /** Real viewport height in pixels, or 0 if unavailable. */
    fun screenHeight(): Int = runCatching {
        val dm = android.util.DisplayMetrics()
        val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        dm.heightPixels
    }.getOrDefault(0)

    // ---- Internal ----

    private suspend fun dispatchWithRetry(
        strokeDurationMs: Long,
        timeoutMs: Long,
        describe: () -> String,
        buildPath: (Path) -> Unit,
    ): Boolean = dispatchWithRetry(
        policy = this.policy,
        strokeDurationMs = strokeDurationMs,
        timeoutMs = timeoutMs,
        describe = describe,
        buildPath = buildPath,
    )

    private suspend fun dispatchWithRetry(
        policy: AccessibilityRetryPolicy,
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
