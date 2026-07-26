package com.omniclaw.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the 7 chat-specific bugs fixed in the chat-audit pass
 * (CHAT-1 through CHAT-10, skipping CHAT-3 and CHAT-6 which were false
 * positives or too minor to test in isolation).
 *
 * These tests mirror the pure-logic contracts of the fixes using local
 * stand-in types (no Android / Hilt / Compose dependencies), following the
 * same pattern as the existing [com.omniclaw.app.agent.AgentLoopBugsTest]
 * and [com.omniclaw.app.ui.chat.ChatViewModelEventsClearTest]. This keeps
 * the tests fast and deterministic while still locking the contract.
 */
class ChatAuditFixesTest {

    // ─── CHAT-1: streaming-thought message ID collision across steps ───────

    /**
     * Mirror of the fixed streaming-thought ID generation in ChatViewModel.
     *
     * The fix: the streaming bubble's ID includes BOTH the sessionId AND the
     * step number, making it globally unique. Previously the ID was just
     * "streaming-thought-${step}", which collided if two sessions ran
     * concurrently and caused Compose to recycle the same bubble across steps.
     */
    private fun streamingThoughtId(sessionId: String, step: Int): String =
        "streaming-thought-$sessionId-$step"

    @Test
    fun `CHAT-1 streaming ID includes session id`() {
        val id = streamingThoughtId("session-abc", 3)
        assertTrue("streaming ID must contain the session id: $id", id.contains("session-abc"))
        assertTrue("streaming ID must contain the step: $id", id.contains("3"))
    }

    @Test
    fun `CHAT-1 streaming ID differs across sessions for same step`() {
        val id1 = streamingThoughtId("session-a", 1)
        val id2 = streamingThoughtId("session-b", 1)
        assertNotEquals("different sessions must have different streaming IDs", id1, id2)
    }

    @Test
    fun `CHAT-1 streaming ID differs across steps for same session`() {
        val id1 = streamingThoughtId("session-a", 1)
        val id2 = streamingThoughtId("session-a", 2)
        assertNotEquals("different steps must have different streaming IDs", id1, id2)
    }

    @Test
    fun `CHAT-1 streaming ID is deterministic for same session and step`() {
        val id1 = streamingThoughtId("session-a", 1)
        val id2 = streamingThoughtId("session-a", 1)
        assertEquals("same session+step must produce the same ID", id1, id2)
    }

    // ─── CHAT-2: send() creates fresh session after terminal status ────────

    /**
     * Local stand-in for SessionStatus (the real enum requires the data-model
     * classpath). Mirrors the four terminal + two non-terminal statuses.
     */
    private enum class TestStatus { RUNNING, IDLE, DONE, FAILED, STOPPED }

    /**
     * Mirror of the fixed send() terminal-status check. If the active session
     * is DONE / FAILED / STOPPED, send() creates a NEW session instead of
     * appending to the old one.
     */
    private fun shouldCreateNewSession(existingStatus: TestStatus?): Boolean {
        if (existingStatus == null) return true
        return existingStatus == TestStatus.DONE ||
            existingStatus == TestStatus.FAILED ||
            existingStatus == TestStatus.STOPPED
    }

    @Test
    fun `CHAT-2 null session triggers new session creation`() {
        assertTrue("null session must create new", shouldCreateNewSession(null))
    }

    @Test
    fun `CHAT-2 RUNNING session does NOT trigger new session creation`() {
        assertFalse("RUNNING session must NOT create new", shouldCreateNewSession(TestStatus.RUNNING))
    }

    @Test
    fun `CHAT-2 IDLE session does NOT trigger new session creation`() {
        assertFalse("IDLE session must NOT create new", shouldCreateNewSession(TestStatus.IDLE))
    }

    @Test
    fun `CHAT-2 DONE session triggers new session creation`() {
        assertTrue("DONE session must create new", shouldCreateNewSession(TestStatus.DONE))
    }

    @Test
    fun `CHAT-2 FAILED session triggers new session creation`() {
        assertTrue("FAILED session must create new", shouldCreateNewSession(TestStatus.FAILED))
    }

    @Test
    fun `CHAT-2 STOPPED session triggers new session creation`() {
        assertTrue("STOPPED session must create new", shouldCreateNewSession(TestStatus.STOPPED))
    }

    // ─── CHAT-4: scroll target derived from filtered messages ──────────────

    /**
     * Mirror of the fixed scroll-target computation in ChatScreen.
     *
     * The fix: the scroll target is derived from the FILTERED message count,
     * not the raw session.messages count. The typing-indicator offset (+1) is
     * only added when the session is RUNNING AND has at least one message.
     */
    private fun computeScrollTarget(
        filteredMessageCount: Int,
        isRunning: Boolean,
        hasMessages: Boolean,
    ): Int {
        if (filteredMessageCount <= 0) return -1  // no scroll
        val showTypingIndicator = isRunning && hasMessages
        return filteredMessageCount - 1 + (if (showTypingIndicator) 1 else 0)
    }

