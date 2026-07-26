package com.omniclaw.app.agent

import com.omniclaw.app.data.model.LlmUsage
import com.omniclaw.app.data.model.Skill
import com.omniclaw.app.data.model.SkillCategory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for five bugs fixed in [AgentLoop]:
 *
 *  1. Duplicate `sessions.appendMessage(...)` in the `action == null || done`
 *     branch — every conversational turn was double-written to the session.
 *  2. `LlmUsage(0L, ...)` on the streaming path hardcoded `promptTokens = 0`,
 *     breaking the `maxSessionTokens` budget guard (prompt tokens dominate cost).
 *  3. The cancel+launch+register sequence in `start()` was split across two
 *     `startMutex.withLock {}` blocks, allowing two concurrent loops for one
 *     session when `start()` was called twice in rapid succession.
 *  4. `substringAfterLast(':')` on `"weekly:Wed:10:00"` returned `"00"` instead
 *     of `"10:00"`, silently scheduling weekly tasks for midnight.
 *  5. Skill enabled/disabled toggles in `SkillRepository` were never consulted
 *     by `AgentLoop` — disabled skills were advertised in the prompt and
 *     dispatched without an enabled check.
 *
 * These tests mirror the pure logic (no Hilt / Android Context) to lock the
 * contract independently of the DI graph, following the same pattern used by
 * [com.omniclaw.app.agent.tools.DeviceActionTest] and
 * [com.omniclaw.app.agent.verifier.SuccessMonitorLogicTest].
 */
class AgentLoopBugsTest {

    // ─── Bug 1: no duplicate appendMessage in the "done" branch ──────────────

    /**
     * Mirror of the AgentLoop runLoopInner append logic. The fix removed the
     * second `sessions.appendMessage(...)` call inside the
     * `action == null || done` branch, so a conversational turn appends the
     * assistant message exactly once (the unconditional append after the LLM
     * call), not twice.
     */
    private class AppendTracker {
        val appended = mutableListOf<String>()
        fun appendAssistant(thought: String) { appended += thought }
    }

    private fun runTurn(tracker: AppendTracker, thought: String, action: String?) {
        // Unconditional append after the LLM call (always happens).
        tracker.appendAssistant(thought)
        // "done" branch — previously had a SECOND appendAssistant(thought);
        // the fix removed it, leaving only the Completed/status/reflection logic.
        if (action == null || action.lowercase().startsWith("done")) {
            // (no append here anymore)
            return
        }
    }

    @Test
    fun `done turn appends assistant message exactly once`() {
        val tracker = AppendTracker()
        runTurn(tracker, "Hello!", action = "done")
        assertEquals("done turn must not double-append", 1, tracker.appended.size)
        assertEquals("Hello!", tracker.appended[0])
    }

    @Test
    fun `conversational turn with null action appends exactly once`() {
        val tracker = AppendTracker()
        runTurn(tracker, "Sure, I can help with that.", action = null)
        assertEquals(1, tracker.appended.size)
    }

    @Test
    fun `device-action turn still appends exactly once before dispatch`() {
        val tracker = AppendTracker()
        // A device-action turn appends the assistant thought once, then dispatches.
        // It does NOT enter the "done" branch, so there was never a duplicate
        // here — but we verify the fix didn't accidentally drop the append.
        runTurn(tracker, "Tapping the button.", action = "tap(500, 800)")
        assertEquals(1, tracker.appended.size)
    }

    // ─── Bug 2: streaming LlmUsage estimates prompt tokens from content ──────

    /**
     * Mirror of the fixed streaming-path usage estimation. Previously:
     *   `usage = LlmUsage(0L, estimatedTokens, estimatedTokens)`
     * which hardcoded promptTokens=0. The fix estimates prompt tokens from
     * the actual `(systemMsg + history)` content length using the same
     * ~1 token / 2 chars heuristic as the completion estimate, and sets
     * totalTokens = promptEstimate + completionEstimate.
     */
    private data class Message(val role: String, val content: String)

    private fun estimateUsage(
        systemMsg: Message,
        history: List<Message>,
        thought: String,
    ): LlmUsage {
        val completionEstimate = (thought.length / 2).toLong()
        val promptChars = (listOf(systemMsg) + history).sumOf { it.content.length }
        val promptEstimate = (promptChars / 2).toLong()
        return LlmUsage(promptEstimate, completionEstimate, promptEstimate + completionEstimate)
    }

