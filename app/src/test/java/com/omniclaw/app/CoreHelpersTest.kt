package com.omniclaw.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the maskSecret helper (mirrors the private function in
 * SettingsViewModel). Tested here via a local copy to avoid Hilt setup.
 */
class MaskSecretTest {

    private fun maskSecret(s: String): String {
        if (s.length <= 8) return if (s.isBlank()) "" else "*****"
        val first = s.take(4)
        val last = s.takeLast(4)
        val stars = "*".repeat((s.length - 8).coerceAtMost(20))
        return "$first$stars$last"
    }

    @Test fun `blank string returns empty`() {
        assertEquals("", maskSecret(""))
    }

    @Test fun `short string returns stars`() {
        assertEquals("*****", maskSecret("short"))
        assertEquals("*****", maskSecret("12345678")) // exactly 8 chars
    }

    @Test fun `long string masks middle`() {
        val result = maskSecret("sk-abc123xyz789def456")
        assertTrue(result.startsWith("sk-a"))
        assertTrue(result.endsWith("f456"))
        assertTrue(result.contains("*"))
    }

    @Test fun `very long string caps stars at 20`() {
        val long = "sk-" + "a".repeat(100)
        val result = maskSecret(long)
        val starCount = result.count { it == '*' }
        assertEquals(20, starCount)
    }
}

/**
 * Unit tests for the unmaskedOr helper — ensures masked values from export
 * don't overwrite real keys on import.
 */
class UnmaskedOrTest {

    private fun unmaskedOr(imported: String?, current: String): String {
        if (imported.isNullOrBlank()) return current
        if (imported.contains('*')) return current
        return imported
    }

    @Test fun `null imported returns current`() {
        assertEquals("current-key", unmaskedOr(null, "current-key"))
    }

    @Test fun `blank imported returns current`() {
        assertEquals("current-key", unmaskedOr("", "current-key"))
    }

    @Test fun `masked imported returns current`() {
        assertEquals("current-key", unmaskedOr("sk-a*****z789", "current-key"))
    }

    @Test fun `real imported returns imported`() {
        assertEquals("new-key", unmaskedOr("new-key", "current-key"))
    }
}

/**
 * Unit tests for AgentLoop.parseDeviceAction (mirrored here as a standalone
 * function to avoid Hilt setup). Verifies that action strings are parsed
 * into the correct DeviceAction sealed-class variants.
 */
class ParseDeviceActionTest {

    private sealed class DeviceAction {
        data class Tap(val x: Int, val y: Int) : DeviceAction()
        data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : DeviceAction()
        data class Scroll(val direction: String, val amount: Float = 0.35f) : DeviceAction()
        data class Type(val text: String) : DeviceAction()
        data class Launch(val packageName: String) : DeviceAction()
        data object Back : DeviceAction()
        data object Home : DeviceAction()
        data object Screenshot : DeviceAction()
        data object NoOp : DeviceAction()
    }

    private fun parseDeviceAction(action: String): DeviceAction {
        val s = action.trim()
        return when {
            s.startsWith("tap", ignoreCase = true) -> {
                val m = Regex("(?i)tap\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").find(s)
                val x = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val y = m?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                DeviceAction.Tap(x, y)
            }
            s.startsWith("swipe", ignoreCase = true) -> {
                val m = Regex("(?i)swipe\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").find(s)
                DeviceAction.Swipe(
                    m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0,
                    m?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0,
                    m?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 0,
                    m?.groupValues?.getOrNull(4)?.toIntOrNull() ?: 0,
                )
            }
            s.startsWith("scroll", ignoreCase = true) -> {
                val m = Regex("(?i)scroll\\s*\\(\\s*([a-z]+)\\s*(?:,\\s*([0-9]*\\.?[0-9]+)\\s*)?\\)").find(s)
                val dir = m?.groupValues?.getOrNull(1)?.lowercase()
                val amt = m?.groupValues?.getOrNull(2)?.toFloatOrNull() ?: 0.35f
                when (dir) {
                    "up", "down", "left", "right" -> DeviceAction.Scroll(dir, amt)
                    else -> DeviceAction.NoOp
                }
            }
            s.startsWith("type", ignoreCase = true) -> {
                val m = Regex("(?i)type\\s*\\(\\s*\"(.*)\"\\s*\\)").find(s)
                DeviceAction.Type(m?.groupValues?.getOrNull(1).orEmpty())
            }
            s.startsWith("launch", ignoreCase = true) -> {
                val m = Regex("(?i)launch\\s*\\(\\s*(.+?)\\s*\\)").find(s)
                DeviceAction.Launch(m?.groupValues?.getOrNull(1).orEmpty())
            }
            s.startsWith("back", ignoreCase = true) -> DeviceAction.Back
            s.startsWith("home", ignoreCase = true) -> DeviceAction.Home
            s.startsWith("screenshot", ignoreCase = true) -> DeviceAction.Screenshot
            else -> DeviceAction.NoOp
        }
    }

