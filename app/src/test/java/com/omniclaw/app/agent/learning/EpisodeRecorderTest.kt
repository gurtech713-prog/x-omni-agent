package com.omniclaw.app.agent.learning

import com.omniclaw.app.data.model.Lesson
import com.omniclaw.app.data.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [EpisodeRecorder] — the per-session trajectory recorder that
 * feeds the self-learning reflection loop.
 *
 * These tests cover the pure-logic methods (fingerprint, normalizeAction) and
 * the in-memory episode recording / retrieval lifecycle. They don't test
 * persistence (that's the LearningEngine + LessonDao's job).
 */
class EpisodeRecorderTest {

    private val recorder = EpisodeRecorder()

    @Test
    fun `fingerprint is stable across whitespace changes`() {
        val obs1 = "RecyclerView\n  Button id=foo text=\"OK\" [clickable]"
        val obs2 = "RecyclerView   Button id=foo text=\"OK\" [clickable]"
        val obs3 = "  RecyclerView\n\n  Button id=foo text=\"OK\" [clickable]  "
        val fp1 = recorder.fingerprint(obs1)
        val fp2 = recorder.fingerprint(obs2)
        val fp3 = recorder.fingerprint(obs3)
        assertEquals("whitespace normalization should produce identical fingerprints", fp1, fp2)
        assertEquals("leading/trailing whitespace should not affect fingerprint", fp1, fp3)
    }

    @Test
    fun `fingerprint differs for different content`() {
        val obs1 = "RecyclerView\n  Button id=foo text=\"OK\""
        val obs2 = "RecyclerView\n  Button id=bar text=\"Cancel\""
        assertNotEquals("different content should produce different fingerprints",
            recorder.fingerprint(obs1), recorder.fingerprint(obs2))
    }

    @Test
    fun `fingerprint of empty observation is 'empty'`() {
        assertEquals("empty", recorder.fingerprint(""))
        assertEquals("empty", recorder.fingerprint("   "))
        assertEquals("empty", recorder.fingerprint("\n\n\n"))
    }

    @Test
    fun `fingerprint is truncated to first 200 chars`() {
        val short = "a".repeat(100)
        val long = "a".repeat(100) + "b".repeat(100)
        assertNotEquals(recorder.fingerprint(short), recorder.fingerprint(long))
    }

    @Test
    fun `fingerprint produces 8-char hex strings`() {
        val fp = recorder.fingerprint("Button id=foo text=\"OK\" [clickable]")
        assertEquals(8, fp.length)
        assertTrue("fingerprint should be hex: $fp", fp.all { it in "0123456789abcdef" })
    }

    @Test
    fun `normalizeAction lowercases and strips whitespace`() {
        assertEquals("tap(500,800)", recorder.normalizeAction("TAP(500, 800)"))
        assertEquals("tap(500,800)", recorder.normalizeAction("  tap( 500 , 800 )  "))
        assertEquals("launch(com.foo)", recorder.normalizeAction("Launch(com.foo)"))
        assertEquals("skill:gallery-qa", recorder.normalizeAction("SKILL:gallery-qa"))
        assertEquals("back", recorder.normalizeAction("BACK"))
    }

    @Test
    fun `normalizeAction is idempotent`() {
        val action = "tap(500, 800)"
        val once = recorder.normalizeAction(action)
        val twice = recorder.normalizeAction(once)
        assertEquals(once, twice)
    }

    @Test
    fun `start and getEpisode returns empty list initially`() {
        recorder.start("session-1", "test prompt")
        val episode = recorder.getEpisode("session-1")
        assertTrue("newly started episode should have no steps", episode!!.isEmpty())
    }

    @Test
    fun `getEpisode returns null for unknown session`() {
        assertEquals(null, recorder.getEpisode("nonexistent"))
    }

    @Test
    fun `getUserPrompt returns the prompt passed to start`() {
        recorder.start("session-2", "Open Reddit and search budget travel")
        assertEquals("Open Reddit and search budget travel", recorder.getUserPrompt("session-2"))
    }

    @Test
    fun `recordStep appends to the episode`() {
        recorder.start("session-3", "test")
        val call = ToolCall(
            id = "call-1",
            name = "tap(500, 800)",
            args = "",
            result = "ok",
            ok = true,
            durationMs = 50,
        )
        recorder.recordStep("session-3", stepIndex = 1, observation = "Button", action = "tap(500, 800)", call = call, verifyOk = true)
        val episode = recorder.getEpisode("session-3")!!
        assertEquals(1, episode.size)
        assertEquals("tap(500, 800)", episode[0].action)
        assertEquals(Lesson.LessonOutcome.SUCCESS, episode[0].outcome)
    }

    @Test
    fun `recordStep marks failed verification as FAILURE`() {
        recorder.start("session-4", "test")
        val call = ToolCall("call-2", "tap(500, 800)", "", "error", ok = false, durationMs = 10)
        recorder.recordStep("session-4", 1, "Button", "tap(500, 800)", call, verifyOk = false)
        val episode = recorder.getEpisode("session-4")!!
        assertEquals(Lesson.LessonOutcome.FAILURE, episode[0].outcome)
    }

    @Test
    fun `recordLoop marks outcome as LOOP`() {
        recorder.start("session-5", "test")
        recorder.recordLoop("session-5", 1, "Button", "tap(500, 800)")
        val episode = recorder.getEpisode("session-5")!!
        assertEquals(Lesson.LessonOutcome.LOOP, episode[0].outcome)
    }

    @Test
    fun `finish updates finalStatus`() {
        recorder.start("session-6", "test")
        recorder.finish("session-6", "DONE")
        assertEquals("DONE", recorder.getFinalStatus("session-6"))
    }

    @Test
    fun `clear removes the episode`() {
        recorder.start("session-7", "test")
        recorder.clear("session-7")
        assertEquals(null, recorder.getEpisode("session-7"))
        assertEquals(null, recorder.getUserPrompt("session-7"))
    }

    @Test
    fun `multiple sessions are isolated`() {
        recorder.start("session-a", "prompt a")
        recorder.start("session-b", "prompt b")
        val callA = ToolCall("ca", "tap(1, 1)", "", "ok", true, 1)
        recorder.recordStep("session-a", 1, "obs a", "tap(1, 1)", callA, true)
        val callB = ToolCall("cb", "tap(2, 2)", "", "ok", true, 1)
        recorder.recordStep("session-b", 1, "obs b", "tap(2, 2)", callB, true)
        val epA = recorder.getEpisode("session-a")!!
        val epB = recorder.getEpisode("session-b")!!
        assertEquals(1, epA.size)
        assertEquals(1, epB.size)
        assertEquals("tap(1, 1)", epA[0].action)
        assertEquals("tap(2, 2)", epB[0].action)
    }
}
