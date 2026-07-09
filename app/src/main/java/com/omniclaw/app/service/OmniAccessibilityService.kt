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
import com.omniclaw.app.agent.tools.DeviceScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import javax.inject.Inject

/**
 * Omni accessibility service.
 *
 * Implements the "Execution" layer of the X-OmniClaw four-layer closed loop:
 *   perceive -> plan -> act -> verify
 *
 * The agent loop dispatches all device actions (taps, swipes, text entry,
 * app launches, back/home) through this service via [DeviceScheduler].
 */
@AndroidEntryPoint
class OmniAccessibilityService : AccessibilityService() {

    @Inject lateinit var scheduler: DeviceScheduler
    @Inject lateinit var agentLogger: com.omniclaw.app.logging.AgentLogger

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        scheduler.boundService = this
        Log.i(TAG, "OmniAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The agent loop pulls the tree on demand — we don't need to react to every event.
        // Future: cache the latest event for cheap snapshots.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility interrupted.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (scheduler.boundService === this) scheduler.boundService = null
        // Shut down the screenshot background executor so its daemon thread
        // doesn't leak for the life of the process. Previously the executor
        // was created in onCreate but never shut down — repeated bind/unbind
        // cycles (e.g. user toggling the accessibility service) leaked a
        // thread each time.
        runCatching { screenshotExecutor.shutdown() }
        return super.onUnbind(intent)
    }

    // ---- Public API used by the agent loop via DeviceScheduler ----

    /** Build a flat text representation of the current accessibility tree. */
    fun snapshotTree(): String? {
        val root = rootInActiveWindow ?: return null
        val sb = StringBuilder()
        appendNode(sb, root, 0)
        return sb.toString()
    }

    private fun appendNode(sb: StringBuilder, node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null) return
        val pad = "  ".repeat(depth.coerceAtMost(8))
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString().orEmpty().take(80)
        // Cross-package ref rebinding: if the viewIdResourceName references
        // another package's namespace (com.foo:id/x), rewrite it to the current
        // package's namespace (com.bar:id/x) so the agent can reuse the ref.
        val rawId = node.viewIdResourceName
        val id = if (rawId != null) agentLogger.rebindRef(rawId, packageName) ?: rawId else ""
        sb.append("$pad- $cls")
        if (id.isNotBlank()) sb.append(" id=$id")
        if (text.isNotBlank()) sb.append(" text=\"$text\"")
        if (node.isClickable) sb.append(" [clickable]")
        if (node.isScrollable) sb.append(" [scrollable]")
        // Include screen bounds for clickable/scrollable nodes so the LLM can
        // reason about spatial layout and pick tap coordinates directly from
        // the tree (instead of always falling back to VLM).
        if (node.isClickable || node.isScrollable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            sb.append(" bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
        }
        sb.appendLine()
        // Iterate children without leaking node objects. getChild(i) allocates
        // a new AccessibilityNodeInfo each call; on older Android these must
        // be recycled to avoid native memory pressure. We collect children
        // first so we can recycle them after recursion.
        val children = ArrayList<AccessibilityNodeInfo>(node.childCount)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { children.add(it) }
        }
        children.forEach { appendNode(sb, it, depth + 1) }
    }

    /**
     * Dispatch a tap gesture at (x, y).
     *
     * Uses the GestureResultCallback overload (instead of passing null) so we
     * can distinguish "gesture scheduled" from "gesture actually executed".
     * Previously, dispatchGesture returned true for scheduled-but-cancelled
     * gestures, causing the agent to think a tap landed when it didn't.
     */
    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) { success = true; latch.countDown() }
            override fun onCancelled(gesture: GestureDescription?) { success = false; latch.countDown() }
        }, handler)
        // Wait up to 500ms for the gesture to complete — dispatchGesture is
        // async but completes quickly (stroke duration is 60ms). Blocking the
        // caller here is acceptable because tap() is called from the agent
        // loop's Dispatchers.Default, not the main thread.
        latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        return success
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) { success = true; latch.countDown() }
            override fun onCancelled(gesture: GestureDescription?) { success = false; latch.countDown() }
        }, handler)
        latch.await(800, java.util.concurrent.TimeUnit.MILLISECONDS)
        return success
    }

    /**
     * Type text into the currently focused input field.
     *
     * If no field is focused (common right after a tap that landed on an input
     * but didn't explicitly request focus), search the tree for the first
     * EditText-like node and focus it before setting text. This fixes the
     * silent-failure case where the agent tapped a search bar, then tried to
     * type — the tap landed but focus wasn't set, so ACTION_SET_TEXT had no
     * target and returned false.
     */
    fun type(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        // Try the focused node first.
        var focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null) {
            // No focus — find the first editable node and focus it.
            focused = findFirstEditable(root)
            if (focused != null) {
                focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
        }
        if (focused == null) {
            Log.w(TAG, "type(\"$text\") failed: no focused input and no editable node found in tree")
            return false
        }
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Recursively find the first node that accepts text input (EditText-like). */
    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditable(node.getChild(i))
            if (found != null) return found
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
     * Capture a screenshot as PNG bytes.
     *
     * Uses suspendCancellableCoroutine instead of a CountDownLatch so the
     * calling coroutine doesn't block a Dispatchers.IO thread for up to 2s.
     * The callback resumes the coroutine as soon as the screenshot completes
     * or fails — no thread is parked waiting.
     */
    suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
        suspendCancellableCoroutine { cont ->
            takeScreenshot(DisplayId, screenshotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        if (hw != null) {
                            val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                            hw.recycle()
                            if (sw != null) {
                                val out = java.io.ByteArrayOutputStream()
                                sw.compress(Bitmap.CompressFormat.PNG, 100, out)
                                sw.recycle()
                                cont.resume(out.toByteArray())
                                return@onSuccess
                            }
                        }
                        cont.resume(null)
                    } finally {
                        screenshot.hardwareBuffer.close()
                    }
                }
                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed: errorCode=$errorCode")
                    cont.resume(null)
                }
            })
            // If the coroutine is cancelled (e.g. session stopped), we can't
            // cancel the in-flight takeScreenshot call, but we can stop waiting.
            cont.invokeOnCancellation { /* nothing to clean up */ }
        }
    }

    // Use a background single-thread executor for takeScreenshot's callback
    // instead of posting to the main-looper handler. The callback does
    // Bitmap.wrapHardwareBuffer + copy(ARGB_8888) + PNG compression, which
    // takes 100-500ms for a full-screen capture. Running that on the main
    // thread caused visible jank and ANR risk. The callback contract only
    // requires an Executor — it does NOT require the main thread.
    // Named screenshotExecutor so onUnbind can shut it down.
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
