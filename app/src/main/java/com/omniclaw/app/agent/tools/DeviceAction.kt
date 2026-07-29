package com.omniclaw.app.agent.tools

/**
 * Atomic Android device actions the agent can dispatch.
 *
 * Each variant maps 1:1 to a path in [com.omniclaw.app.accessibility.AccessibilityExecutor.dispatch].
 *
 * [Scroll] is a high-level, direction-based scroll that computes its swipe
 * coordinates from the real screen dimensions at dispatch time. This is
 * preferred over [Swipe] for scrolling because the LLM can say "scroll down"
 * without knowing the exact pixel coordinates of the viewport — which makes
 * it robust across screen sizes, orientations, and multi-window layouts.
 */
sealed class DeviceAction {
    data object NoOp : DeviceAction()
    data class Tap(val x: Int, val y: Int) : DeviceAction()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : DeviceAction()

    /**
     * Drag (long-press then move). Used for drag-and-drop scenarios where a
     * simple swipe isn't enough — the element needs to be "picked up" first.
     * Dispatches a long-press at (x1,y1), holds, moves to (x2,y2), then releases.
     */
    data class Drag(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : DeviceAction()

    /**
     * Directional scroll. [direction] is one of "up"/"down"/"left"/"right"
     * (case-insensitive). [amount] is a fraction of the screen dimension to
     * travel (0.1–0.5); default 0.35 scrolls ~one third of the viewport.
     */
    data class Scroll(val direction: String, val amount: Float = 0.35f) : DeviceAction()

    data class Type(val text: String) : DeviceAction()
    data class Launch(val packageName: String) : DeviceAction()
    data object Back : DeviceAction()
    data object Home : DeviceAction()
    data object Screenshot : DeviceAction()
}
