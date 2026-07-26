package com.omniclaw.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.omniclaw.app.agent.tools.DeviceAction
import com.omniclaw.app.agent.tools.DeviceScheduler
import com.omniclaw.app.logging.AgentLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level executor for all accessibility operations.
 *
 * This is the production-grade replacement for the ad-hoc tap/swipe/type
 * methods that lived directly on [OmniAccessibilityService]. It coordinates:
 *
 *   - [NodeSearchEngine] for leak-safe, depth-bounded tree traversal.
 *   - [GestureManager] for reliable, retryable gesture dispatch.
 *   - [WindowTracker] for dialog/keyboard/shade awareness.
 *   - [AccessibilityDiagnostics] + [AccessibilityMetrics] for observability.
 *
 * The executor is a [Singleton] injected into [DeviceScheduler], which
 * delegates to it when the a11y service is connected. When the service is
 * NOT connected, the executor returns graceful failures so the agent loop
 * can fall back to vision.
 *
 * Every method:
 *   1. Checks window state (dismisses dialogs/keyboard if they'd block the action).
 *   2. Retries on transient failure per [AccessibilityRetryPolicy].
 *   3. Times the operation and records it in [AccessibilityMetrics].
 *   4. Logs diagnostic events to [AccessibilityDiagnostics].
 *   5. Never leaks [AccessibilityNodeInfo] objects — the [NodeSearchEngine]
 *      recycles every node it touches (except roots owned by the caller and
 *      returned targets owned by the caller).
 */
@Singleton
class AccessibilityExecutor @Inject constructor(
    private val diagnostics: AccessibilityDiagnostics,
    private val metrics: AccessibilityMetrics,
    private val windowTracker: WindowTracker,
    private val logger: AgentLogger,
) {

    private val searchEngine = NodeSearchEngine()
    /** The bound accessibility service, set by [DeviceScheduler] on connect. */
    @Volatile
    var service: AccessibilityService? = null

    /** The bound scheduler, used to read the `boundService` field. */
    @Volatile
    var deviceScheduler: DeviceScheduler? = null

    private fun svc(): AccessibilityService? = service ?: deviceScheduler?.boundService

    /**
     * Capture a flat text snapshot of the current accessibility tree.
     *
     * Recovers gracefully from null roots (waits up to 800ms with 3 retries,
     * re-querying `rootInActiveWindow` each time). Returns a sentinel string
     * if the service is disconnected or the root remains null after retries.
     */
    suspend fun snapshot(): String {
        val s = svc()
        if (s == null) {
            metrics.recordSnapshot(0)
            return "(accessibility service not connected)"
        }
        if (s !is AccessibilityService) {
            metrics.recordSnapshot(0)
            return "(accessibility service not connected)"
        }
        val start = System.currentTimeMillis()
        val result = snapshotWithRootRecovery(s)
        metrics.recordSnapshot(System.currentTimeMillis() - start)
        return result
    }

    private suspend fun snapshotWithRootRecovery(svc: AccessibilityService): String {
        val policy = AccessibilityRetryPolicy.Default
        for (attempt in 0 until policy.maxAttempts) {
            val root = runCatching { svc.rootInActiveWindow }.getOrNull()
            if (root != null) {
                try {
                    val sb = StringBuilder()
                    appendNode(sb, root, 0)
                    return sb.toString()
                } finally {
                    runCatching { root.recycle() }
                }
            }
            diagnostics.recordNullRoot()
            delay(50L)
        }
        diagnostics.recordRootRecovery()
        return "(empty tree — root null after ${policy.maxAttempts} retries)"
    }

    /**
     * Dispatch a [DeviceAction]. Returns true on success.
     *
     * Before dispatching, this clears any blocking overlay (dialog, keyboard)
     * that would intercept the action. After dispatching, it waits for the
     * screen to stabilize so the next snapshot reflects the post-action state.
     */
    suspend fun dispatch(action: DeviceAction): Boolean {
        val svc = svc() as? AccessibilityService ?: return false
        // Pre-action: dismiss dialogs / keyboard that would intercept.
        clearBlockingOverlays(svc)

        return when (action) {
            is DeviceAction.Tap -> {
                val start = System.currentTimeMillis()
                val gm = GestureManager(svc, policy = AccessibilityRetryPolicy.Default)
                val ok = gm.tap(action.x, action.y)
                metrics.recordTap(System.currentTimeMillis() - start, ok)
                if (ok) waitForStabilization()
                ok
            }
            is DeviceAction.Swipe -> {
                val start = System.currentTimeMillis()
                val gm = GestureManager(svc, policy = AccessibilityRetryPolicy.Default)
                val ok = gm.swipe(action.x1, action.y1, action.x2, action.y2)
                metrics.recordSwipe(System.currentTimeMillis() - start, ok)
                if (ok) waitForStabilization()
                ok
            }
            is DeviceAction.Type -> {
                val ok = typeText(svc, action.text)
                metrics.recordType(ok)
                // Typing into a field usually opens the IME + triggers layout
                // shifts (suggestions bar appears, field scrolls into view).
                // Wait for stabilization so the next snapshot reflects the
                // post-type state — same rationale as tap/swipe/launch.
                if (ok) waitForStabilization()
                ok
            }
            is DeviceAction.Launch -> {
                val ok = launchPackage(svc, action.packageName)
                metrics.recordLaunch(ok)
                if (ok) waitForStabilization()
                ok
            }
            DeviceAction.Back -> {
                val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                metrics.recordBack()
                windowTracker.clearKeyboard()
                windowTracker.clearDialog()
                if (ok) waitForStabilization()
                ok
            }
            DeviceAction.Home -> {
                val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                metrics.recordHome()
                if (ok) waitForStabilization()
                ok
            }
            DeviceAction.Screenshot -> true  // handled by the agent loop's vision path
            DeviceAction.NoOp -> true
        }
    }

    /** Capture a screenshot as compressed bytes (WebP/PNG). */
    suspend fun screenshot(): ByteArray? {
        val svc = svc() as? OmniA11yLike ?: return null
        val start = System.currentTimeMillis()
        val bytes = runCatching { svc.screenshot() }.getOrNull()
        metrics.recordScreenshot(System.currentTimeMillis() - start)
        return bytes
    }

    // ---- Internal helpers ----

    /**
     * Dismiss dialogs and the soft keyboard before dispatching an action.
     *
     * Rationale: a tap at (x, y) that lands on a dialog's dimmed background
     * is silently swallowed. A tap that lands on the keyboard hits a key
     * instead of the field behind it. Pressing BACK clears both, and the
     * subsequent action lands on the intended target.
     *
     * We only press BACK when a dialog/keyboard is actually visible —
     * never speculatively — so we don't dismiss the user's legitimate
     * foreground activity.
     */
    private fun clearBlockingOverlays(svc: AccessibilityService) {
        val state = windowTracker.current
        if (state.isDialogVisible || state.isKeyboardVisible) {
            val ok = runCatching {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }.getOrDefault(false)
            if (ok) {
                if (state.isDialogVisible) {
                    diagnostics.recordDialogDismissal()
                    windowTracker.clearDialog()
                }
                if (state.isKeyboardVisible) {
                    diagnostics.recordKeyboardDismissal()
                    windowTracker.clearKeyboard()
                }
            }
        }
    }

    /**
     * Wait for the screen to stabilize after an action.
     *
     * Polls the accessibility tree fingerprint every 100ms; once two
     * consecutive reads match (or after 1.5s), returns. This prevents the
     * next snapshot from capturing a mid-animation state.
     */
    private suspend fun waitForStabilization() {
        val cap = 600L
        val start = System.currentTimeMillis()
        var prev = quickFingerprint()
        delay(50)
        val firstCheck = quickFingerprint()
        if (firstCheck == prev && firstCheck.isNotBlank()) return
        prev = firstCheck
        while (System.currentTimeMillis() - start < cap) {
            delay(50)
            val cur = quickFingerprint()
            if (cur == prev) return
            prev = cur
        }
    }

    private fun quickFingerprint(): String {
        val svc = svc() as? AccessibilityService ?: return ""
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return ""
        try {
            return "${root.packageName}:${root.childCount}"
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * Type text into the focused (or first editable) field.
     *
     * Uses [NodeSearchEngine] to find an editable node if none is focused.
     * Recycles all intermediate nodes. The target node is recycled after
     * the action.
     */
    private suspend fun typeText(svc: AccessibilityService, text: String): Boolean {
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return false
        var target: AccessibilityNodeInfo? = null
        try {
            // Try the focused node first (free — no search needed).
            target = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            if (target == null) {
                // No focus — find the first editable node.
                target = searchEngine.findFirst(root, searchEngine.editable)
            }
            if (target == null) {
                Log.w(TAG, "type(\"$text\") failed: no editable node found")
                return false
            }
            // Focus the node before setting text (some fields require focus).
            if (!target.isFocused) {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return runCatching {
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)
        } finally {
            runCatching { target?.recycle() }
            runCatching { root.recycle() }
        }
    }

    private fun launchPackage(svc: AccessibilityService, packageNameOrAppName: String): Boolean {
        return try {
            val pm = svc.packageManager
            val cleanedInput = packageNameOrAppName.trim().trim('"', '\'').lowercase()
            
            // Map common app names to popular package names if passed by friendly name
            val knownMap = mapOf(
                "whatsapp" to "com.whatsapp",
                "youtube" to "com.google.android.youtube",
                "chrome" to "com.android.chrome",
                "browser" to "com.android.chrome",
                "settings" to "com.android.settings",
                "instagram" to "com.instagram.android",
                "facebook" to "com.facebook.katana",
                "twitter" to "com.twitter.android",
                "x" to "com.twitter.android",
                "telegram" to "org.telegram.messenger",
                "spotify" to "com.spotify.music",
                "maps" to "com.google.android.apps.maps",
                "gmail" to "com.google.android.gm",
                "camera" to "com.android.camera",
                "calculator" to "com.google.android.calculator",
                "clock" to "com.google.android.deskclock",
                "photos" to "com.google.android.apps.photos",
                "gallery" to "com.miui.gallery"
            )
            
            val pkg = knownMap[cleanedInput] ?: packageNameOrAppName.trim().trim('"', '\'')
            
            var intent = pm.getLaunchIntentForPackage(pkg)
            
            // If getLaunchIntentForPackage returned null and input looks like an app label, search installed applications
            if (intent == null && !cleanedInput.contains('.')) {
                val installedApps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                for (appInfo in installedApps) {
                    val label = pm.getApplicationLabel(appInfo).toString().lowercase()
                    if (label == cleanedInput || label.contains(cleanedInput)) {
                        val foundIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                        if (foundIntent != null) {
                            intent = foundIntent
                            break
                        }
                    }
                }
            }
            
            if (intent == null) {
                Log.w(TAG, "launch($packageNameOrAppName) failed: no launch intent found")
                return false
            }
            
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            svc.startActivity(intent)
            val prev = windowTracker.current.foregroundPackage
            if (prev != pkg) {
                diagnostics.recordPackageSwitch(prev, pkg)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "launch($packageNameOrAppName) failed: ${e.message}")
            false
        }
    }

    /**
     * Append a node and its children to [sb] as a flat text tree.
     *
     * Recycles every child node it obtains. The root [node] is NOT recycled
     * here (the caller owns it).
     */
    private fun appendNode(sb: StringBuilder, node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 50) return
        val pad = "  ".repeat(depth.coerceAtMost(8))
        val cls = runCatching { node.className?.toString()?.substringAfterLast('.') }.getOrNull() ?: "?"
        val text = runCatching { node.text?.toString().orEmpty().take(80) }.getOrDefault("")
        val rawId = runCatching { node.viewIdResourceName }.getOrNull()
        val id = if (rawId != null) logger.rebindRef(rawId, node.packageName?.toString() ?: "") ?: rawId else ""
        sb.append("$pad- $cls")
        if (id.isNotBlank()) sb.append(" id=$id")
        if (text.isNotBlank()) sb.append(" text=\"$text\"")
        if (runCatching { node.isClickable }.getOrDefault(false)) sb.append(" [clickable]")
        if (runCatching { node.isScrollable }.getOrDefault(false)) sb.append(" [scrollable]")
        if (runCatching { node.isClickable || node.isScrollable }.getOrDefault(false)) {
            val rect = android.graphics.Rect()
            runCatching { node.getBoundsInScreen(rect) }
            sb.append(" bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
        }
        sb.appendLine()
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        val children = ArrayList<AccessibilityNodeInfo>(childCount)
        for (i in 0 until childCount) {
            runCatching { node.getChild(i) }.getOrNull()?.let { children.add(it) }
        }
        children.forEach { appendNode(sb, it, depth + 1) }
        children.forEach { runCatching { it.recycle() } }
    }

    /** Typealias for the duck-typed screenshot capability of the service. */
    interface OmniA11yLike {
        suspend fun screenshot(): ByteArray?
    }

    companion object {
        private const val TAG = "A11yExecutor"
    }
}
