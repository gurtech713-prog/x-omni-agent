package com.omniclaw.app.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Voice Activity Detection (VAD) over the microphone stream.
 *
 * Runs alongside [AudioRecorder] and reports whether speech is currently
 * happening, so the STT pipeline can:
 *   - Trim leading/trailing silence from the recording.
 *   - Auto-stop after N ms of continuous silence (end-of-speech detection).
 *   - Suppress the transcription request entirely if no speech was detected
 *     (saves an API call + a network round-trip).
 *
 * Algorithm: RMS (root-mean-square) energy over a sliding window of audio
 * frames. When the RMS exceeds [speechThreshold] for >= [speechOnsetMs],
 * we report VAD_STATE_SPEECH. When it drops below [silenceThreshold] for
 * >= [silenceOffsetMs], we report VAD_STATE_SILENCE.
 *
 * This is a lightweight energy-based VAD — not as accurate as a neural VAD
 * (like Silero), but it has zero native dependencies and runs in <1% CPU.
 * Good enough for the push-to-talk use case where the user is intentionally
 * holding the bubble to speak.
 */
class VoiceActivityDetector(
    private val speechThreshold: Float = 0.02f,
    private val silenceThreshold: Float = 0.008f,
    private val speechOnsetMs: Long = 80L,
    private val silenceOffsetMs: Long = 700L,
    private val sampleRate: Int = 16_000,
) {
    /** Current VAD state — speech or silence. */
    enum class VadState { SILENCE, SPEECH }

    private val _state = MutableStateFlow(VadState.SILENCE)
    val state: StateFlow<VadState> = _state.asStateFlow()

    private var speechStartedAt = 0L
    private var silenceStartedAt = 0L
    private var everDetectedSpeech = false

    /** True if speech was detected at any point since [reset]. */
    val hasDetectedSpeech: Boolean get() = everDetectedSpeech

    /**
     * Feed a chunk of 16-bit PCM audio to the VAD.
     *
     * @param pcm The audio samples (16-bit signed, mono, little-endian).
     * @return the updated [VadState].
     */
    fun feed(pcm: ShortArray): VadState {
        if (pcm.isEmpty()) return _state.value
        val rms = computeRms(pcm)
        val now = System.currentTimeMillis()

        return when (_state.value) {
            VadState.SILENCE -> {
                if (rms >= speechThreshold) {
                    if (speechStartedAt == 0L) speechStartedAt = now
                    if (now - speechStartedAt >= speechOnsetMs) {
                        everDetectedSpeech = true
                        silenceStartedAt = 0L
                        _state.value = VadState.SPEECH
                    }
                } else {
                    speechStartedAt = 0L
                }
                _state.value
            }
            VadState.SPEECH -> {
                if (rms < silenceThreshold) {
                    if (silenceStartedAt == 0L) silenceStartedAt = now
                    if (now - silenceStartedAt >= silenceOffsetMs) {
                        _state.value = VadState.SILENCE
                        speechStartedAt = 0L
                    }
                } else {
                    silenceStartedAt = 0L
                }
                _state.value
            }
        }
    }

    /** Reset the VAD state (call before starting a new recording). */
    fun reset() {
        _state.value = VadState.SILENCE
        speechStartedAt = 0L
        silenceStartedAt = 0L
        everDetectedSpeech = false
    }

    private fun computeRms(samples: ShortArray): Float {
        var sum = 0.0
        for (s in samples) {
            val v = s.toDouble() / Short.MAX_VALUE
            sum += v * v
        }
        return sqrt((sum / samples.size)).toFloat()
    }

    companion object {
        private const val TAG = "VAD"
    }
}