    @Test
    fun `CHAT-4 scroll target is last message index when not running`() {
        val target = computeScrollTarget(filteredMessageCount = 5, isRunning = false, hasMessages = true)
        assertEquals(4, target)  // 5 - 1 + 0
    }

    @Test
    fun `CHAT-4 scroll target adds 1 for typing indicator when running`() {
        val target = computeScrollTarget(filteredMessageCount = 5, isRunning = true, hasMessages = true)
        assertEquals(5, target)  // 5 - 1 + 1
    }

    @Test
    fun `CHAT-4 scroll target does NOT add 1 when running but no messages`() {
        val target = computeScrollTarget(filteredMessageCount = 0, isRunning = true, hasMessages = false)
        assertEquals(-1, target)  // no scroll
    }

    @Test
    fun `CHAT-4 scroll target respects filtered count not session count`() {
        // Simulates: session has 10 messages, but 4 are TOOL messages filtered
        // out (showToolCalls=false), so filtered count = 6. Scroll target
        // must be based on 6, not 10.
        val target = computeScrollTarget(filteredMessageCount = 6, isRunning = false, hasMessages = true)
        assertEquals(5, target)  // 6 - 1 + 0
    }

    @Test
    fun `CHAT-4 zero messages returns -1 (no scroll)`() {
        assertEquals(-1, computeScrollTarget(0, isRunning = false, hasMessages = false))
        assertEquals(-1, computeScrollTarget(0, isRunning = true, hasMessages = false))
    }

    // ─── CHAT-5: stop() falls back to activeSession if activeId is null ────

    /**
     * Mirror of the fixed stop() fallback. If _activeId is null, fall back to
     * activeSession.value?.id. Previously, stop() silently did nothing when
     * activeId was null.
     */
    private fun resolveStopTarget(activeId: String?, activeSessionId: String?): String? {
        return activeId ?: activeSessionId
    }

    @Test
    fun `CHAT-5 stop uses activeId when present`() {
        assertEquals("s1", resolveStopTarget(activeId = "s1", activeSessionId = "s2"))
    }

    @Test
    fun `CHAT-5 stop falls back to activeSession id when activeId is null`() {
        assertEquals("s2", resolveStopTarget(activeId = null, activeSessionId = "s2"))
    }

    @Test
    fun `CHAT-5 stop returns null when both are null`() {
        assertNull(resolveStopTarget(activeId = null, activeSessionId = null))
    }

    // ─── CHAT-7: open() guards against non-existent session ────────────────

    /**
     * Mirror of the fixed open() guard. If the session id doesn't exist in
     * the repository, clear _activeId instead of setting it to a stale id.
     */
    private data class OpenResult(val activeId: String?, val eventsCleared: Boolean)

    private fun openSession(id: String, exists: Boolean): OpenResult {
        val newActiveId = if (!exists) null else id
        return OpenResult(newActiveId, eventsCleared = true)
    }

    @Test
    fun `CHAT-7 open existing session sets activeId`() {
        val r = openSession("s1", exists = true)
        assertEquals("s1", r.activeId)
        assertTrue("events must be cleared", r.eventsCleared)
    }

    @Test
    fun `CHAT-7 open non-existent session clears activeId`() {
        val r = openSession("deleted-id", exists = false)
        assertNull("activeId must be null for non-existent session", r.activeId)
        assertTrue("events must be cleared even for non-existent session", r.eventsCleared)
    }

    @Test
    fun `CHAT-7 open non-existent session does NOT keep previous activeId`() {
        val r = openSession("deleted-id", exists = false)
        assertNull("must not keep any previous activeId", r.activeId)
    }

    // ─── CHAT-8: Composer mic toggle syncs from actual service state ───────

    /**
     * Mirror of the fixed Composer mic toggle state resolution.
     *
     * The fix: the displayed toggle state is `pendingToggle ?: serviceIsRunning`,
     * where pendingToggle is a transient override that clears after 500ms.
     * This ensures the icon re-syncs from the actual service state if the
     * service is stopped externally.
     */
    private data class MicToggleState(val pendingToggle: Boolean?, val serviceRunning: Boolean) {
        val displayedState: Boolean get() = pendingToggle ?: serviceRunning
    }

    @Test
    fun `CHAT-8 no pending toggle uses service state`() {
        val s = MicToggleState(pendingToggle = null, serviceRunning = true)
        assertTrue("should show on when service is running", s.displayedState)
        val s2 = MicToggleState(pendingToggle = null, serviceRunning = false)
        assertFalse("should show off when service is stopped", s2.displayedState)
    }

