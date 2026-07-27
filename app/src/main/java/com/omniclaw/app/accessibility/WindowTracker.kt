package com.omniclaw.app.accessibility

import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicReference

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the current window state — which package is in the foreground,
 * whether a dialog or keyboard is visible, and whether the notification
 * shade is pulled down.
 *
 * The [OmniAccessibilityService] feeds events to this tracker via
 * [onEvent]; the [AccessibilityExecutor] reads the current state via
 * [current] before dispatching actions so it can apply window-specific
 * strategies (e.g. press BACK to dismiss a dialog before tapping).
 *
 * Thread safety: all mutable state is backed by [AtomicReference], so reads
 * from the agent loop (Dispatchers.Default) and writes from the a11y service
 * main looper are race-free.
 */
@Singleton
class WindowTracker @Inject constructor() {

    data class WindowState(
        val foregroundPackage: String?,
        val foregroundActivity: String?,
        val isDialogVisible: Boolean,
        val isKeyboardVisible: Boolean,
        val isNotificationShadeVisible: Boolean,
        val isPictureInPictureVisible: Boolean,
        val activeWindowTitles: List<String>,
        val lastEventTime: Long,
        val lastEventType: Int,
    ) {
        companion object {
            val EMPTY = WindowState(
                foregroundPackage = null,
                foregroundActivity = null,
                isDialogVisible = false,
                isKeyboardVisible = false,
                isNotificationShadeVisible = false,
                isPictureInPictureVisible = false,
                activeWindowTitles = emptyList(),
                lastEventTime = 0L,
                lastEventType = 0,
            )
        }
    }

    private val stateRef = AtomicReference(WindowState.EMPTY)

    /** Current window state — safe to read from any thread. */
    val current: WindowState get() = stateRef.get()

    /**
     * Called by the accessibility service on every [AccessibilityEvent].
     * Extracts window state and atomically updates [current].
     */
    fun onEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        val className = event.className?.toString()
        val type = event.eventType
        val now = System.currentTimeMillis()

        // Heuristic detection of special window states.
        val isKeyboard = type == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            pkg != null && pkg in KEYBOARD_PACKAGES
        val isShade = pkg == SYSTEM_UI_PACKAGE && (
            className?.contains("StatusBar", ignoreCase = true) == true ||
            className?.contains("NotificationShade", ignoreCase = true) == true ||
            className?.contains("Shade", ignoreCase = true) == true
        )
        val isDialog = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            (className?.endsWith("Dialog") == true || className?.endsWith("AlertDialog") == true)
        // PiP detection: AccessibilityEvent.getParcelableData() is deprecated since
        // API 33 and was rarely populated, so the old heuristic never fired. Instead,
        // detect the PiP window via its class name / window title on the event itself
        // (the a11y service surfaces PiP frames whose class/title contains
        // "PictureInPicture"; see AccessibilityService.windows / TYPE_PICTURE_IN_PICTURE).
        val isPip = type == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            (className?.contains("PictureInPicture", ignoreCase = true) == true ||
                event.text?.any { it?.contains("PictureInPicture", ignoreCase = true) == true } == true)

        val titles = mutableListOf<String>()
        runCatching {
            event.text?.forEach { t -> if (t.isNotBlank()) titles.add(t.toString()) }
        }

        val newState = WindowState(
            foregroundPackage = pkg ?: current.foregroundPackage,
            foregroundActivity = className,
            isDialogVisible = isDialog || current.isDialogVisible,
            isKeyboardVisible = isKeyboard,
            isNotificationShadeVisible = isShade,
            isPictureInPictureVisible = isPip,
            activeWindowTitles = titles,
            lastEventTime = now,
            lastEventType = type,
        )
        stateRef.set(newState)

        // Dialog / keyboard states are sticky — we set them true on detection
        // but they should clear when a subsequent event indicates the window
        // closed. The heuristic below clears them when we see a new
        // TYPE_WINDOW_STATE_CHANGED to a non-dialog, non-shade class.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !isDialog && !isShade) {
            stateRef.updateAndGet { it.copy(isDialogVisible = false, isNotificationShadeVisible = false) }
        }
    }

    /** Manually clear the keyboard-visible flag (e.g. after a BACK press). */
    fun clearKeyboard() {
        stateRef.updateAndGet { it.copy(isKeyboardVisible = false) }
    }

    /** Manually clear the dialog-visible flag. */
    fun clearDialog() {
        stateRef.updateAndGet { it.copy(isDialogVisible = false) }
    }

    /** True if [packageName] is the current foreground package. */
    fun isForeground(packageName: String): Boolean =
        current.foregroundPackage == packageName

    /** True if any special window (dialog, keyboard, shade, PiP) is visible. */
    fun hasOverlayWindow(): Boolean {
        val s = current
        return s.isDialogVisible || s.isKeyboardVisible ||
            s.isNotificationShadeVisible || s.isPictureInPictureVisible
    }

    companion object {
        private val KEYBOARD_PACKAGES = setOf(
            "com.google.android.inputmethod.latin",  // Gboard
            "com.android.inputmethod.latin",
            "com.iflytek.inputmethod",
            "com.sohu.inputmethod.sogou",
            "com.baidu.input",
            "com.tencent.qqpinyin",
        )
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
