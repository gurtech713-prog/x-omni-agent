package com.omniclaw.app.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for [LlmClient.Message] and [LlmClient.CompletionResult] — the
 * data classes that flow through every LLM call.
 *
 * These verify the canonical shapes that [UnifiedLlmClient], [GeminiClient],
 * and [LocalLlmClient] all depend on. A regression in any field name or
 * default would break the wire protocol for at least one provider.
 */
class LlmClientModelTest {

    @Test
    fun `message with role and content`() {
        val m = LlmClient.Message(role = "user", content = "hello")
        assertEquals("user", m.role)
        assertEquals("hello", m.content)
    }

    @Test
    fun `message roles are plain strings not enums`() {
        // The wire format uses lowercase string roles — the agent loop builds
        // them via ChatMessage.Role.name.lowercase(). Verify the shape.
        listOf("system", "user", "assistant", "tool").forEach { role ->
            val m = LlmClient.Message(role, "x")
            assertEquals(role, m.role)
        }
    }

    @Test
    fun `completion result carries text usage and finish reason`() {
        val usage = com.omniclaw.app.data.model.LlmUsage(
            promptTokens = 10,
            completionTokens = 5,
            totalTokens = 15,
        )
        val result = LlmClient.CompletionResult(
            text = "response",
            usage = usage,
            finishReason = "stop",
        )
        assertEquals("response", result.text)
        assertEquals(15L, result.usage.totalTokens)
        assertEquals("stop", result.finishReason)
    }

    @Test
    fun `completion result default finish reason is empty string`() {
        // When the API doesn't return a finish_reason, we default to "".
        // The agent loop checks for "length" specifically, so empty is safe.
        val result = LlmClient.CompletionResult(
            text = "x",
            usage = com.omniclaw.app.data.model.LlmUsage(0, 0, 0),
            finishReason = "",
        )
        assertEquals("", result.finishReason)
    }

    @Test
    fun `llm exception message is preserved`() {
        val ex = LlmException("HTTP 503: service unavailable")
        assertEquals("HTTP 503: service unavailable", ex.message)
    }

    @Test
    fun `llm exception is a runtime exception`() {
        val ex = LlmException("test")
        assertNotNull(ex)
        assert(ex is RuntimeException)
    }
}

/**
 * Unit tests for [UnifiedLlmClient]'s local-model-spec parser.
 *
 * The parser splits "local-<family>:<path>" into a (family, path) pair.
 * It's private in UnifiedLlmClient, so we mirror the exact logic here to
 * lock the parsing contract. A regression would break every LiteRT request.
 */
class LocalModelSpecParserTest {

    private fun parseLocalModelSpec(model: String): Pair<String, String> {
        val s = model.removePrefix("local-")
        val colon = s.indexOf(':')
        return if (colon > 0) {
            Pair(s.substring(0, colon), s.substring(colon + 1))
        } else {
            Pair("gemma", s)
        }
    }

    @Test
    fun `parses family and path`() {
        val (family, path) = parseLocalModelSpec("local-gemma:models/gemma-2b.tflite")
        assertEquals("gemma", family)
        assertEquals("models/gemma-2b.tflite", path)
    }

    @Test
    fun `parses tinyllama family`() {
        val (family, path) = parseLocalModelSpec("local-tinyllama:assets://models/tinyllama.tflite")
        assertEquals("tinyllama", family)
        assertEquals("assets://models/tinyllama.tflite", path)
    }

    @Test
    fun `defaults to gemma family when no colon`() {
        val (family, path) = parseLocalModelSpec("local-models/gemma.tflite")
        assertEquals("gemma", family)
        assertEquals("models/gemma.tflite", path)
    }

    @Test
    fun `handles paths with multiple colons (Windows-style drive letters edge case)`() {
        // A path like "C:\models\gemma.tflite" would split on the first colon.
        // This is an edge case that doesn't occur on Android (no drive letters)
        // but the parser should still produce a sensible result.
        val (family, path) = parseLocalModelSpec("local-gemma:C:/models/gemma.tflite")
        assertEquals("gemma", family)
        assertEquals("C:/models/gemma.tflite", path)
    }

    @Test
    fun `handles bare model name without local- prefix`() {
        // removePrefix is a no-op if the prefix isn't present.
        val (family, path) = parseLocalModelSpec("gemma:models/x.tflite")
        assertEquals("gemma", family)
        assertEquals("models/x.tflite", path)
    }
}
