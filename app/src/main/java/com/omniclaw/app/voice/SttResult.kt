package com.omniclaw.app.voice

/**
 * Structured result of a speech-to-text transcription.
 *
 * Every [StreamingSttClient] call returns one of these variants. Callers
 * pattern-match on the type to decide how to react:
 *
 *   - [Success] → use the transcript.
 *   - [Cancelled] → the user (or agent loop) cancelled; don't show an error.
 *   - [NetworkFailure] → show "check your connection" and offer retry.
 *   - [AuthenticationFailure] → show "check your STT API key in Settings".
 *   - [Timeout] → show "transcription took too long; try again".
 *   - [UnknownFailure] → log the cause, show a generic error.
 */
sealed class SttResult {
    /** Successful transcription. [text] is the full transcript. */
    data class Success(
        val text: String,
        val language: String? = null,
        val durationMs: Long = 0L,
        val segments: List<Segment> = emptyList(),
    ) : SttResult()

    /** The transcription was cancelled before completion. */
    data object Cancelled : SttResult()

    /** Network connectivity failure (no connection, DNS failure, socket reset). */
    data class NetworkFailure(val message: String) : SttResult()

    /** The API key is missing, invalid, or expired (HTTP 401 / 403). */
    data class AuthenticationFailure(val message: String) : SttResult()

    /** The request exceeded the configured timeout. */
    data class Timeout(val message: String) : SttResult()

    /** Any other failure (malformed JSON, server 5xx, unsupported format, etc.). */
    data class UnknownFailure(val message: String, val cause: Throwable? = null) : SttResult()

    /** A time-stamped segment of the transcript (for streaming / word-level timing). */
    data class Segment(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val confidence: Float = 0f,
    ) : SttResult()
}
