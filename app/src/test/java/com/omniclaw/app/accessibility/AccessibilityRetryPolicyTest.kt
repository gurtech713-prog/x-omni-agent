package com.omniclaw.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AccessibilityRetryPolicy] — backoff computation.
 */
class AccessibilityRetryPolicyTest {

    @Test
    fun `delayForAttempt zero returns zero`() {
        val p = AccessibilityRetryPolicy.Default
        assertEquals(0L, p.delayForAttempt(0))
    }

    @Test
    fun `delayForAttempt grows exponentially`() {
        val p = AccessibilityRetryPolicy(baseDelayMs = 100, maxDelayMs = 10000, backoffFactor = 2.0)
        assertEquals(100L, p.delayForAttempt(1))
        assertEquals(200L, p.delayForAttempt(2))
        assertEquals(400L, p.delayForAttempt(3))
    }

    @Test
    fun `delayForAttempt caps at maxDelay`() {
        val p = AccessibilityRetryPolicy(baseDelayMs = 1000, maxDelayMs = 5000, backoffFactor = 2.0)
        // attempt 10 → 1000 * 2^9 = 512000, capped at 5000.
        assertEquals(5000L, p.delayForAttempt(10))
    }

    @Test
    fun `NoRetry policy has maxAttempts 1`() {
        assertEquals(1, AccessibilityRetryPolicy.NoRetry.maxAttempts)
    }

    @Test
    fun `Aggressive policy has more attempts`() {
        assertTrue(AccessibilityPolicy().maxAttempts < AccessibilityRetryPolicy.Aggressive.maxAttempts)
    }

    private fun AccessibilityPolicy() = AccessibilityRetryPolicy.Default
}
