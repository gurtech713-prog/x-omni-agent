package com.omniclaw.app.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records audio from the microphone to a temp file. Used by the push-to-talk
 * bubble (OverlayService) and the speech-vision spine.
 *
 * Output format: AAC LC in MP4 container — widely accepted by cloud STT APIs
 * (SiliconFlow SenseVoice, OpenAI Whisper, etc.).
 */
@Singleton
class AudioRecorder @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val ctx: Context,
) {

    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var outputFile: File? = null

    fun start(): File? {
        if (recorder != null) return outputFile
        val out = File(ctx.cacheDir, "asr_${System.currentTimeMillis()}.m4a")
        val r = newRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(16_000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            Log.w(TAG, "start() failed: ${e.message}")
            runCatching { r.release() }
            // Clean up the orphaned output file. prepare() may have already
            // created a (possibly partial) file on disk; without this cleanup
            // repeated failed starts leak files in cacheDir indefinitely.
            runCatching { if (out.exists()) out.delete() }
            return null
        }
        outputFile = out
        recorder = r
        return out
    }

    fun stop(): File? {
        val r = recorder ?: return null
        val out = outputFile
        val stopOk = runCatching {
            r.stop()
            true
        }.getOrElse {
            // stop() throws RuntimeException if called before any audio was
            // recorded (e.g. a very short press). The output file is invalid
            // in that case — delete it so the caller doesn't send a corrupt
            // file to the STT API and waste a network round-trip.
            Log.w(TAG, "stop() failed (short recording?): ${it.message}")
            runCatching { out?.let { f -> if (f.exists()) f.delete() } }
            false
        }
        runCatching { r.release() }
        recorder = null
        outputFile = null
        return if (stopOk) out else null
    }

    fun cancel() {
        val r = recorder ?: return
        runCatching { r.stop() }
        runCatching { r.release() }
        // Also delete the partial file on cancel — the recording is discarded.
        runCatching { outputFile?.let { if (it.exists()) it.delete() } }
        recorder = null
        outputFile = null
    }

    fun isRecording(): Boolean = recorder != null

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx)
        else @Suppress("DEPRECATION") MediaRecorder()

    companion object {
        private const val TAG = "AudioRecorder"
    }
}
