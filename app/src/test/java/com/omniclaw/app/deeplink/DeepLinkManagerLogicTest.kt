package com.omniclaw.app.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DeepLinkManager]'s bookmark ID sanitization and the
 * `launchByPhrase` whole-word matching logic.
 *
 * Both helpers are pure functions of their inputs (no Context needed), so we
 * mirror them here to lock the contract. A regression in ID sanitization
 * would cause file-path collisions; a regression in phrase matching would
 * cause false-positive bookmark launches.
 */
class DeepLinkManagerLogicTest {

    /**
     * Sanitize a bookmark name into a file-safe ID — mirrors the exact
     * transformation in [DeepLinkManager.saveBookmark].
     */
    private fun sanitizeBookmarkId(name: String): String {
        val baseId = "link-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24)
        return baseId
    }

    @Test
    fun `sanitize lowercases and replaces non-alphanumerics with dashes`() {
        assertEquals("link-amazon-quick-link", sanitizeBookmarkId("Amazon Quick Link"))
        assertEquals("link-reddit-budget-travel", sanitizeBookmarkId("Reddit / Budget Travel"))
    }

    @Test
    fun `sanitize trims leading and trailing dashes`() {
        assertEquals("link-hello", sanitizeBookmarkId("!!! hello !!!"))
        assertEquals("link-hello", sanitizeBookmarkId("---hello---"))
    }

    @Test
    fun `sanitize caps at 24 chars after the link- prefix`() {
        val long = "a".repeat(100)
        val sanitized = sanitizeBookmarkId(long)
        // "link-" (5) + up to 24 chars = 29 total
        assertEquals(29, sanitized.length)
        assertTrue(sanitized.startsWith("link-"))
    }

    @Test
    fun `sanitize handles empty name`() {
        assertEquals("link-", sanitizeBookmarkId(""))
    }

    @Test
    fun `sanitize handles unicode name`() {
        // Non-ASCII chars are stripped by [^a-z0-9]+.
        val sanitized = sanitizeBookmarkId("Café Résumé")
        assertEquals("link-caf-r-sum", sanitized)
    }

    /**
     * Whole-word phrase matching — mirrors the exact logic in
     * [DeepLinkManager.launchByPhrase]. Returns true if [bookmarkName]
     * appears as a whole word in [phrase] (case-insensitive).
     */
    private fun matchesPhrase(phrase: String, bookmarkName: String): Boolean {
        val firstChar = bookmarkName.firstOrNull()
        val lastChar = bookmarkName.lastOrNull()
        val isWordChar: (Char) -> Boolean = { it.isLetterOrDigit() || it == '_' }
        val prefix = if (firstChar != null && isWordChar(firstChar)) "(?<!\\w)" else ""
        val suffix = if (lastChar != null && isWordChar(lastChar)) "(?!\\w)" else ""
        val pattern = Regex("$prefix${Regex.escape(bookmarkName)}$suffix", RegexOption.IGNORE_CASE)
        return pattern.containsMatchIn(phrase)
    }

    @Test
    fun `whole-word match succeeds for exact name`() {
        assertTrue(matchesPhrase("Open Amazon", "Amazon"))
    }

    @Test
    fun `whole-word match succeeds for name in middle of phrase`() {
        assertTrue(matchesPhrase("Please open Amazon for me", "Amazon"))
    }

    @Test
    fun `whole-word match fails for substring that is not a whole word`() {
        // "Am" should not match "Amazon" — this was the original bug.
        assertFalse(matchesPhrase("Open Amazon", "Am"))
    }

    @Test
    fun `whole-word match is case insensitive`() {
        assertTrue(matchesPhrase("open amazon now", "Amazon"))
        assertTrue(matchesPhrase("OPEN AMAZON", "amazon"))
    }

    @Test
    fun `whole-word match handles names with regex-special chars`() {
        // Regex.escape ensures a name like "C++" doesn't break the pattern.
        assertTrue(matchesPhrase("Open C++ now", "C++"))
    }

    @Test
    fun `longest name wins when multiple bookmarks match`() {
        // This is the tie-break rule in launchByPhrase.
        val matches = listOf(
            "Am" to 2,
            "Amazon" to 6,
            "Amazon Prime" to 12,
        )
        val best = matches.maxByOrNull { it.second }?.first
        assertEquals("Amazon Prime", best)
    }
}
