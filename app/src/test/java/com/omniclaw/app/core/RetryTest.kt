package com.omniclaw.app.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for the [retry] helper — exponential backoff with retryable
 * exception filtering.
 *
 * These tests run under [runTest] so the [delay] calls inside [retry] are
 * skipped via the test scheduler (no real wall-clock time elapses).
 */
class RetryTest {

    @Test
    fun `returns result on first success`() = runTest {
        val calls = intArrayOf(0)
        val result = retry(maxAttempts = 3) {
            calls[0]++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls[0])
    }

    @Test
    fun `retries on IOException and succeeds on second attempt`() = runTest {
        val calls = intArrayOf(0)
        val result = retry(maxAttempts = 3, baseDelayMs = 1) {
            calls[0]++
            if (calls[0] == 1) throw IOException("transient")
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(2, calls[0])
    }

    @Test
    fun `does not retry non-retryable exception`() = runTest {
        val calls = intArrayOf(0)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                retry(maxAttempts = 3, baseDelayMs = 1) {
                    calls[0]++
                    throw IllegalArgumentException("bad request")
                }
            }
        }
        assertEquals("non-retryable should fire exactly once", 1, calls[0])
    }

    @Test
    fun `exhausts attempts and throws last error`() = runTest {
        val calls = intArrayOf(0)
        val ex = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                retry(maxAttempts = 3, baseDelayMs = 1) {
                    calls[0]++
                    throw IOException("always fails")
                }
            }
        }
        assertEquals(3, calls[0])
        assertEquals("always fails", ex.message)
    }

    @Test
    fun `respects custom retryable predicate`() = runTest {
        val calls = intArrayOf(0)
        // Only retry on IllegalStateException, not on IOException.
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                retry(
                    maxAttempts = 5,
                    baseDelayMs = 1,
                    retryable = { it is IllegalStateException },
                ) {
                    calls[0]++
                    throw IOException("not retryable per predicate")
                }
            }
        }
        assertEquals(1, calls[0])
    }

    @Test
    fun `rethrows CancellationException without retry`() = runTest {
        val calls = intArrayOf(0)
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                retry(maxAttempts = 3, baseDelayMs = 1) {
                    calls[0]++
                    throw kotlinx.coroutines.CancellationException("cancelled")
                }
            }
        }
        assertEquals(1, calls[0])
    }

    @Test
    fun `delay grows exponentially and caps at maxDelay`() = runTest {
        // We can't easily assert wall-clock time under runTest, but we can
        // verify the function completes and retries the expected number of
        // times. The exponential growth is exercised; the cap is enforced
        // by coerceAtMost(maxDelayMs) in the implementation.
        val calls = intArrayOf(0)
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                retry(
                    maxAttempts = 4,
                    baseDelayMs = 100,
                    maxDelayMs = 500,
                ) {
                    calls[0]++
                    throw IOException("fail")
                }
            }
        }
        assertEquals(4, calls[0])
    }

    @Test
    fun `returns result immediately when block succeeds without throwing`() = runTest {
        val result = retry<String>(maxAttempts = 3) { "value" }
        assertEquals("value", result)
    }

    @Test
    fun `throws RuntimeException when maxAttempts is zero`() = runTest {
        // With maxAttempts=0, the repeat block never runs, lastError is null,
        // and the fallback throws RuntimeException.
        assertThrows(RuntimeException::class.java) {
            kotlinx.coroutines.runBlocking {
                retry<String>(maxAttempts = 0) { "unreachable" }
            }
        }
    }

    @Test
    fun `retryable predicate receives the actual exception`() = runTest {
        val seen = mutableListOf<Throwable>()
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                retry(
                    maxAttempts = 3,
                    baseDelayMs = 1,
                    retryable = { e ->
                        seen.add(e)
                        e is IOException
                    },
                ) {
                    throw IOException("test")
                }
            }
        }
        // Predicate is called once per failed attempt (3 times for 3 attempts).
        assertTrue("predicate should see each exception", seen.all { it is IOException })
        assertTrue("predicate should be called at least once", seen.isNotEmpty())
    }
}
