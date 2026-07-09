package com.omniclaw.app.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.omniclaw.app.R
import com.omniclaw.app.agent.AgentLoop
import com.omniclaw.app.data.session.SessionRepository
import com.omniclaw.app.voice.AudioRecorder
import com.omniclaw.app.voice.SttClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Floating push-to-talk bubble overlay.
 *
 * Implements the original X-OmniClaw speech-to-action flow:
 *   1. Press-and-hold the bubble -> AudioRecorder starts capturing mic input
 *   2. Release -> audio file is sent to SttClient (SiliconFlow SenseVoice)
 *   3. Transcribed text is dispatched to AgentLoop.start() as a user message
 *
 * Per the 2026-03-25 "speech-vision spine" update, speech and text share one
 * execution core — both paths converge at AgentLoop.start().
 */
@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var audioRecorder: AudioRecorder
    @Inject lateinit var sttClient: SttClient
    @Inject lateinit var agentLoop: AgentLoop
    @Inject lateinit var sessions: SessionRepository

    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        bubble = buildBubble()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 32
            y = 200
        }
        runCatching { windowManager?.addView(bubble, params) }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { if (audioRecorder.isRecording()) audioRecorder.stop() }
        runCatching { bubble?.let { windowManager?.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    private fun buildBubble(): View {
        val tv = TextView(this).apply {
            text = "PUSH"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 10f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.overlay_bubble_bg)
            // Use dp, not raw pixels. Previously pad=24 was 24px (~8dp on a
            // 3x density device), making the bubble's touch target too small.
            val dm = resources.displayMetrics
            val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, dm).toInt()
            setPadding(pad, pad, pad, pad)
            // Enforce a 48dp minimum touch target for accessibility.
            val minTouch = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, dm).toInt()
            layoutParams = LinearLayout.LayoutParams(minTouch, minTouch)
        }
        tv.setOnTouchListener(object : View.OnTouchListener {
            private var rawStartX = 0f
            private var rawStartY = 0f
            private var moved = false
            private var recording = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        rawStartX = e.rawX; rawStartY = e.rawY
                        moved = false
                        // Pre-flight: check RECORD_AUDIO permission before
                        // starting. If missing, show a toast instead of
                        // silently failing (the user would press-and-hold
                        // with no feedback).
                        val hasMicPermission = ContextCompat.checkSelfPermission(
                            this@OverlayService, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasMicPermission) {
                            (v as TextView).text = "!"
                            android.widget.Toast.makeText(
                                this@OverlayService,
                                "Microphone permission required. Open Settings → Permissions → Microphone.",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            vibrate(50)
                            return true
                        }
                        // Start recording
                        recording = audioRecorder.start() != null
                        if (recording) {
                            (v as TextView).text = "REC"
                            v.announceForAccessibility("Recording")
                            vibrate(20)
                        } else {
                            // start() failed (mic busy or hardware error)
                            android.widget.Toast.makeText(
                                this@OverlayService,
                                "Could not start recording. Microphone may be in use.",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - rawStartX
                        val dy = e.rawY - rawStartY
                        if (dx * dx + dy * dy > 25 * 25) moved = true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (recording) {
                            val audioFile = audioRecorder.stop()
                            (v as TextView).text = "PUSH"
                            if (!moved && audioFile != null) {
                                vibrate(15)
                                transcribeAndDispatch(audioFile)
                            }
                            recording = false
                        }
                        if (!moved) v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (recording) {
                            audioRecorder.cancel()
                            (v as TextView).text = "PUSH"
                            recording = false
                        }
                    }
                }
                return true
            }
        })
        return tv
    }

    private fun transcribeAndDispatch(audioFile: File) {
        scope.launch {
            (bubble as? TextView)?.text = "…"
            val transcript = sttClient.transcribe(audioFile)
            // Clean up the temp file regardless of outcome.
            runCatching { audioFile.delete() }
            if (transcript.isNullOrBlank()) {
                Log.w(TAG, "STT returned empty transcript")
                android.widget.Toast.makeText(
                    this@OverlayService,
                    "Could not transcribe audio. Check your STT API key and network.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                (bubble as? TextView)?.text = "PUSH"
                return@launch
            }
            Log.i(TAG, "STT transcript received (${transcript.length} chars)")
            // Speech-vision spine: dispatch to the shared execution core.
            val session = sessions.create("Voice: ${transcript.take(40)}")
            agentLoop.start(session, transcript)
            (bubble as? TextView)?.text = "PUSH"
        }
    }

    private fun vibrate(ms: Long) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Vibrator::class.java))?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    companion object {
        private const val TAG = "OverlayService"
        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, OverlayService::class.java)
            runCatching { ctx.startService(i) }
        }
        fun stop(ctx: android.content.Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