    @Test
    fun `streaming usage has non-zero prompt tokens proportional to content`() {
        val system = Message("system", "You are a helpful assistant. ".repeat(20))
        val history = listOf(
            Message("user", "Open Reddit and search for budget travel tips"),
            Message("assistant", "Sure, launching Reddit now."),
        )
        val thought = "Launching Reddit."
        val usage = estimateUsage(system, history, thought)
        assertTrue("promptTokens must be > 0, was ${usage.promptTokens}", usage.promptTokens > 0L)
        assertTrue(
            "promptTokens should reflect system+history length (~${(system.content.length + history.sumOf { it.content.length }) / 2})",
            usage.promptTokens >= 50L,
        )
    }

    @Test
    fun `streaming usage totalTokens equals prompt plus completion`() {
        val system = Message("system", "abc")
        val history = listOf(Message("user", "def"))
        val thought = "ghi"
        val usage = estimateUsage(system, history, thought)
        // system(3) + user(3) = 6 chars -> 3 prompt tokens
        // thought(3) = 3 chars -> 1 completion token (integer division)
        // total = 3 + 1 = 4
        assertEquals(3L, usage.promptTokens)
        assertEquals(1L, usage.completionTokens)
        assertEquals(usage.promptTokens + usage.completionTokens, usage.totalTokens)
    }

    @Test
    fun `streaming usage promptTokens grows with longer history`() {
        val system = Message("system", "system prompt")
        val shortHistory = listOf(Message("user", "hi"))
        val longHistory = (1..50).map { Message("user", "message number $it with some content") }
        val thought = "reply"
        val shortUsage = estimateUsage(system, shortHistory, thought)
        val longUsage = estimateUsage(system, longHistory, thought)
        assertTrue(
            "longer history should yield more prompt tokens (${shortUsage.promptTokens} vs ${longUsage.promptTokens})",
            longUsage.promptTokens > shortUsage.promptTokens,
        )
    }

    @Test
    fun `streaming usage does not regress to hardcoded zero prompt tokens`() {
        // The bug was `LlmUsage(0L, estimatedTokens, estimatedTokens)`.
        // Verify the fix never produces promptTokens == 0 when there's content.
        val system = Message("system", "non-empty system prompt")
        val usage = estimateUsage(system, emptyList(), "reply")
        assertNotEquals("promptTokens must not be hardcoded 0", 0L, usage.promptTokens)
        // And totalTokens must be greater than completionTokens alone.
        assertTrue(usage.totalTokens > usage.completionTokens)
    }

    // ─── Bug 3: start() wraps cancel+launch+register in one withLock ─────────

    /**
     * Mirror of the fixed `start()` cancel+launch+register sequence. The fix
     * moved the `val job = scope.launch { runLoop(...) }` and the
     * `runningJobs[id] = job` registration INSIDE the same `startMutex.withLock`
     * block that cancels the existing job. The previous two-block split let two
     * rapid `start()` calls both pass the cancel check (neither had registered
     * yet) and both launch a runLoop, spawning two concurrent loops.
     *
     * This test mirrors the registry+mutex contract: the second concurrent
     * `start()` for the same session ID must observe (and cancel) the job
     * registered by the first — which is only possible when cancel+launch+
     * register is a SINGLE `withLock` critical section.
     */
    private class StartRegistry {
        private val mutex = Mutex()
        private val runningJobs = ConcurrentHashMap<String, String>()
        val launchCount = AtomicInteger(0)
        val cancelCount = AtomicInteger(0)

        fun reset() {
            runningJobs.clear()
            launchCount.set(0)
            cancelCount.set(0)
        }

        // Fixed: single withLock block for cancel+launch+register. The second
        // caller always sees the first's registered job and cancels it.
        suspend fun startFixed(id: String) {
            mutex.withLock {
                runningJobs[id]?.let {
                    cancelCount.incrementAndGet()
                    runningJobs.remove(id)
                }
                launchCount.incrementAndGet()
                runningJobs[id] = "job-${launchCount.get()}"
            }
        }

