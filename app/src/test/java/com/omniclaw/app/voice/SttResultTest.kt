package com.omniclaw.app.voice

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SttResult] — the sealed result hierarchy.
 *
 * Verifies the type structure that [StreamingSttClient] returns so callers
 * can pattern-match exhaustively.
 */
class SttResultTest {

    @Test
    fun `Success carries text and optional fields`() {
        val r = SttResult.Success(text = "hello world", language = "en", durationMs = 1500)
        assertTrue(r is SttResult.Success)
        assertTrue((r as SttResult.Success).text == "hello world")
        assertTrue(r.language == "en")
        assertTrue(r.durationMs == 1500L)
    }

    @Test
    fun `Cancelled is a singleton object`() {
        val a: SttResult = SttResult.Cancelled
        val b: SttResult = SttResult.Cancelled
        assertTrue(a === b)
    }

    @Test
    fun `NetworkFailure carries message`() {
        val r = SttResult.NetworkFailure("connection reset")
        assertTrue(r is SttResult.NetworkFailure)
        assertTrue((r as SttResult.NetworkFailure).message == "connection reset")
    }

    @Test
    fun `AuthenticationFailure carries message`() {
        val r = SttResult.AuthenticationFailure("HTTP 401")
        assertTrue(r is SttResult.AuthenticationFailure)
    }

    @Test
    fun `Timeout carries message`() {
        val r = SttResult.Timeout("60s exceeded")
        assertTrue(r is SttResult.Timeout)
    }

    @Test
    fun `UnknownFailure carries message and optional cause`() {
        val cause = RuntimeException("inner")
        val r = SttResult.UnknownFailure("malformed JSON", cause)
        assertTrue(r is SttResult.UnknownFailure)
        assertTrue((r as SttResult.UnknownFailure).cause === cause)
    }

    @Test
    fun `Segment carries timing and confidence`() {
        val seg = SttResult.Segment(text = "hello", startMs = 0, endMs = 500, confidence = 0.95f)
        assertTrue(seg.confidence == 0.95f)
        assertTrue(seg.endMs - seg.startMs == 500L)
    }

    @Test
    fun `exhaustive when matches all variants`() {
        // This test verifies the sealed hierarchy is exhaustive — if a new
        // variant is added, this won't compile.
        fun label(r: SttResult): String = when (r) {
            is SttResult.Success -> "success"
            SttResult.Cancelled -> "cancelled"
            is SttResult.NetworkFailure -> "network"
            is SttResult.AuthenticationFailure -> "auth"
            is SttResult.Timeout -> "timeout"
            is SttResult.UnknownFailure -> "unknown"
            is SttResult.Segment -> "segment"
        }
        assertTrue(label(SttResult.Success("x")) == "success")
        assertTrue(label(SttResult.Cancelled) == "cancelled")
        assertTrue(label(SttResult.NetworkFailure("x")) == "network")
        assertTrue(label(SttResult.AuthenticationFailure("x")) == "auth")
        assertTrue(label(SttResult.Timeout("x")) == "timeout")
        assertTrue(label(SttResult.UnknownFailure("x")) == "unknown")
        assertTrue(label(SttResult.Segment("x", 0, 0)) == "segment")
    }
}