    @Test fun `tap with coordinates`() {
        val a = parseDeviceAction("tap(100, 200)")
        assertTrue(a is DeviceAction.Tap)
        assertEquals(100, (a as DeviceAction.Tap).x)
        assertEquals(200, a.y)
    }

    @Test fun `tap case insensitive`() {
        assertTrue(parseDeviceAction("TAP(50, 60)") is DeviceAction.Tap)
    }

    @Test fun `tap with spaces`() {
        val a = parseDeviceAction("tap( 100 , 200 )")
        assertTrue(a is DeviceAction.Tap)
    }

    @Test fun `swipe with 4 coordinates`() {
        val a = parseDeviceAction("swipe(100, 200, 300, 400)")
        assertTrue(a is DeviceAction.Swipe)
        val s = a as DeviceAction.Swipe
        assertEquals(100, s.x1); assertEquals(200, s.y1)
        assertEquals(300, s.x2); assertEquals(400, s.y2)
    }

    @Test fun `scroll down parses direction`() {
        val a = parseDeviceAction("scroll(down)")
        assertTrue(a is DeviceAction.Scroll)
        assertEquals("down", (a as DeviceAction.Scroll).direction)
    }

    @Test fun `scroll up with amount parses both`() {
        val a = parseDeviceAction("scroll(up,0.5)")
        assertTrue(a is DeviceAction.Scroll)
        val s = a as DeviceAction.Scroll
        assertEquals("up", s.direction)
        assertEquals(0.5f, s.amount, 0.001f)
    }

    @Test fun `scroll defaults amount when omitted`() {
        val a = parseDeviceAction("scroll(left)")
        assertTrue(a is DeviceAction.Scroll)
        assertEquals(0.35f, (a as DeviceAction.Scroll).amount, 0.001f)
    }

    @Test fun `scroll right case insensitive`() {
        assertTrue(parseDeviceAction("SCROLL(RIGHT)") is DeviceAction.Scroll)
    }

    @Test fun `scroll with invalid direction returns NoOp`() {
        assertTrue(parseDeviceAction("scroll(sideways)") is DeviceAction.NoOp)
    }

    @Test fun `type with quoted text`() {
        val a = parseDeviceAction("type(\"hello world\")")
        assertTrue(a is DeviceAction.Type)
        assertEquals("hello world", (a as DeviceAction.Type).text)
    }

    @Test fun `type with empty quotes`() {
        val a = parseDeviceAction("type(\"\")")
        assertTrue(a is DeviceAction.Type)
        assertEquals("", (a as DeviceAction.Type).text)
    }

    @Test fun `launch with package name`() {
        val a = parseDeviceAction("launch(com.amazon.mShop.android.shopping)")
        assertTrue(a is DeviceAction.Launch)
        assertEquals("com.amazon.mShop.android.shopping", (a as DeviceAction.Launch).packageName)
    }

    @Test fun `back action`() {
        assertTrue(parseDeviceAction("back") is DeviceAction.Back)
    }

