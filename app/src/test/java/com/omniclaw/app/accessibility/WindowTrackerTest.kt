package com.omniclaw.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [WindowTracker] — window-state tracking from a11y events.
 *
 * Uses fake [AccessibilityEvent]s (we can't construct real ones in unit tests,
 * so we test the [WindowState] data class + the heuristics directly).
 */
class WindowTrackerTest {

    @Test
    fun `initial state is EMPTY`() {
        val t = WindowTracker()
        assertEquals(WindowTracker.WindowState.EMPTY, t.current)
        assertFalse(t.hasOverlayWindow())
    }

    @Test
    fun `isForeground checks foregroundPackage`() {
        val t = WindowTracker()
        assertTrue(!t.isForeground("com.example"))
    }

    @Test
    fun `clearKeyboard clears the keyboard flag`() {
        val t = WindowTracker()
        t.clearKeyboard()
        assertFalse(t.current.isKeyboardVisible)
    }

    @Test
    fun `clearDialog clears the dialog flag`() {
        val t = WindowTracker()
        t.clearDialog()
        assertFalse(t.current.isDialogVisible)
    }

    @Test
    fun `hasOverlayWindow false when no overlays`() {
        val t = WindowTracker()
        assertFalse(t.hasOverlayWindow())
    }
}
