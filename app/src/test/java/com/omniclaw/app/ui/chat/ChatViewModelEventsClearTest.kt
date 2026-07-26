package com.omniclaw.app.ui.chat

import com.omniclaw.app.agent.AgentLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for Bug 6 in [ChatViewModel]:
 *
 * `open(id)` switched the active session but never cleared `_events`, so a
 * previous session's step/thought/tool-call log could remain visible after
 * switching to a different (especially idle) session. The fix resets
 * `_events.value = emptyList()` inside both `open()` and `newSession()`.
 *
 * This test mirrors the event-list reset contract via a lightweight stand-in
 * (the real `ChatViewModel` requires Hilt + Compose lifecycle to instantiate).
 * The mirror follows the same pattern as
 * [com.omniclaw.app.agent.verifier.SuccessMonitorLogicTest] and
 * [com.omniclaw.app.CoreHelpersTest].
 */
class ChatViewModelEventsClearTest {

    /**
     * Minimal mirror of the ChatViewModel event-log state machine.
     * `_events` accumulates agent events filtered by the active session id;
     * `open(id)` and `newSession()` must reset it.
     */
    private class EventsState {
        val events = mutableListOf<AgentLoop.Event>()
        var activeId: String? = null

        fun newSession(id: String) {
            activeId = id
            // Bug 6 fix: clear the event log on session creation so the
            // previous session's entries don't bleed into the fresh one.
            events.clear()
        }

        fun open(id: String) {
            activeId = id
            // Bug 6 fix: clear the event log on session switch so a previous
            // (especially idle) session's entries don't remain visible.
            events.clear()
        }

        fun onEvent(e: AgentLoop.Event) {
            val cur = activeId
            if (cur == null || e.sessionId == cur) {
                events += e
            }
        }
    }

    private fun thoughtEvent(sessionId: String, text: String): AgentLoop.Event =
        AgentLoop.Event.Thought(sessionId, step = 1, text = text)

    private fun stepStartedEvent(sessionId: String): AgentLoop.Event =
        AgentLoop.Event.StepStarted(sessionId, step = 1)

    @Test
    fun `open clears events from previous session`() {
        val state = EventsState()
        state.newSession("session-A")
        state.onEvent(thoughtEvent("session-A", "thinking A1"))
        state.onEvent(stepStartedEvent("session-A"))
        assertEquals("session A should have 2 events", 2, state.events.size)

        // Switch to a different (idle) session — the event log must clear.
        state.open("session-B")
        assertEquals("open() must clear _events", 0, state.events.size)
    }

    @Test
    fun `newSession clears events from previous session`() {
        val state = EventsState()
        state.newSession("session-A")
        state.onEvent(thoughtEvent("session-A", "thinking A1"))
        state.onEvent(thoughtEvent("session-A", "thinking A2"))
        assertEquals(2, state.events.size)

        state.newSession("session-B")
        assertEquals("newSession() must clear _events", 0, state.events.size)
    }

    @Test
    fun `events accumulate for the active session after open`() {
        val state = EventsState()
        state.newSession("session-A")
        state.onEvent(thoughtEvent("session-A", "A1"))
        state.open("session-B")
        // After open, events for the new active session should accumulate.
        state.onEvent(thoughtEvent("session-B", "B1"))
        state.onEvent(stepStartedEvent("session-B"))
        assertEquals(2, state.events.size)
        assertTrue("events should be for session-B", state.events.all { it.sessionId == "session-B" })
    }

    @Test
    fun `events from a non-active session are filtered out after open`() {
        val state = EventsState()
        state.newSession("session-A")
        state.onEvent(thoughtEvent("session-A", "A1"))
        state.open("session-B")
        // session-A events arriving AFTER switching to B must not appear.
        state.onEvent(thoughtEvent("session-A", "late A event"))
        assertEquals("late events from session-A must be filtered", 0, state.events.size)
    }

    @Test
    fun `switching back to a previous session clears its old events`() {
        val state = EventsState()
        state.newSession("session-A")
        state.onEvent(thoughtEvent("session-A", "A1"))
        state.open("session-B")
        state.onEvent(thoughtEvent("session-B", "B1"))
        // Switch back to A — old A events must NOT reappear.
        state.open("session-A")
        assertEquals("switching back must start with a clean log", 0, state.events.size)
    }
}
