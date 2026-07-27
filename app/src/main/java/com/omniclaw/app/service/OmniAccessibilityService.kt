package com.omniclaw.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import com.omniclaw.app.accessibility.AccessibilityDiagnostics
import com.omniclaw.app.accessibility.AccessibilityExecutor
import com.omniclaw.app.accessibility.WindowTracker
import com.omniclaw.app.agent.tools.DeviceScheduler
import com.omniclaw.app.logging.AgentLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import javax.inject.Inject

/**
 * Omni accessibility service.
 *
 * Implements the "Execution" layer of the X-OmniClaw four-layer closed loop:
 *   perceive -> plan -> act -> verify
 *
 * The agent loop dispatches all device actions (taps, swipes, text entry,
 * app launches, back/home) through this service via [DeviceScheduler],
 * which delegates to [AccessibilityExecutor].
 *
 * The service itself is intentionally thin — it owns the platform lifecycle
 * (connect/disconnect/event delivery) and exposes raw capabilities (root
 * node, screenshot). All higher-level logic (gesture retry, node search,
 * window tracking, metrics) lives in the executor + its collaborators.
 */
@AndroidEntryPoint
class OmniAccessibilityService : AccessibilityService(), AccessibilityExecutor.OmniA11yLike {

    @Inject lateinit var scheduler: DeviceScheduler
    @Inject lateinit var agentLogger: com.omniclaw.app.logging.AgentLogger
    @Inject lateinit var diagnostics: AccessibilityDiagnostics
    @Inject lateinit var windowTracker: WindowTracker

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        scheduler.boundService = this
        diagnostics.setState(AccessibilityDiagnostics.ServiceState.CONNECTED)
        Log.i(TAG, "OmniAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Feed every event to the window tracker so it can maintain an
        // accurate picture of dialogs, keyboard, shade, and foreground package.
        windowTracker.onEvent(event)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility interrupted.")
        diagnostics.log("lifecycle", "onInterrupt() called", AccessibilityDiagnostics.DiagnosticEvent.Severity.WARN)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (scheduler.boundService === this) scheduler.boundService = null
        diagnostics.setState(AccessibilityDiagnostics.ServiceState.DISCONNECTED)
        // Shut down the screenshot background executor so its daemon thread
        // doesn't leak for the life of the process.
        runCatching { screenshotExecutor.shutdown() }
        return super.onUnbind(intent)
    }

    // ---- Raw capabilities exposed to AccessibilityExecutor ----
    // The executor calls these via the OmniA11yLike interface + direct casts.

