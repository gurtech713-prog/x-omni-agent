package com.omniclaw.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [retry]. Covers: success on first attempt, retry on retryable
 * exception, no-retry on non-retryable exception, max-attempts exhaustion,
 * and — critically — that [CancellationException] is rethrown (no swallow).
 */
class RetryCancellationTest {

    @Test
    fun `success on first attempt returns immediately`() = runBlocking {
        var calls = 0
        val result = retry(maxAttempts = 3, baseDelayMs = 1) {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun `retries on retryable exception`() = runBlocking {
        var calls = 0
        val result = retry(
            maxAttempts = 3,
            baseDelayMs = 1,
            retryable = { it is IOException },
        ) {
            calls++
            if (calls < 3) throw IOException("transient")
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(3, calls)
    }

    @Test
    fun `non-retryable exception propagates immediately`() = runBlocking {
        var calls = 0
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                retry(
                    maxAttempts = 5,
                    baseDelayMs = 1,
                    retryable = { it is IOException },
                ) {
                    calls++
                    throw IllegalStateException("not retryable")
                }
            }
        }
        assertEquals("not retryable", ex.message)
        assertEquals(1, calls)
    }

    @Test
    fun `max attempts exhaustion throws last error`() = runBlocking {
        var calls = 0
        val ex = assertThrows(IOException::class.java) {
            runBlocking {
                retry(
                    maxAttempts = 3,
                    baseDelayMs = 1,
                    retryable = { it is IOException },
                ) {
                    calls++
                    throw IOException("always fails")
                }
            }
        }
        assertEquals("always fails", ex.message)
        assertEquals(3, calls)
    }

    @Test
    fun `CancellationException is rethrown not swallowed as retryable`() = runBlocking {
        // A retryable=true lambda that throws CancellationException MUST still
        // propagate immediately — never retried, never silently dropped.
        var calls = 0
        try {
            withTimeout(50) {
                retry(
                    maxAttempts = 10,
                    baseDelayMs = 1000,
                    retryable = { true },  // would normally swallow everything
                ) {
                    calls++
                    throw CancellationException("test cancellation")
                }
            }
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            // Expected.
        }
        // The retry helper must NOT have retried after a cancellation.
        assertEquals(1, calls)
    }

    @Test
    fun `delay between attempts respects exponential backoff`() = runBlocking {
        var calls = 0
        val start = System.currentTimeMillis()
        retry(
            maxAttempts = 3,
            baseDelayMs = 50,
            maxDelayMs = 1000,
            retryable = { it is IOException },
        ) {
            calls++
            if (calls < 3) throw IOException("retry me")
            "done"
        }
        val elapsed = System.currentTimeMillis() - start
        // 2 retries: delay(50) + delay(100) = ~150ms minimum
        assertTrue("Elapsed $elapsed ms should be >= 100", elapsed >= 100)
    }
}
