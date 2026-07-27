package com.omniclaw.app.core

import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Generic retry helper with exponential backoff + full jitter.
 *
 * Retries [maxAttempts] times, waiting [baseDelayMs] * 2^(attempt) between
 * each attempt (capped at [maxDelayMs]). The wait is then uniformly jittered
 * in [0, baseDelay] (full jitter per AWS Architecture Blog) so concurrent
 * retryers don't synchronize their retries into a thundering herd. Catches
 * [retryable] exceptions only — non-retryable exceptions (e.g.
 * IllegalArgumentException) propagate immediately.
 *
 * Usage:
 *   val result = retry(3) { llm.complete(...) }
 *   val result = retry(3, retryable = { it is IOException || it is LlmException }) { ... }
 */
suspend fun <T> retry(
    maxAttempts: Int = 3,
    baseDelayMs: Long = 500,
    maxDelayMs: Long = 8_000,
    retryable: (Throwable) -> Boolean = { it.isRetryableByDefault() },
    block: suspend () -> T,
): T {
    var lastError: Throwable? = null
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            lastError = e
            if (!retryable(e)) throw e
            if (attempt < maxAttempts - 1) {
                // U-M16: full jitter — wait a random amount in [0, baseDelay].
                // Without jitter, N concurrent retryers hitting a rate-limited
                // endpoint all wait the same backoff and re-fire in lockstep,
                // re-triggering the rate limit. Full jitter de-correlates them.
                val baseDelay = (baseDelayMs * 2.0.pow(attempt)).roundToLong().coerceAtMost(maxDelayMs)
                val delayMs = Random.nextLong(0, baseDelay + 1)
                delay(delayMs)
            }
        }
    }
    throw lastError ?: RuntimeException("retry: exhausted all $maxAttempts attempts")
}

/**
 * Default retry classifier (audit M-48). Retries on transport-level
 * [java.io.IOException] AND on transient HTTP errors commonly returned by LLM
 * providers - 429 (rate limit), 503 (service unavailable), and 502 (bad
 * gateway) - which clients surface as exceptions whose message carries the
 * status code / reason rather than an IOException subtype.
 *
 * U-M17: uses word-boundary regexes instead of bare `contains("429")` so a
 * legitimate message containing "14293" (a number that just happens to
 * contain "429" as a substring) doesn't trigger a false-positive retry.
 */
fun Throwable.isRetryableByDefault(): Boolean {
    if (this is java.io.IOException) return true
    if (this is kotlinx.coroutines.TimeoutCancellationException) return true
    val msg = (message ?: "").lowercase()
    return Regex("\\b429\\b").containsMatchIn(msg) ||
        Regex("\\b503\\b").containsMatchIn(msg) ||
        Regex("\\b502\\b").containsMatchIn(msg) ||
        Regex("\\btimeout\\b").containsMatchIn(msg)
}
