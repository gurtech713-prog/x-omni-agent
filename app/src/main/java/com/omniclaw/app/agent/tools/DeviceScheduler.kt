package com.omniclaw.app.agent.tools

import com.omniclaw.app.service.OmniAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified device-tool surface — one entry point for the agent loop to:
 *   - snapshot the current screen (accessibility tree or screenshot)
 *   - dispatch atomic Android actions (tap, swipe, type, launch, back, home)
 *
 * Actual execution is delegated to [OmniAccessibilityService] when it is
 * connected. If the service is not connected, calls return gracefully so the
 * agent loop can still reason about screenshots-only fallback (per the
 * X-OmniClaw "vision fallback & dual-track decisions" feature).
 */
@Singleton
class DeviceScheduler @Inject constructor() {

    @Volatile
    var boundService: OmniAccessibilityService? = null

    /** Returns a flat text representation of the current UI tree. */
    fun snapshot(): String {
        val svc = boundService ?: return "(accessibility service not connected)"
        val tree = svc.snapshotTree() ?: return "(empty tree)"
        return tree
    }

    /** Returns the raw bitmap bytes of the latest screenshot, or null. */
    suspend fun screenshot(): ByteArray? = boundService?.screenshot()

    fun dispatch(action: DeviceAction): Boolean {
        val svc = boundService ?: return false
        return when (action) {
            is DeviceAction.Tap -> svc.tap(action.x, action.y)
            is DeviceAction.Swipe -> svc.swipe(action.x1, action.y1, action.x2, action.y2)
            is DeviceAction.Type -> svc.type(action.text)
            is DeviceAction.Launch -> svc.launch(action.packageName)
            DeviceAction.Back -> svc.back()
            DeviceAction.Home -> svc.home()
            // Screenshot is handled by the agent loop's vision-fallback path
            // (which is suspend). Mark as no-op here so the loop falls through
            // to its own screenshot call.
            DeviceAction.Screenshot -> true
            DeviceAction.NoOp -> true
        }
    }
}
