package com.omniclaw.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.omniclaw.app.MainActivity
import com.omniclaw.app.OmniApplication
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    @Inject lateinit var streamingStt: com.omniclaw.app.voice.StreamingSttClient
    @Inject lateinit var agentLoop: AgentLoop
    @Inject lateinit var sessions: SessionRepository

    private var windowManager: WindowManager? = null
    private var bubble: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager

        // Promote to a specialUse foreground service so the overlay isn't killed
        // on Android 14+ (H-20). Shares the agent notification channel. The
        // manifest declares foregroundServiceType="specialUse" plus the required
        // PROPERTY_SPECIAL_USE_FGS_SUBTYPE for this service.
        ensureNotificationChannel()
        runCatching {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to promote OverlayService to foreground: ${e.message}", e)
            // S-H4: don't continue onCreate if foreground promotion failed —
            // the system would kill us within the 5s ANR window. Bail out so
            // we don't attach the bubble view to a service about to die.
            stopSelf()
            return
        }

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
        // Don't silently swallow addView failures (H-19): if the overlay can't be
        // attached (e.g. SYSTEM_ALERT_WINDOW revoked), log it, drop the bubble
        // reference, and stop the service so we don't linger uselessly.
        runCatching { windowManager?.addView(bubble, params) }
            .onFailure { e ->
                Log.e(TAG, "Failed to add overlay bubble view: ${e.message}", e)
                bubble = null
                stopSelf()
            }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { if (audioRecorder.isRecording()) audioRecorder.stop() }
        runCatching { bubble?.let { windowManager?.removeView(it) } }
        bubble = null
        if (instance === this) instance = null
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
            @Volatile private var recording = false
            // S-H5: serialize start/stop so ACTION_UP's stop() always waits
            // for ACTION_DOWN's start() to finish first. Without this, a
            // quick press-and-release races: start() is still running when
            // UP fires, `recording` is still false, so stop() is never
            // called and the recorder is left running.
            private val recorderMutex = Mutex()

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
                        // S-H5: set `recording` synchronously here so the
                        // subsequent ACTION_UP knows to launch a stop()
                        // call — even if the start coroutine hasn't run yet.
                        recording = true
                        // Start recording on IO — MediaRecorder.start() touches the
                        // mic hardware and must not run on the main thread (H-18).
                        // Bubble text is updated back on the main thread.
                        scope.launch(Dispatchers.IO) {
                            recorderMutex.withLock {
                                val started = audioRecorder.start() != null
                                if (!started) recording = false
                                withContext(Dispatchers.Main) {
                                    if (started) {
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
                            }
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - rawStartX
                        val dy = e.rawY - rawStartY
                        if (dx * dx + dy * dy > 25 * 25) moved = true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (recording) {
                            (v as TextView).text = "PUSH"
                            val wasMoved = moved
                            recording = false
                            // Stop recording on IO — MediaRecorder.stop() encodes and
                            // writes the file, which must not block the UI (H-18).
                            // S-H5: serialize with the start coroutine so we never
                            // call stop() before start() has actually begun. The
                            // mutex is released by the start coroutine once it has
                            // either succeeded (recording actually started) or
                            // failed (stop() is then a no-op on an idle recorder).
                            scope.launch(Dispatchers.IO) {
                                recorderMutex.withLock {
                                    val audioFile = audioRecorder.stop()
                                    if (!wasMoved && audioFile != null) {
                                        withContext(Dispatchers.Main) { vibrate(15) }
                                        transcribeAndDispatch(audioFile)
                                    }
                                }
                            }
                        }
                        if (!moved) v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (recording) {
                            (v as TextView).text = "PUSH"
                            recording = false
                            // Cancel on IO for the same reason as start/stop (H-18).
                            // S-H5: serialize with start for the same reason as stop.
                            scope.launch(Dispatchers.IO) {
                                recorderMutex.withLock { audioRecorder.cancel() }
                            }
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
            // Use the production-grade StreamingSttClient with structured results.
            // Guard against unexpected throws so a malformed STT response can't
            // crash the coroutine, and always clean up the temp audio file.
            val result = try {
                streamingStt.transcribeStructured(audioFile)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                runCatching { audioFile.delete() }
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "STT transcribe threw unexpectedly", t)
                runCatching { audioFile.delete() }
                (bubble as? TextView)?.text = "PUSH"
                android.widget.Toast.makeText(
                    this@OverlayService,
                    "STT failed: ${t.message ?: "unknown error"}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            // Clean up the temp file regardless of outcome.
            runCatching { audioFile.delete() }
            val transcript = when (result) {
                is com.omniclaw.app.voice.SttResult.Success -> result.text
                is com.omniclaw.app.voice.SttResult.Cancelled -> {
                    (bubble as? TextView)?.text = "PUSH"
                    return@launch
                }
                is com.omniclaw.app.voice.SttResult.AuthenticationFailure -> {
                    android.widget.Toast.makeText(
                        this@OverlayService,
                        "STT auth failed: ${result.message}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    (bubble as? TextView)?.text = "PUSH"
                    return@launch
                }
                is com.omniclaw.app.voice.SttResult.NetworkFailure -> {
                    android.widget.Toast.makeText(
                        this@OverlayService,
                        "STT network error: ${result.message}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    (bubble as? TextView)?.text = "PUSH"
                    return@launch
                }
                is com.omniclaw.app.voice.SttResult.Timeout -> {
                    android.widget.Toast.makeText(
                        this@OverlayService,
                        "STT timed out. Try again.",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    (bubble as? TextView)?.text = "PUSH"
                    return@launch
                }
                is com.omniclaw.app.voice.SttResult.UnknownFailure -> {
                    Log.w(TAG, "STT unknown failure: ${result.message}", result.cause)
                    android.widget.Toast.makeText(
                        this@OverlayService,
                        "STT failed: ${result.message}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    (bubble as? TextView)?.text = "PUSH"
                    return@launch
                }
                is com.omniclaw.app.voice.SttResult.Segment -> {
                    return@launch
                }
            }
            if (transcript.isBlank()) {
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

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, OmniApplication.CHANNEL_AGENT)
            .setContentTitle("Omni overlay")
            .setContentText("Push-to-talk bubble is active.")
            .setSmallIcon(R.drawable.ic_omni_mono)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(OmniApplication.CHANNEL_AGENT) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                OmniApplication.CHANNEL_AGENT,
                "Omni agent",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "On-device agent services (overlay, capture, automation)."
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIF_ID = 0x0B51
        @Volatile private var instance: OverlayService? = null
        fun isRunning(): Boolean = instance != null

        fun start(ctx: android.content.Context) {
            if (!android.provider.Settings.canDrawOverlays(ctx)) {
                android.widget.Toast.makeText(
                    ctx,
                    "Please grant 'Display over other apps' permission in Settings first.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${ctx.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { ctx.startActivity(intent) }
                return
            }
            val i = Intent(ctx, OverlayService::class.java)
            // The service promotes itself to a specialUse foreground service in
            // onCreate (H-20), so start it as a foreground service on O+.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }
        fun stop(ctx: android.content.Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
