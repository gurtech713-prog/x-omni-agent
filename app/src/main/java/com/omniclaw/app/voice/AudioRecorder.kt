package com.omniclaw.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
    // V-H1: guard so a concurrent stop()/cancel()/auto-stop (from the
    // OnInfoListener max-duration callback) can't race a second stop() into
    // deleting a valid recording. compareAndSet(false, true) wins exactly once.
    private val stopping = AtomicBoolean(false)

    @Synchronized
    fun start(): File? {
        // V-M4: explicit RECORD_AUDIO check — without it, prepare()/start()
        // throws RuntimeException("permission denied") which we used to swallow
        // as a generic start() failure, hiding the root cause from callers.
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            return null
        }
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
            // Safety bound (M-22): cap recordings at 60s so a stuck or pocket press
            // can't write a multi-MB file to cacheDir and stress the encoder. The
            // OnInfoListener auto-stops when the limit is hit.
            r.setMaxDuration(MAX_DURATION_MS)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    Log.i(TAG, "Max duration (${MAX_DURATION_MS}ms) reached — auto-stopping")
                    runCatching { stop() }
                        .onFailure { Log.w(TAG, "auto-stop at max duration failed: ${it.message}") }
                }
            }
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

    // V-H1/V-H2: @Synchronized serializes concurrent stop() callers (and the
    // auto-stop callback) so only the first one stops the recorder; subsequent
    // callers see recorder == null and return null without deleting the file.
    @Synchronized
    fun stop(): File? {
        if (!stopping.compareAndSet(false, true)) return null
        try {
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
        } finally {
            stopping.set(false)
        }
    }

    // V-H2: @Synchronized so cancel() can't race a concurrent stop()/start().
    @Synchronized
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
        private const val MAX_DURATION_MS = 60_000
    }
}
