package com.omniclaw.app.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LlmException] and [RateLimitException] — the two throwable
 * types thrown by [LlmClient]. These tests verify the contract callers depend
 * on (notably that 429s surface a [RateLimitException] carrying Retry-After).
 */
class LlmExceptionTest {

    @Test
    fun `LlmException message is preserved`() {
        val ex = LlmException("HTTP 401: bad key")
        assertEquals("HTTP 401: bad key", ex.message)
        assertNull(ex.cause)
    }

    @Test
    fun `RateLimitException with Retry-After includes the value in the message`() {
        val ex = RateLimitException(retryAfterSeconds = 30, body = """{"error":"slow down"}""")
        assertNotNull(ex.message)
        assertTrue(ex.message!!.contains("30 s"))
        assertEquals(30, ex.retryAfterSeconds)
    }

    @Test
    fun `RateLimitException without Retry-After header still constructs cleanly`() {
        val ex = RateLimitException(retryAfterSeconds = null, body = "")
        assertNull(ex.retryAfterSeconds)
        assertTrue(ex.message!!.contains("429"))
    }

    @Test
    fun `RateLimitException is a RuntimeException so retry blocks catch it`() {
        val ex = RateLimitException(60, "")
        assertTrue(ex is RuntimeException)
    }

    @Test
    fun `LlmException is a RuntimeException so retry blocks catch it`() {
        val ex = LlmException("HTTP 500")
        assertTrue(ex is RuntimeException)
    }
}