    @Test fun `home action`() {
        assertTrue(parseDeviceAction("home") is DeviceAction.Home)
    }

    @Test fun `screenshot action`() {
        assertTrue(parseDeviceAction("screenshot") is DeviceAction.Screenshot)
    }

    @Test fun `unknown action returns NoOp`() {
        assertTrue(parseDeviceAction("foobar(1,2)") is DeviceAction.NoOp)
    }
}

/**
 * Unit tests for AgentLogger.rebindRef — cross-package ref rebinding.
 */
class RebindRefTest {

    private fun rebindRef(ref: String?, currentPackage: String): String? {
        if (ref.isNullOrBlank()) return null
        if (!ref.contains(":id/")) return ref
        val resourceName = ref.substringAfter(":id/")
        return "$currentPackage:id/$resourceName"
    }

    @Test fun `null ref returns null`() {
        assertNull(rebindRef(null, "com.bar"))
    }

    @Test fun `blank ref returns null`() {
        assertNull(rebindRef("", "com.bar"))
    }

    @Test fun `ref without id slash returned as-is`() {
        assertEquals("com.foo:something", rebindRef("com.foo:something", "com.bar"))
    }

    @Test fun `ref rebinding to current package`() {
        assertEquals("com.bar:id/search_btn",
            rebindRef("com.foo:id/search_btn", "com.bar"))
    }

    @Test fun `ref already in current package stays same shape`() {
        assertEquals("com.bar:id/search_btn",
            rebindRef("com.bar:id/search_btn", "com.bar"))
    }
}

/**
 * Unit tests for ScheduledTaskWorker.computeDelayToNext weekday walk.
 * Mirrors the logic to avoid Hilt.
 */
class ComputeDelayToNextTest {

    private fun computeDelayToNext(weekdays: Set<Int>, timeOfDay: String, nowHour: Int, nowMinute: Int, nowDayOfWeek: Int): Long {
        val parts = timeOfDay.split(":")
        val targetHour = parts.getOrNull(0)?.toIntOrNull() ?: 9
        val targetMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        // Simulate: compute ms to next matching weekday
        val nowMinutes = nowHour * 60 + nowMinute
        val targetMinutes = targetHour * 60 + targetMinute
        val days = if (weekdays.isEmpty()) setOf(1, 2, 3, 4, 5, 6, 7) else weekdays
        var dayOffset = 0
        while (dayOffset < 8) {
            val checkDay = ((nowDayOfWeek - 1 + dayOffset) % 7) + 1
            val isToday = dayOffset == 0
            if (checkDay in days) {
                if (!isToday || targetMinutes > nowMinutes) {
                    return (dayOffset * 24 * 60 + (targetMinutes - nowMinutes)) * 60_000L
                }
            }
            dayOffset++
        }
        return -1L // no match (shouldn't happen with 7-day fallback)
    }

    @Test fun `same day target time in future returns small delay`() {
        val delay = computeDelayToNext(setOf(4), "10:00", 9, 0, 4) // Wed, target 10:00, now 9:00
        assertTrue("delay should be 1 hour = 3600000ms", delay == 3_600_000L)
    }

    @Test fun `same day target time passed rolls to next week`() {
        val delay = computeDelayToNext(setOf(4), "9:00", 10, 0, 4) // Wed, target 9:00, now 10:00
        assertTrue("delay should be ~7 days", delay >= 6 * 24 * 60 * 60_000L)
    }

    @Test fun `non-target day walks forward`() {
        val delay = computeDelayToNext(setOf(6), "10:00", 9, 0, 4) // Wed, target Sat
        assertTrue("delay should be ~3 days", delay >= 2 * 24 * 60 * 60_000L && delay <= 3 * 24 * 60 * 60_000L)
    }

    @Test fun `empty weekdays matches any day`() {
        val delay = computeDelayToNext(emptySet(), "10:00", 9, 0, 4)
        assertTrue(delay == 3_600_000L) // 1 hour
    }
}
