package com.omniclaw.app.core

import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Generic retry helper with exponential backoff.
 *
 * Retries [maxAttempts] times, waiting [baseDelayMs] * 2^(attempt-1) between
 * each attempt (capped at [maxDelayMs]). Catches [retryable] exceptions only —
 * non-retryable exceptions (e.g. IllegalArgumentException) propagate immediately.
 *
 * Usage:
 *   val result = retry(3) { llm.complete(...) }
 *   val result = retry(3, retryable = { it is IOException || it is LlmException }) { ... }
 */
suspend fun <T> retry(
    maxAttempts: Int = 3,
    baseDelayMs: Long = 500,
    maxDelayMs: Long = 8_000,
    retryable: (Throwable) -> Boolean = { it is java.io.IOException || it is RuntimeException },
    block: suspend () -> T,
): T {
    var lastError: Throwable? = null
    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: Throwable) {
            lastError = e
            if (!retryable(e)) throw e
            if (attempt < maxAttempts - 1) {
                val delayMs = (baseDelayMs * 2.0.pow(attempt)).roundToLong().coerceAtMost(maxDelayMs)
                delay(delayMs)
            }
        }
    }
    throw lastError ?: RuntimeException("retry: exhausted all $maxAttempts attempts")
}
