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
                val delayMs = (baseDelayMs * 2.0.pow(attempt)).roundToLong().coerceAtMost(maxDelayMs)
                delay(delayMs)
            }
        }
    }
    throw lastError ?: RuntimeException("retry: exhausted all $maxAttempts attempts")
}

/**
 * Default retry classifier (audit M-48). Retries on transport-level
 * [java.io.IOException] AND on transient HTTP errors commonly returned by LLM
 * providers - 429 (rate limit) and 503 (service unavailable) - which clients
 * surface as exceptions whose message carries the status code / reason rather
 * than an IOException subtype.
 */
fun Throwable.isRetryableByDefault(): Boolean {
    if (this is java.io.IOException) return true
    val msg = (message ?: "").lowercase()
    return msg.contains("429") || msg.contains("503") ||
        msg.contains("rate limit") || msg.contains("too many requests") ||
        msg.contains("service unavailable")
}
