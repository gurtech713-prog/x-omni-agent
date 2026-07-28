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

    // S-L4: reuse a single GestureManager per bound service instance so the
    // totalGestures / successfulGestures / cancelledGestures / timedOutGestures
    // counters accumulate across dispatches instead of resetting to zero on
    // every tap/swipe. Re-creates the manager only when the underlying service
    // reference changes (connect/disconnect cycle).
    @Volatile private var gmForService: AccessibilityService? = null
    @Volatile private var gmCache: GestureManager? = null

    private fun gestureManager(svc: AccessibilityService): GestureManager {
        val cached = gmCache
        if (cached != null && gmForService === svc) return cached
        val gm = GestureManager(svc, policy = AccessibilityRetryPolicy.Default)
        gmForService = svc
        gmCache = gm
        return gm
    }

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
        // S-L5: removed the redundant `s !is AccessibilityService` check —
        // svc() already returns AccessibilityService?, so a non-null s is
        // guaranteed to be an AccessibilityService. The check was dead code.
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
            // S-M6: honor the retry policy's backoff schedule instead of a
            // flat 50ms — matches GestureManager's behavior and lets the
            // root recover from a transient null (common mid-animation).
            delay(policy.delayForAttempt(attempt))
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
        // Pre-action: dismiss dialogs / keyboard that would intercept — but
        // only when they would actually block this specific action (M-02).
        clearBlockingOverlays(svc, action)

        return when (action) {
            is DeviceAction.Tap -> {
                val start = System.currentTimeMillis()
                // S-L4: reuse the cached GestureManager instead of building a
                // fresh one per dispatch — otherwise the per-instance gesture
                // stats reset to zero on every tap/swipe.
                val gm = gestureManager(svc)
                val ok = gm.tap(action.x, action.y)
                metrics.recordTap(System.currentTimeMillis() - start, ok)
                if (ok) waitForStabilization()
                ok
            }
            is DeviceAction.Swipe -> {
                val start = System.currentTimeMillis()
                val gm = gestureManager(svc)
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
    private fun clearBlockingOverlays(svc: AccessibilityService, action: DeviceAction) {
        // Type actions target the focused field, which is often inside a
        // dialog. Pressing BACK first would dismiss the dialog the agent is
        // typing into, so never clear overlays for Type (M-02).
        if (action is DeviceAction.Type) return

        val state = windowTracker.current

        // For a tap/swipe aimed AT a visible dialog (e.g. "OK"/"Delete"),
        // dismissing the dialog first makes the gesture land on the app
        // behind it. When a dialog is showing it owns the active window, so
        // the active root's screen bounds are the dialog's bounds; only
        // dismiss when the gesture lands outside them.
        if (state.isDialogVisible && gestureInsideActiveWindow(svc, action)) {
            return
        }

        if (state.isDialogVisible || state.isKeyboardVisible) {
            val ok = runCatching {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }.getOrDefault(false)
            if (ok) {
                // S-H7: a single BACK press almost always dismisses only the
                // topmost layer (keyboard first, then dialog underneath).
                // Clearing both flags here desyncs the tracker from reality —
                // the next snapshot would still see the dialog and re-trigger
                // dismissal. Clear only the topmost layer; if both are visible
                // the next dispatch() call will handle the dialog.
                if (state.isKeyboardVisible) {
                    diagnostics.recordKeyboardDismissal()
                    windowTracker.clearKeyboard()
                    // Don't clear dialog — it may still be visible underneath.
                } else if (state.isDialogVisible) {
                    diagnostics.recordDialogDismissal()
                    windowTracker.clearDialog()
                }
            }
        }
    }

    /**
     * True if [action] is a tap/swipe whose target coordinates fall inside the
     * active window's bounds. When a dialog is visible it owns the active
     * window, so this effectively tests whether the gesture targets the dialog
     * (and therefore must NOT be dismissed first). See M-02.
     */
    private fun gestureInsideActiveWindow(svc: AccessibilityService, action: DeviceAction): Boolean {
        val (x, y) = when (action) {
            is DeviceAction.Tap -> action.x to action.y
            is DeviceAction.Swipe -> action.x1 to action.y1
            else -> return false
        }
        return runCatching {
            val root = svc.rootInActiveWindow ?: return@runCatching false
            try {
                val bounds = android.graphics.Rect()
                root.getBoundsInScreen(bounds)
                bounds.contains(x, y)
            } finally {
                runCatching { root.recycle() }
            }
        }.getOrDefault(false)
    }

    /**
     * Wait for the screen to stabilize after an action.
     *
     * Polls the accessibility tree fingerprint every 100ms; once two
     * consecutive reads match (or after 1.5s), returns. This prevents the
     * next snapshot from capturing a mid-animation state.
     */
    private suspend fun waitForStabilization() {
        // S-M5: cap at 1500ms to match the docstring above (was 600ms).
        val cap = 1500L
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

    /**
     * Process-level cache of launchable app label -> package name, built lazily
     * with a single [android.content.pm.PackageManager.queryIntentActivities]
     * call (ACTION_MAIN + CATEGORY_LAUNCHER). Avoids 150+ per-app PackageManager
     * IPCs on the launch fallback path (H-05).
     */
    @Volatile
    private var launchableAppsCache: Map<String, String>? = null

    private fun launchableApps(svc: AccessibilityService): Map<String, String> {
        launchableAppsCache?.let { return it }
        val pm = svc.packageManager
        val map = runCatching {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    launcherIntent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
            val result = HashMap<String, String>(resolveInfos.size)
            for (info in resolveInfos) {
                val label = runCatching { info.loadLabel(pm).toString().lowercase() }.getOrNull() ?: continue
                val pkgName = info.activityInfo?.packageName ?: continue
                result.putIfAbsent(label, pkgName)
            }
            result
        }.getOrDefault(emptyMap())
        launchableAppsCache = map
        return map
    }

    private fun launchPackage(svc: AccessibilityService, packageNameOrAppName: String): Boolean {
        return try {
            val pm = svc.packageManager
            val cleanedInput = packageNameOrAppName.trim().trim('"', '\'').lowercase()

            // Map common app names to popular package names if passed by friendly name.
            // CRITICAL FIX (agent not performing tasks): expanded the knownMap with
            // multiple camera package names (different OEMs use different ones) and
            // more app aliases. The previous map only had "com.android.camera" for
            // camera — but Samsung uses "com.sec.android.app.camera", Pixel uses
            // "com.google.android.GoogleCamera", MIUI uses "com.android.camera",
            // etc. If the package wasn't found, launch() returned false and the
            // agent reported "error" with no diagnostic. Now we try multiple
            // package names for camera and fall back to launchableApps search.
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
                "calculator" to "com.google.android.calculator",
                "clock" to "com.google.android.deskclock",
                "photos" to "com.google.android.apps.photos",
                "gallery" to "com.google.android.apps.photos",
                // Camera — multiple OEM package names. The first one that
                // resolves via getLaunchIntentForPackage wins.
                "camera" to "com.android.camera",
                "google camera" to "com.google.android.GoogleCamera",
                // Note: Samsung's camera package is handled below via the
                // multi-try list since "com.sec.android.app.camera" won't
                // resolve on non-Samsung devices.
            )

            // Camera has multiple possible package names across OEMs. Try them
            // all when the user asks for "camera".
            val cameraPackages = listOf(
                "com.android.camera",
                "com.google.android.GoogleCamera",
                "com.sec.android.app.camera",          // Samsung
                "com.miui.camera",                      // Xiaomi MIUI
                "org.lineageos.camera",                 // LineageOS
                "com.android.camera2",                  // AOSP camera2
                "com.oppo.camera",                      // OPPO
                "com.coloros.camera",                   // ColorOS (Realme/OPPO)
            )

            val pkg = knownMap[cleanedInput] ?: packageNameOrAppName.trim().trim('"', '\'')

            // Try the resolved package first.
            var intent = pm.getLaunchIntentForPackage(pkg)

            // If camera and the first package didn't resolve, try the other
            // OEM camera packages.
            if (intent == null && cleanedInput == "camera") {
                for (cameraPkg in cameraPackages) {
                    intent = pm.getLaunchIntentForPackage(cameraPkg)
                    if (intent != null) break
                }
            }

            // If getLaunchIntentForPackage returned null and input looks like an
            // app label, search launchable apps via a single cached query
            // (ACTION_MAIN + CATEGORY_LAUNCHER) instead of iterating every
            // installed app with a per-app IPC (H-05).
            if (intent == null && !cleanedInput.contains('.')) {
                val apps = launchableApps(svc)
                val matchPkg = apps[cleanedInput]
                    ?: apps.entries.firstOrNull { (label, _) -> label.contains(cleanedInput) }?.value
                if (matchPkg != null) {
                    intent = pm.getLaunchIntentForPackage(matchPkg)
                }
            }

            if (intent == null) {
                Log.w(TAG, "launch($packageNameOrAppName) failed: no launch intent found (tried pkg='$pkg')")
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
        val pkg = runCatching { node.packageName?.toString() }.getOrNull().orEmpty()
        val id = if (rawId != null) logger.rebindRef(rawId, pkg) ?: rawId else ""
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
        // S-M8: cap the number of children expanded per node to prevent
        // pathological trees (e.g. a RecyclerView with 10k items) from
        // blowing up the snapshot. Matches OmniAccessibilityService's caps.
        val childCap = if (depth < 10) 200 else if (depth < 20) 100 else 50
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        val limit = minOf(childCount, childCap)
        val children = ArrayList<AccessibilityNodeInfo>(limit)
        for (i in 0 until limit) {
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
