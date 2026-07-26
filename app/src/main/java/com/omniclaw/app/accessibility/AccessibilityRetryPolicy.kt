package com.omniclaw.app.accessibility

/**
 * Retry policy for accessibility operations.
 *
 * Accessibility actions frequently fail transiently: a tap lands during an
 * animation, the root node is stale mid-transition, or the target app hasn't
 * finished rendering. The [AccessibilityExecutor] consults this policy to
 * decide how many times to retry, how long to wait between attempts, and
 * when to give up.
 *
 * Values are tuned for real-world Android UI transitions:
 *   - maxAttempts = 3 — enough to ride out a 200-400ms animation, not so many
 *     that the agent loop stalls for seconds.
 *   - baseDelayMs = 150 — short enough that 3 attempts fit in ~1s, long
 *     enough that a frame actually renders between retries.
 *   - maxDelayMs = 1200 — caps the exponential backoff so we never wait
 *     more than ~1.2s between attempts.
 *
 * The policy is a data class so tests can construct custom variants (e.g.
 * a no-retry policy for idempotent actions) without touching the executor.
 */
data class AccessibilityRetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 150L,
    val maxDelayMs: Long = 1200L,
    val backoffFactor: Double = 2.0,
) {
    /**
     * Compute the delay before attempt [attemptIndex] (0-based).
     * Returns 0 for the first attempt (no delay before the initial try).
     */
    fun delayForAttempt(attemptIndex: Int): Long {
        if (attemptIndex <= 0) return 0L
        val raw = (baseDelayMs * Math.pow(backoffFactor, (attemptIndex - 1).toDouble())).toLong()
        return raw.coerceAtMost(maxDelayMs)
    }

    companion object {
        /** No retries — for idempotent actions where a second attempt is wasteful. */
        val NoRetry = AccessibilityRetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0)

        /** Aggressive — for time-sensitive actions like dismissing dialogs. */
        val Aggressive = AccessibilityRetryPolicy(maxAttempts = 5, baseDelayMs = 80, maxDelayMs = 600)

        /** Default — balances responsiveness against reliability. */
        val Default = AccessibilityRetryPolicy()
    }
}
