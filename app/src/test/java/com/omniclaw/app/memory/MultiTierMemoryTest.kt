package com.omniclaw.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MultiTierMemory] — in-memory tier management + retrieval.
 */
class MultiTierMemoryTest {

    @Test
    fun `addShortTerm stores entry per session`() {
        val m = MultiTierMemory()
        m.addShortTerm("s1", "observed home screen")
        m.addShortTerm("s1", "tapped search button")
        val entries = m.retrieveForSession("s1")
        assertEquals(2, entries.size)
        assertEquals(MultiTierMemory.Memory.Tier.SHORT_TERM, entries[0].tier)
    }

    @Test
    fun `addWorking stores entry per session`() {
        val m = MultiTierMemory()
        m.addWorking("s1", "user wants budget travel tips")
        val entries = m.retrieveForSession("s1")
        assertEquals(1, entries.size)
        assertEquals(MultiTierMemory.Memory.Tier.WORKING, entries[0].tier)
    }

    @Test
    fun `sessions are isolated`() {
        val m = MultiTierMemory()
        m.addShortTerm("a", "session a content")
        m.addShortTerm("b", "session b content")
        assertEquals(1, m.retrieveForSession("a").size)
        assertEquals(1, m.retrieveForSession("b").size)
    }

    @Test
    fun `retrieveRelevant scores by tag and content`() {
        val m = MultiTierMemory()
        m.addShortTerm("s1", "Open Reddit and search for parrot photos", tags = setOf("reddit", "search"))
        m.addShortTerm("s1", "Check the weather forecast", tags = setOf("weather"))
        val results = m.retrieveRelevant("s1", "reddit", limit = 5)
        assertEquals(1, results.size)
        assertTrue(results[0].content.contains("Reddit"))
    }

    @Test
    fun `short-term caps at 50 entries`() {
        val m = MultiTierMemory()
        repeat(60) { m.addShortTerm("s1", "entry $it") }
        val entries = m.retrieveForSession("s1")
        assertEquals(50, entries.size)
    }

    @Test
    fun `clearSession removes all in-memory tiers for that session`() {
        val m = MultiTierMemory()
        m.addShortTerm("s1", "short")
        m.addWorking("s1", "working")
        m.clearSession("s1")
        assertTrue(m.retrieveForSession("s1").isEmpty())
    }

    @Test
    fun `tierCounts aggregates across sessions`() {
        val m = MultiTierMemory()
        m.addShortTerm("a", "x")
        m.addShortTerm("b", "y")
        m.addWorking("a", "z")
        val counts = m.tierCounts()
        assertEquals(2, counts[MultiTierMemory.Memory.Tier.SHORT_TERM])
        assertEquals(1, counts[MultiTierMemory.Memory.Tier.WORKING])
    }

    @Test
    fun `task memory is independent of session memory`() {
        val m = MultiTierMemory()
        m.addTask("task-1", "photo list: [a, b, c]")
        m.addShortTerm("s1", "session content")
        assertEquals(1, m.retrieveTask("task-1").size)
        assertEquals(1, m.retrieveForSession("s1").size)
    }
}
