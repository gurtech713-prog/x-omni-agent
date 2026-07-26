package com.omniclaw.app.agent.tools

import com.omniclaw.app.accessibility.AccessibilityExecutor
import com.omniclaw.app.service.OmniAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified device-tool surface — one entry point for the agent loop to:
 *   - snapshot the current screen (accessibility tree or screenshot)
 *   - dispatch atomic Android actions (tap, swipe, type, launch, back, home)
 *
 * Delegates to [AccessibilityExecutor] for all operations. The executor
 * coordinates [com.omniclaw.app.accessibility.NodeSearchEngine],
 * [com.omniclaw.app.accessibility.GestureManager],
 * [com.omniclaw.app.accessibility.WindowTracker], and
 * [com.omniclaw.app.accessibility.AccessibilityMetrics] internally.
 *
 * If the a11y service is not connected, calls return gracefully so the
 * agent loop can still reason about screenshots-only fallback (per the
 * X-OmniClaw "vision fallback & dual-track decisions" feature).
 */
@Singleton
class DeviceScheduler @Inject constructor(
    private val executor: AccessibilityExecutor,
) {

    @Volatile
    var boundService: OmniAccessibilityService? = null
        set(value) {
            field = value
            // Keep the executor's service reference in sync so it can
            // dispatch gestures + read the tree.
            executor.service = value
        }

    init {
        // Register this scheduler with the executor so the executor can read
        // the bound service via the back-reference (handles the case where
        // the service connects before the executor is wired up).
        executor.deviceScheduler = this
    }

    /** Returns a flat text representation of the current UI tree. */
    suspend fun snapshot(): String {
        val svc = boundService ?: return "(accessibility service not connected)"
        return executor.snapshot()
    }

    /**
     * Synchronous snapshot for non-suspend callers (e.g. [SuccessMonitor.verifyLast]).
     *
     * Runs the snapshot on the accessibility service's main looper via
     * [OmniAccessibilityService.snapshotTree], which is already synchronous.
     * Does NOT benefit from the executor's retry / root-recovery logic —
     * callers that need those guarantees should use the suspend [snapshot].
     */
    fun snapshotBlocking(): String {
        val svc = boundService ?: return "(accessibility service not connected)"
        return svc.snapshotTree() ?: "(empty tree)"
    }

    /** Returns the raw bitmap bytes of the latest screenshot, or null. */
    suspend fun screenshot(): ByteArray? = executor.screenshot()

    suspend fun dispatch(action: DeviceAction): Boolean {
        val svc = boundService ?: return false
        return executor.dispatch(action)
    }

    /**
     * Synchronous dispatch for non-suspend callers (BehaviorRecorder replay
     * legacy path). Prefer the suspend [dispatch] for new code.
     */
    fun dispatchBlocking(action: DeviceAction): Boolean {
        val svc = boundService ?: return false
        return when (action) {
            is DeviceAction.Tap -> svc.tap(action.x, action.y)
            is DeviceAction.Swipe -> svc.swipe(action.x1, action.y1, action.x2, action.y2)
            is DeviceAction.Type -> svc.type(action.text)
            is DeviceAction.Launch -> svc.launch(action.packageName)
            DeviceAction.Back -> svc.back()
            DeviceAction.Home -> svc.home()
            DeviceAction.Screenshot -> true
            DeviceAction.NoOp -> true
        }
    }
}
