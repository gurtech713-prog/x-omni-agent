package com.omniclaw.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VoiceActivityDetector] — speech/silence detection.
 */
class VoiceActivityDetectorTest {

    @Test
    fun `starts in SILENCE state`() {
        val vad = VoiceActivityDetector()
        assertEquals(VoiceActivityDetector.VadState.SILENCE, vad.state.value)
        assertFalse(vad.hasDetectedSpeech)
    }

    @Test
    fun `silent audio keeps SILENCE state`() {
        val vad = VoiceActivityDetector(speechThreshold = 0.02f, speechOnsetMs = 50)
        // All-zero samples → RMS = 0 → below silence threshold.
        val silent = ShortArray(1600)  // 100ms at 16kHz
        vad.feed(silent)
        assertEquals(VoiceActivityDetector.VadState.SILENCE, vad.state.value)
        assertFalse(vad.hasDetectedSpeech)
    }

    @Test
    fun `loud audio transitions to SPEECH after onset`() {
        val vad = VoiceActivityDetector(speechThreshold = 0.02f, silenceThreshold = 0.005f, speechOnsetMs = 1)
        // Max-amplitude samples → RMS = 1.0 → well above speech threshold.
        val loud = ShortArray(1600) { Short.MAX_VALUE }
        // First feed triggers onset tracking.
        vad.feed(loud)
        // With onsetMs=1, the first feed should transition immediately (now - now >= 1).
        // But we need at least 1ms to pass — add a tiny sleep.
        Thread.sleep(5)
        vad.feed(loud)
        assertEquals(VoiceActivityDetector.VadState.SPEECH, vad.state.value)
        assertTrue(vad.hasDetectedSpeech)
    }

    @Test
    fun `reset clears speech detection`() {
        val vad = VoiceActivityDetector(speechThreshold = 0.02f, speechOnsetMs = 0)
        // Force-detect speech.
        val loud = ShortArray(1600) { Short.MAX_VALUE }
        vad.feed(loud)
        assertTrue(vad.hasDetectedSpeech)
        vad.reset()
        assertFalse(vad.hasDetectedSpeech)
        assertEquals(VoiceActivityDetector.VadState.SILENCE, vad.state.value)
    }

    @Test
    fun `speech followed by silence transitions back to SILENCE`() {
        val vad = VoiceActivityDetector(
            speechThreshold = 0.02f,
            silenceThreshold = 0.005f,
            speechOnsetMs = 0,
            silenceOffsetMs = 0,
        )
        val loud = ShortArray(1600) { Short.MAX_VALUE }
        val silent = ShortArray(1600)
        // Trigger speech.
        vad.feed(loud)
        assertEquals(VoiceActivityDetector.VadState.SPEECH, vad.state.value)
        // Feed silence — should transition back immediately because offset is 0.
        vad.feed(silent)
        assertEquals(VoiceActivityDetector.VadState.SILENCE, vad.state.value)
    }

    @Test
    fun `empty pcm array does not crash`() {
        val vad = VoiceActivityDetector()
        vad.feed(ShortArray(0))
        assertEquals(VoiceActivityDetector.VadState.SILENCE, vad.state.value)
    }
}