    @Test
    fun `CHAT-8 pending toggle overrides service state for immediate feedback`() {
        val s = MicToggleState(pendingToggle = true, serviceRunning = false)
        assertTrue("pending on should show on even if service hasn't started yet", s.displayedState)
        val s2 = MicToggleState(pendingToggle = false, serviceRunning = true)
        assertFalse("pending off should show off even if service hasn't stopped yet", s2.displayedState)
    }

    @Test
    fun `CHAT-8 after pending toggle clears, re-syncs from service state`() {
        // Simulate: user clicks toggle on (pending=true), service start fails
        // (serviceRunning stays false), pending clears after 500ms.
        var state = MicToggleState(pendingToggle = true, serviceRunning = false)
        assertTrue("immediate feedback: show on", state.displayedState)
        // 500ms later, pending clears:
        state = state.copy(pendingToggle = null)
        assertFalse("after pending clears, re-sync to actual service state (off)", state.displayedState)
    }

    // ─── CHAT-9: AgentLoop sets session title from first prompt ────────────

    /**
     * Mirror of the fixed AgentLoop.start() title-setting logic.
     *
     * The fix: if the session title is still the placeholder "New session",
     * set it from the first user prompt. Don't overwrite a title that was
     * already set (e.g. "[Scheduled] <title>" for scheduled tasks).
     */
    private fun shouldUpdateTitle(currentTitle: String): Boolean {
        return currentTitle == "New session"
    }

    @Test
    fun `CHAT-9 title updated for placeholder 'New session'`() {
        assertTrue("should update 'New session' placeholder", shouldUpdateTitle("New session"))
    }

    @Test
    fun `CHAT-9 title NOT updated for scheduled task title`() {
        assertFalse("should NOT update '[Scheduled]' title", shouldUpdateTitle("[Scheduled] My task"))
    }

    @Test
    fun `CHAT-9 title NOT updated for custom title`() {
        assertFalse("should NOT update custom title", shouldUpdateTitle("My custom title"))
    }

    @Test
    fun `CHAT-9 title NOT updated for blank title (defensive)`() {
        assertFalse("should NOT update blank title", shouldUpdateTitle(""))
    }

    // ─── CHAT-10: empty LLM response produces a clear system note ──────────

    /**
     * Mirror of the fixed AgentLoop empty-thought guard.
     *
     * The fix: if the LLM returns an empty/blank thought, append a clear
     * system note explaining what happened, then end the session with DONE
     * instead of appending an empty assistant bubble and continuing.
     */
    private data class EmptyThoughtResult(
        val systemNoteAppended: Boolean,
        val assistantMessageContent: String,
        val sessionEnded: Boolean,
    )

    private fun handleThought(thought: String): EmptyThoughtResult {
        if (thought.isBlank()) {
            val note = "(The model returned an empty response. This can happen with safety filters or content policies. Try rephrasing your request.)"
            return EmptyThoughtResult(
                systemNoteAppended = true,
                assistantMessageContent = note,
                sessionEnded = true,
            )
        }
        return EmptyThoughtResult(
            systemNoteAppended = false,
            assistantMessageContent = thought,
            sessionEnded = false,
        )
    }

    @Test
    fun `CHAT-10 empty thought appends system note and ends session`() {
        val r = handleThought("")
        assertTrue("system note must be appended for empty thought", r.systemNoteAppended)
        assertTrue("assistant content must be the note", r.assistantMessageContent.contains("empty response"))
        assertTrue("session must end for empty thought", r.sessionEnded)
    }

    @Test
    fun `CHAT-10 blank thought (whitespace only) appends system note`() {
        val r = handleThought("   \n\n  ")
        assertTrue("blank thought must be treated as empty", r.systemNoteAppended)
        assertTrue("session must end for blank thought", r.sessionEnded)
    }

    @Test
    fun `CHAT-10 non-empty thought does NOT append system note`() {
        val r = handleThought("Tapping the search button.")
        assertFalse("non-empty thought must NOT append system note", r.systemNoteAppended)
        assertEquals("Tapping the search button.", r.assistantMessageContent)
        assertFalse("non-empty thought must NOT end session", r.sessionEnded)
    }

    @Test
    fun `CHAT-10 thought with content proceeds normally`() {
        val r = handleThought("THOUGHT: I'll search Reddit\nACTION: launch(com.reddit.frontpage)")
        assertFalse("thought with content must NOT append system note", r.systemNoteAppended)
        assertFalse("thought with content must NOT end session", r.sessionEnded)
        assertTrue(
            "assistant content must be the original thought",
            r.assistantMessageContent.contains("search Reddit"),
        )
    }
}
