package com.omniclaw.app.agent.verifier

import com.omniclaw.app.agent.learning.EpisodeRecorder
import com.omniclaw.app.data.model.ToolCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SuccessMonitor]'s drift-detection and consecutive-failure
 * tracking logic.
 *
 * SuccessMonitor depends on DeviceScheduler (for snapshots) and
 * EpisodeRecorder (for fingerprinting), both of which require Android Context.
 * We mirror the pure verification logic here to lock the contract: an action
 * is "failed" if (a) the call itself failed, (b) the snapshot contains error
 * markers, or (c) the screen fingerprint hasn't changed across 3+ recent
 * snapshots (drift = stuck).
 */
class SuccessMonitorLogicTest {

    private val recorder = EpisodeRecorder()

    /** Mirror of SuccessMonitor.verifyLast drift-detection core. */
    private class State(
        val recentSnapshots: ArrayDeque<String> = ArrayDeque(),
        var consecutiveFailures: Int = 0,
    )

    private fun verify(
        state: State,
        call: ToolCall,
        snapshot: String,
    ): Boolean {
        if (!call.ok) {
            state.consecutiveFailures++
            return false
        }
        if (snapshot.contains("Error launching", ignoreCase = true) ||
            snapshot.contains("not responding", ignoreCase = true) ||
            snapshot.contains("has stopped", ignoreCase = true)
        ) {
            state.consecutiveFailures++
            return false
        }
        val fp = recorder.fingerprint(snapshot)
        val identicalCount = state.recentSnapshots.count { it == fp }
        state.recentSnapshots.addLast(fp)
        if (state.recentSnapshots.size > 6) state.recentSnapshots.removeFirst()
        if (identicalCount >= 2) {
            state.consecutiveFailures++
            return false
        }
        if (call.name.startsWith("tap", ignoreCase = true) &&
            (snapshot.isBlank() || snapshot.contains("not connected", ignoreCase = true))
        ) {
            state.consecutiveFailures++
            return false
        }
        state.consecutiveFailures = 0
        return true
    }

    @Test
    fun `failed call returns false`() {
        val state = State()
        val call = ToolCall("c1", "tap(1,2)", "", "error", ok = false, durationMs = 1)
        assertFalse(verify(state, call, "some snapshot"))
        assertTrue(state.consecutiveFailures == 1)
    }

    @Test
    fun `snapshot with error launching returns false`() {
        val state = State()
        val call = ToolCall("c1", "launch(pkg)", "", "ok", ok = true, durationMs = 1)
        assertFalse(verify(state, call, "Error launching package"))
        assertTrue(state.consecutiveFailures == 1)
    }

    @Test
    fun `snapshot with not responding returns false`() {
        val state = State()
        val call = ToolCall("c1", "tap(1,2)", "", "ok", ok = true, durationMs = 1)
        assertFalse(verify(state, call, "App is not responding"))
    }

    @Test
    fun `successful action on fresh screen returns true`() {
        val state = State()
        val call = ToolCall("c1", "tap(1,2)", "", "ok", ok = true, durationMs = 1)
        assertTrue(verify(state, call, "Screen A with button X"))
        assertTrue(state.consecutiveFailures == 0)
    }

    @Test
    fun `drift detection flags after 3 identical fingerprints`() {
        val state = State()
        val call = ToolCall("c1", "tap(1,2)", "", "ok", ok = true, durationMs = 1)
        val screen = "Same screen every time"
        // First call: fresh, true
        assertTrue(verify(state, call, screen))
        // Second call: 1 identical, still true (threshold is >= 2)
        assertTrue(verify(state, call, screen))
        // Third call: 2 identical now, false
        assertFalse(verify(state, call, screen))
    }

    @Test
    fun `tap on disconnected service returns false`() {
        val state = State()
        val call = ToolCall("c1", "tap(1,2)", "", "ok", ok = true, durationMs = 1)
        assertFalse(verify(state, call, "(accessibility service not connected)"))
    }

    @Test
    fun `consecutive failures reset on success`() {
        val state = State()
        // Two failures
        verify(state, ToolCall("c1", "tap(1,2)", "", "err", ok = false, durationMs = 1), "screen")
        verify(state, ToolCall("c2", "tap(1,2)", "", "err", ok = false, durationMs = 1), "screen")
        assertTrue(state.consecutiveFailures == 2)
        // One success resets the counter
        verify(state, ToolCall("c3", "tap(3,4)", "", "ok", ok = true, durationMs = 1), "totally new screen X")
        assertTrue(state.consecutiveFailures == 0)
    }

    @Test
    fun `recent snapshots window is bounded at 6`() {
        val state = State()
        val call = ToolCall("c1", "tap(1,2)", "", "ok", ok = true, durationMs = 1)
        // Push 10 distinct screens — only the last 6 should be retained.
        repeat(10) { i ->
            verify(state, call, "screen $i with content")
        }
        assertTrue(state.recentSnapshots.size <= 6)
    }
}
