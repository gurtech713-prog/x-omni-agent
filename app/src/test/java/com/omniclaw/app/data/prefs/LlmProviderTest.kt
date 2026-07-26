package com.omniclaw.app.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [LlmProvider.fromString] — the parser that converts the
 * stored provider string back to the enum.
 *
 * This is the single source of truth for "which backend does the user want",
 * so parsing regressions would silently route requests to the wrong provider.
 */
class LlmProviderTest {

    @Test
    fun `parses gemini`() {
        assertEquals(LlmProvider.GEMINI, LlmProvider.fromString("gemini"))
        assertEquals(LlmProvider.GEMINI, LlmProvider.fromString("GEMINI"))
        assertEquals(LlmProvider.GEMINI, LlmProvider.fromString("  Gemini  "))
    }

    @Test
    fun `parses litert and aliases`() {
        assertEquals(LlmProvider.LITERT, LlmProvider.fromString("litert"))
        assertEquals(LlmProvider.LITERT, LlmProvider.fromString("local"))
        assertEquals(LlmProvider.LITERT, LlmProvider.fromString("on-device"))
        assertEquals(LlmProvider.LITERT, LlmProvider.fromString("LITERT"))
    }

    @Test
    fun `defaults to OPENAI_COMPAT for unknown`() {
        assertEquals(LlmProvider.OPENAI_COMPAT, LlmProvider.fromString("openai-compat"))
        assertEquals(LlmProvider.OPENAI_COMPAT, LlmProvider.fromString("unknown"))
        assertEquals(LlmProvider.OPENAI_COMPAT, LlmProvider.fromString(""))
    }

    @Test
    fun `defaults to OPENAI_COMPAT for null`() {
        assertEquals(LlmProvider.OPENAI_COMPAT, LlmProvider.fromString(null))
    }

    @Test
    fun `all enum values round-trip through lowercase name`() {
        LlmProvider.entries.forEach { p ->
            assertEquals(p, LlmProvider.fromString(p.name.lowercase()))
        }
    }
}
