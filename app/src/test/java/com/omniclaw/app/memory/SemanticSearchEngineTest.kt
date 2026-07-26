package com.omniclaw.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SemanticSearchEngine] — TF-IDF-style retrieval.
 */
class SemanticSearchEngineTest {

    @Test
    fun `index and search by exact word match`() {
        val s = SemanticSearchEngine()
        s.index("d1", "reddit budget travel tips")
        s.index("d2", "weather forecast today")
        val results = s.search("reddit", k = 2)
        assertEquals(2, results.size)
        assertEquals("d1", results[0].docId)
        assertTrue(results[0].score > results[1].score)
    }

    @Test
    fun `search returns top-k`() {
        val s = SemanticSearchEngine()
        s.index("d1", "parrot photos gallery")
        s.index("d2", "parrot videos gallery")
        s.index("d3", "weather forecast")
        val results = s.search("parrot", k = 2)
        assertEquals(2, results.size)
        assertTrue(results.all { it.docId in setOf("d1", "d2") })
    }

    @Test
    fun `remove drops from index`() {
        val s = SemanticSearchEngine()
        s.index("d1", "content")
        s.remove("d1")
        assertEquals(0, s.size)
        assertTrue(s.search("content").isEmpty())
    }

    @Test
    fun `stop words are filtered`() {
        val s = SemanticSearchEngine()
        s.index("d1", "the quick brown fox")
        // "the" is a stop word — searching for it should return 0 results.
        val results = s.search("the", k = 5)
        // The query tokenizes to ["the"] which is filtered, so the query embedding
        // is all zeros and cosine similarity is 0 for all docs.
        assertTrue("stop-word query should return zero-score results", results.all { it.score == 0f })
    }

    @Test
    fun `empty query returns zero-score results`() {
        val s = SemanticSearchEngine()
        s.index("d1", "some content")
        val results = s.search("", k = 5)
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.score == 0f })
    }

    @Test
    fun `clear empties the index`() {
        val s = SemanticSearchEngine()
        s.index("d1", "content")
        s.index("d2", "more content")
        s.clear()
        assertEquals(0, s.size)
    }
}