    /** Build a flat text representation of the current accessibility tree. */
    fun snapshotTree(): String? {
        val root = rootInActiveWindow ?: return null
        // Note: the executor owns recycling for snapshots taken via its own
        // snapshot() method. This legacy entry point is kept for backward
        // compatibility with BehaviorRecorder / SuccessMonitor which call
        // it directly. We recycle the root after building the string.
        try {
            val sb = StringBuilder()
            appendNode(sb, root, 0)
            return sb.toString()
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * O(1) stabilization fingerprint for the post-action polling loop.
     *
     * Returns "packageName:childCount" from the accessibility root WITHOUT
     * building the full tree string. Previous approach called
     * snapshotTree().take(80) which serialized the entire tree (10-50KB)
     * then discarded all but the first 80 characters — repeated 12+ times
     * per step in the stabilization loop.
     *
     * This method reads root.packageName and root.childCount only — O(1)
     * with zero string serialization. Returns empty string if no root is
     * available (service disconnected or initializing).
     */
    fun cheapStabilizationFingerprint(): String {
        val root = rootInActiveWindow ?: return ""
        try {
            val pkg = root.packageName?.toString() ?: ""
            val count = root.childCount
            return "$pkg:$count"
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun appendNode(sb: StringBuilder, node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 50) return
        
        // OPTIMIZATION: Prune non-interactive nodes early to reduce tree size
        // and improve performance on complex layouts.
        val isInteractive = runCatching {
            node.isClickable || node.isScrollable || node.isEditable ||
            node.isFocusable || node.isLongClickable
        }.getOrDefault(false)
        
        // Skip deep non-interactive nodes (depth > 30) unless they're interactive
        if (depth > 30 && !isInteractive) return
        
        // Stale-node guard: every AccessibilityNodeInfo field access can throw
        // NPE / IllegalStateException if the underlying window changes mid-
        // traversal (common during animations). Wrap each access in runCatching
        // and bail out gracefully if the node is gone.
        val pad = "  ".repeat(depth.coerceAtMost(8))
        val cls = runCatching { node.className?.toString()?.substringAfterLast('.') }.getOrNull() ?: "?"
        val rawText = runCatching { node.text?.toString().orEmpty() }.getOrNull().orEmpty()
        // PRIVACY: Filter sensitive fields before including in accessibility tree.
        // Password inputs, OTP fields, and other security-sensitive UI elements
        // should not be exposed to the agent or logged.
        val text = filterSensitiveText(rawText, node)
        val rawId = runCatching { node.viewIdResourceName }.getOrNull()
        val id = if (rawId != null) agentLogger.rebindRef(rawId, packageName) ?: rawId else ""
        sb.append("$pad- $cls")
        if (id.isNotBlank()) sb.append(" id=$id")
        if (text.isNotBlank()) sb.append(" text=\"$text\"")
        val clickable = runCatching { node.isClickable }.getOrDefault(false)
        val scrollable = runCatching { node.isScrollable }.getOrDefault(false)
        if (clickable) sb.append(" [clickable]")
        if (scrollable) sb.append(" [scrollable]")
        if (clickable || scrollable) {
            val rect = Rect()
            runCatching { node.getBoundsInScreen(rect) }
            sb.append(" bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
        }
        sb.appendLine()
        
        // OPTIMIZATION: Adaptive child capping based on depth and interactivity
        // shallower nodes get full treatment, deeper nodes get capped earlier
        val childCap = if (depth < 10) 200 else if (depth < 20) 100 else 50
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        val children = ArrayList<AccessibilityNodeInfo>(minOf(childCount, childCap))
        for (i in 0 until childCount) {
            if (children.size >= childCap) {
                sb.append("$pad  ... (${childCount - childCap} more children truncated)\n")
                break
            }
            runCatching { node.getChild(i) }.getOrNull()?.let { children.add(it) }
        }
        children.forEach { appendNode(sb, it, depth + 1) }
        children.forEach { runCatching { it.recycle() } }
    }

    /**
     * Filter sensitive text from accessibility nodes.
     *
     * Detects password fields, OTP inputs, and other security-sensitive UI
     * elements by checking:
     * 1. Node class name (e.g., "AppCompatEditText" with password input type)
     * 2. View ID resource names containing "password", "pin", "otp", "secret"
     * 3. Content description hints like "password", "confirm", "verification"
     *
     * Returns "[FILTERED]" for sensitive content, preserving the tree structure
     * without exposing credentials to the agent or logs.
     */
    private fun filterSensitiveText(rawText: String, node: AccessibilityNodeInfo): String {
        if (rawText.isBlank()) return rawText

        try {
            // Check if this is a password/secret input field
            val isPasswordField = runCatching {
                val className = node.className?.toString().orEmpty()
                val viewId = node.viewIdResourceName.orEmpty()
                val contentDesc = node.contentDescription?.toString().orEmpty()

                // Direct password/secret indicators in view ID or content description
                val sensitiveKeywords = listOf("password", "passwd", "pin", "otp",
                    "secret", "credential", "auth", "token", "verification", "confirm")

                val hasSensitiveId = sensitiveKeywords.any { keyword ->
                    viewId.contains(keyword, ignoreCase = true) ||
                    contentDesc.lowercase().contains(keyword)
                }

                // EditText with password input type
                val isEditText = className.contains("EditText", ignoreCase = true) ||
                    className.contains("TextInput", ignoreCase = true)

                val isPasswordField = isEditText && (
                    hasSensitiveId ||
                    runCatching { node.isPassword }.getOrDefault(false)
                )

                isPasswordField
            }.getOrNull() ?: false

            if (isPasswordField) {
                return "[FILTERED]"
            }
        } catch (_: Exception) {
            // If we can't determine sensitivity, preserve the text
        }

        return rawText.take(80)
    }

    /**
     * Suspend tap — uses suspendCancellableCoroutine to avoid blocking the
     * calling thread. The GestureResultCallback resumes the continuation.
     */
    suspend fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gesture: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gesture: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, handler)
        }
    }

    /**
     * Suspend swipe — uses suspendCancellableCoroutine to avoid blocking the
     * calling thread. The GestureResultCallback resumes the continuation.
     */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCancellableCoroutine { cont ->
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gesture: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onCancelled(gesture: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, handler)
        }
    }

    fun type(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null) {
            focused = findFirstEditable(root)
            if (focused != null) {
                focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
        }
        if (focused == null) {
            runCatching { root.recycle() }
            return false
        }
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        runCatching { focused.recycle() }
        runCatching { root.recycle() }
        return ok
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 50) return null
        if (runCatching { node.isEditable }.getOrDefault(false)) return node
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val found = findFirstEditable(child, depth + 1)
            if (found != null) {
                // Recycle the child if it's not the found node.
                if (found !== child) runCatching { child.recycle() }
                return found
            }
            runCatching { child.recycle() }
        }
        return null
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun launch(packageName: String): Boolean {
        return try {
            val pm = packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "launch($packageName) failed: ${e.message}")
            false
        }
    }

    /**
     * Capture a screenshot as PNG bytes (suspend, via the a11y API).
     * Implements [AccessibilityExecutor.OmniA11yLike].
     */
    override suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
        withTimeout(3_000) {
            suspendCancellableCoroutine { cont ->
                takeScreenshot(DisplayId, screenshotExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            // Guard: caller may have cancelled the coroutine before
                            // the callback fires. Resume only if still active.
                            if (!cont.isActive) {
                                return
                            }
                            val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            if (hw != null) {
                                val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                                if (sw != null) {
                                    val out = java.io.ByteArrayOutputStream()
                                    sw.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    sw.recycle()
                                    if (cont.isActive) cont.resume(out.toByteArray())
                                    return@onSuccess
                                }
                            }
                            if (cont.isActive) cont.resume(null)
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed: errorCode=$errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                })
                cont.invokeOnCancellation { /* nothing to clean up — the callback handles resources */ }
            }
        }
    }

    private val screenshotExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "OmniA11yScreenshot").apply { isDaemon = true }
    }

    companion object {
        private const val TAG = "OmniA11y"
        private const val DisplayId = 0 // default display

        /** True if the user has enabled this service in system settings. */
        fun isEnabled(ctx: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val target = ctx.packageName + "/" + OmniAccessibilityService::class.java.name
            return enabled.split(":").any { it.equals(target, ignoreCase = true) }
        }

        fun openSettings(ctx: Context) {
            val i = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        }
    }
}
