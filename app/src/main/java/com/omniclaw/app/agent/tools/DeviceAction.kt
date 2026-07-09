package com.omniclaw.app.agent.tools

sealed class DeviceAction {
    data object NoOp : DeviceAction()
    data class Tap(val x: Int, val y: Int) : DeviceAction()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : DeviceAction()
    data class Type(val text: String) : DeviceAction()
    data class Launch(val packageName: String) : DeviceAction()
    data object Back : DeviceAction()
    data object Home : DeviceAction()
    data object Screenshot : DeviceAction()
}