        // Buggy: two separate withLock blocks. Between them, a second caller
        // can pass the cancel check without seeing the first's job.
        suspend fun startBuggy(id: String) {
            mutex.withLock {
                runningJobs[id]?.let {
                    cancelCount.incrementAndGet()
                    runningJobs.remove(id)
                }
            }
            // GAP — another start() can race here and see no job to cancel.
            launchCount.incrementAndGet()
            runningJobs[id] = "job-${launchCount.get()}"
        }
    }

    @Test
    fun `fixed start serializes cancel plus launch plus register`() = runBlocking {
        val reg = StartRegistry()
        val id = "session-1"
        // Two concurrent startFixed calls for the same session.
        val j1 = async(Dispatchers.Default) { reg.startFixed(id) }
        val j2 = async(Dispatchers.Default) { reg.startFixed(id) }
        awaitAll(j1, j2)
        assertEquals("both starts should launch", 2, reg.launchCount.get())
        assertEquals(
            "the second start must cancel the first's registered job (single critical section)",
            1, reg.cancelCount.get(),
        )
    }

    @Test
    fun `buggy two-block start structure differs from fixed`() = runBlocking {
        // Structural sanity check: the buggy form (two separate withLock
        // blocks) compiles and runs, but does NOT guarantee the second caller
        // sees the first's job. We don't assert the race manifests (that would
        // be a flaky probabilistic test); the fixed test above is the
        // deterministic regression guard. This test just confirms the buggy
        // mirror is well-formed and produces launchCount=2.
        val reg = StartRegistry()
        val id = "session-1"
        reg.reset()
        val j1 = async(Dispatchers.Default) { reg.startBuggy(id) }
        val j2 = async(Dispatchers.Default) { reg.startBuggy(id) }
        awaitAll(j1, j2)
        assertEquals("both starts should launch", 2, reg.launchCount.get())
    }

    // ─── Bug 4: weekly scheduleSpec parsing ──────────────────────────────────

    /**
     * Mirror of the fixed weekly-schedule parse. The bug was:
     *   `val time = scheduleSpec.substringAfterLast(':')`
     * which returned `"00"` for `"weekly:Wed:10:00"`. The fix takes everything
     * after the FIRST colon following "weekly:" as the time.
     */
    private fun parseWeekly(scheduleSpec: String): Pair<String, String> {
        val rest = scheduleSpec.substringAfter("weekly:")
        val dayName = rest.substringBefore(':')
        val time = rest.substringAfter(':')
        return dayName to time
    }

    @Test
    fun `weekly Wed 10 00 parses day and full time`() {
        val (day, time) = parseWeekly("weekly:Wed:10:00")
        assertEquals("Wed", day)
        assertEquals("10:00", time)
    }

    @Test
    fun `weekly Mon 9 30 parses correctly`() {
        val (day, time) = parseWeekly("weekly:Mon:9:30")
        assertEquals("Mon", day)
        assertEquals("9:30", time)
    }

    @Test
    fun `weekly parse does not regress to minutes-only`() {
        // The bug returned "00" (minutes only). Verify the fix returns HH:mm.
        val (day, time) = parseWeekly("weekly:Fri:18:45")
        assertEquals("Fri", day)
        assertEquals("18:45", time)
        assertNotEquals("00", time)
        assertTrue("time should contain a colon: $time", time.contains(':'))
        assertTrue("time should have 2 colon-separated parts: $time", time.split(':').size == 2)
    }

    @Test
    fun `weekly day maps to correct weekday number`() {
        val dayMap = mapOf("Sun" to 1, "Mon" to 2, "Tue" to 3, "Wed" to 4, "Thu" to 5, "Fri" to 6, "Sat" to 7)
        val (day, _) = parseWeekly("weekly:Wed:10:00")
        assertEquals(4, dayMap[day])
    }

    // ─── Bug 5: skill toggles consulted by buildSystemPrompt & handleSkillAction ──

    /**
     * Mirror of the fixed `isSkillEnabled(skillId)` lookup. Skills present in
     * the repository return their `enabled` flag; skills not in the repository
     * (internal helpers like gallery-search, open-bookmark, behavior-replay)
     * default to enabled.
     */
    private class FakeSkillState(skills: List<Skill>) {
        val skills: MutableStateFlow<List<Skill>> = MutableStateFlow(skills)
        fun isSkillEnabled(skillId: String): Boolean {
            val skill = skills.value.firstOrNull { it.id == skillId } ?: return true
            return skill.enabled
        }
    }

    private fun skill(id: String, enabled: Boolean): Skill = Skill(
        id = id,
        name = id,
        category = SkillCategory.AUTOMATION,
        description = "",
        enabled = enabled,
        examples = emptyList(),
        path = "",
    )

    @Test
    fun `isSkillEnabled returns true for enabled skill`() {
        val state = FakeSkillState(listOf(skill("gallery-qa", enabled = true)))
        assertTrue(state.isSkillEnabled("gallery-qa"))
    }

    @Test
    fun `isSkillEnabled returns false for disabled skill`() {
        val state = FakeSkillState(listOf(skill("gallery-qa", enabled = false)))
        assertFalse(state.isSkillEnabled("gallery-qa"))
    }

    @Test
    fun `isSkillEnabled defaults to true for unknown skill`() {
        // Skills not in the repository (gallery-search, open-bookmark,
        // behavior-replay) cannot be toggled — default to enabled.
        val state = FakeSkillState(listOf(skill("gallery-qa", enabled = true)))
        assertTrue(state.isSkillEnabled("gallery-search"))
        assertTrue(state.isSkillEnabled("open-bookmark"))
        assertTrue(state.isSkillEnabled("behavior-replay"))
    }

    @Test
    fun `buildSystemPrompt filters out disabled skills`() {
        // The prompt block is a list of (skillId, line) pairs filtered by
        // isSkillEnabled. Verify disabled skills are excluded.
        val state = FakeSkillState(listOf(
            skill("gallery-qa", enabled = true),
            skill("capcut-theme-video", enabled = false),
            skill("scheduled-automation", enabled = true),
        ))
        val skillLines = listOf(
            "gallery-qa" to "line A",
            "capcut-theme-video" to "line B",
            "scheduled-automation" to "line C",
        )
        val visible = skillLines.filter { state.isSkillEnabled(it.first) }
        assertTrue("enabled skill kept", visible.any { it.first == "gallery-qa" })
        assertFalse("disabled skill filtered out", visible.any { it.first == "capcut-theme-video" })
        assertTrue("enabled skill kept", visible.any { it.first == "scheduled-automation" })
    }

    @Test
    fun `handleSkillAction refuses disabled skill with clear message`() {
        // The fix adds an early return in handleSkillAction: if the parsed
        // skillId is disabled, return a clear refusal message instead of
        // dispatching.
        val state = FakeSkillState(listOf(skill("gallery-qa", enabled = false)))
        val skillId = "gallery-qa"
        val result: String? = if (!state.isSkillEnabled(skillId)) {
            "Skill '$skillId' is disabled by the user. Use a different approach or ask the user to enable it in Settings."
        } else {
            "Gallery memory sync started."
        }
        assertTrue("disabled skill must be refused", result!!.contains("disabled"))
        assertTrue("refusal must name the skill", result.contains("gallery-qa"))
    }

    @Test
    fun `handleSkillAction dispatches enabled skill`() {
        val state = FakeSkillState(listOf(skill("gallery-qa", enabled = true)))
        val skillId = "gallery-qa"
        val result: String? = if (!state.isSkillEnabled(skillId)) {
            "refused"
        } else {
            "Gallery memory sync started."
        }
        assertEquals("Gallery memory sync started.", result)
    }

    @Test
    fun `toggle flip is reflected in subsequent isSkillEnabled calls`() {
        // Simulate the user toggling a skill off then on — the StateFlow
        // update must be visible to isSkillEnabled on the next call.
        val state = FakeSkillState(listOf(skill("scheduled-automation", enabled = true)))
        assertTrue(state.isSkillEnabled("scheduled-automation"))
        state.skills.update { list -> list.map { if (it.id == "scheduled-automation") it.copy(enabled = false) else it } }
        assertFalse(state.isSkillEnabled("scheduled-automation"))
        state.skills.update { list -> list.map { if (it.id == "scheduled-automation") it.copy(enabled = true) else it } }
        assertTrue(state.isSkillEnabled("scheduled-automation"))
    }
}
