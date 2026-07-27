package com.omniclaw.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.omniclaw.app.OmniApplication
import com.omniclaw.app.R
import com.omniclaw.app.agent.AgentLoop
import com.omniclaw.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dynamic Island / Android Halo — a floating pill overlay that shows live
 * agent status. Inspired by iOS Dynamic Island, adapted for Android via
 * SYSTEM_ALERT_WINDOW.
 *
 * States:
 *   - IDLE     : compact pill with three claw marks — "▌▌▌"
 *   - RUNNING  : expands to show "AGENT · step 3/24 · 1.2k tok" + pulsing dot
 *   - EXPANDED : full view with last thought + current action
 *   - DONE     : briefly shows "✓ DONE" then collapses back to idle
 *   - FAILED   : briefly shows "✗ ERR" then collapses back to idle
 *
 * Behavior:
 *   - Tap to expand/collapse
 *   - Long-press to drag (reposition)
 *   - Auto-starts when the first agent session becomes active
 *   - Auto-stops when no sessions are running
 *
 * Stays pure B&W: black background + white text, or white background + black
 * text (follows system dark/light theme via the app's theme).
 */
@AndroidEntryPoint
class HaloOverlayService : Service() {

    @Inject lateinit var agentLoop: AgentLoop

    private var windowManager: WindowManager? = null
    private var halo: HaloView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager

        // H-20: promote to a specialUse foreground service so the halo isn't
        // killed on Android 14+. Shares the agent notification channel; the
        // manifest declares foregroundServiceType="specialUse" for this service.
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
            Log.e(TAG, "Failed to promote HaloOverlayService to foreground: ${'$'}{e.message}", e)
        }
        halo = HaloView(this).also { it.attach() }
        // Subscribe to agent events to drive the halo state.
        scope.launch {
            agentLoop.events.collect { e ->
                halo?.onAgentEvent(e)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // Cancel any pending handler callbacks (e.g. the 2.5s/3.5s delayed
        // IDLE transitions). Without this, the runnables hold a reference to
        // the destroyed HaloView (inner class → outer Service) and leak.
        handler.removeCallbacksAndMessages(null)
        runCatching { halo?.detach() }
        halo = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Display any status text pushed via ServiceGateway.showHaloStatus (audit M-44).
        val status = intent?.getStringExtra(EXTRA_STATUS)
        if (!status.isNullOrEmpty()) {
            halo?.showStatus(status)
        }
        return START_STICKY
    }

    /**
     * The Halo view — a pill-shaped overlay with three states.
     * Implemented with platform Views (no Compose in overlay services — keeps
     * it lightweight and avoids Compose's lifecycle requirements).
     */
    private inner class HaloView(private val ctx: Context) {
        private var rootView: View? = null
        private var titleText: TextView? = null
        private var subtitleText: TextView? = null
        private var dotView: View? = null
        private var expanded = false
        private var currentState: HaloState = HaloState.IDLE

        private val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Use TOP|START from the start so the x/y are always offsets from
            // the top-left corner. We center horizontally after layout (see
            // attach()). Previously this used TOP|CENTER_HORIZONTAL and the
            // touch handler flipped gravity to TOP|START on the first MOVE —
            // but the captured initialX was a center-offset, so the first
            // drag delta caused the halo to jump to a wrong position.
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = getStatusBarHeight(ctx) + dp(8)
        }

        fun attach() {
            if (rootView != null) return
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), dp(7), dp(14), dp(7))
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(com.omniclaw.app.R.drawable.halo_bg)
            }
            // Pulsing status dot
            val dot = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                    marginEnd = dp(8)
                }
                setBackgroundResource(com.omniclaw.app.R.drawable.halo_dot)
            }
            // Title text (e.g. "AGENT", "DONE", "ERR")
            // Text color must adapt to the halo background. The halo_bg
            // drawable is a dark pill, so the text is white. But if the
            // device is in light theme and the halo_bg were light, white
            // text would be invisible. We read the system night-mode to
            // pick the right color. (The halo_bg drawable itself is dark
            // in both themes, so white text is correct here — but we guard
            // against future theme changes.)
            val isDarkTheme = (ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val titleColor = if (isDarkTheme) Color.WHITE else Color.BLACK
            val subtitleColor = if (isDarkTheme) 0xCCFFFFFF.toInt() else 0xCC000000.toInt()
            val title = TextView(ctx).apply {
                text = "▌▌▌"
                setTextColor(titleColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.15f
            }
            // Subtitle (step / token info) — hidden when collapsed
            val subtitle = TextView(ctx).apply {
                text = ""
                setTextColor(subtitleColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.1f
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(10) }
            }
            root.addView(dot)
            root.addView(title)
            root.addView(subtitle)
            rootView = root
            titleText = title
            subtitleText = subtitle
            dotView = dot

            // Center horizontally after the first layout pass. Under
            // TOP|START gravity, x is the left offset; centering means
            // x = (screenWidth - viewWidth) / 2.
            root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                ) {
                    val w = right - left
                    if (w > 0) {
                        val dm = ctx.resources.displayMetrics
                        layoutParams.x = ((dm.widthPixels - w) / 2).coerceAtLeast(0)
                        runCatching { windowManager?.updateViewLayout(root, layoutParams) }
                        root.removeOnLayoutChangeListener(this)
                    }
                }
            })

            // Touch handling: tap = expand/collapse, no dragging (make still)
            root.setOnTouchListener(object : View.OnTouchListener {
                private var downTime = 0L

                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downTime = System.currentTimeMillis()
                        }
                        MotionEvent.ACTION_UP -> {
                            if (System.currentTimeMillis() - downTime < 300) {
                                v.performClick()
                                toggleExpanded()
                            }
                        }
                    }
                    return true
                }
            })

            runCatching { windowManager?.addView(root, layoutParams) }
        }

        fun detach() {
            runCatching { rootView?.let { windowManager?.removeView(it) } }
            rootView = null
        }

        fun onAgentEvent(e: AgentLoop.Event) {
            handler.post {
                when (e) {
                    is AgentLoop.Event.StepStarted -> {
                        setState(HaloState.RUNNING, "AGENT", "step ${e.step}/24")
                        startPulse()
                    }
                    is AgentLoop.Event.Thought -> {
                        if (expanded) {
                            subtitleText?.text = "step ${e.step} · ${e.text.take(60)}"
                        }
                    }
                    is AgentLoop.Event.ToolCall -> {
                        if (expanded) {
                            subtitleText?.text = "→ ${e.call.name.take(40)}"
                        }
                    }
                    is AgentLoop.Event.StepFinished -> {
                        subtitleText?.text = "step ${e.step}/24 · ${e.usage.totalTokens} tok"
                    }
                    is AgentLoop.Event.LessonsApplied -> {
                        // Briefly show how many lessons were applied from past
                        // sessions — transparency for the self-learning loop.
                        if (expanded) {
                            subtitleText?.text = "applied ${e.lessonCount} lessons"
                        }
                    }
                    is AgentLoop.Event.Completed -> {
                        setState(HaloState.DONE, "DONE", null)
                        stopPulse()
                        handler.postDelayed({ setState(HaloState.IDLE, "▌▌▌", null) }, 2500)
                    }
                    is AgentLoop.Event.Failed -> {
                        setState(HaloState.FAILED, "ERR", e.error.take(40))
                        stopPulse()
                        handler.postDelayed({ setState(HaloState.IDLE, "▌▌▌", null) }, 3500)
                    }
                    is AgentLoop.Event.LoopDetected -> {
                        setState(HaloState.FAILED, "LOOP", "stuck — retrying")
                        stopPulse()
                    }
                    is AgentLoop.Event.Stopped -> {
                        setState(HaloState.IDLE, "▌▌▌", null)
                        stopPulse()
                    }
                    is AgentLoop.Event.SkillComplete -> {
                        if (expanded) {
                            subtitleText?.text = "${e.skillId}: done"
                        }
                    }
                }
            }
        }

        /** Display a dynamic status line pushed via ServiceGateway.showHaloStatus (audit M-44). */
        fun showStatus(text: String) {
            setState(HaloState.RUNNING, "AGENT", text)
            subtitleText?.text = text
            subtitleText?.visibility = View.VISIBLE
        }

        /** Re-read the system night-mode and re-apply title/subtitle colors so a
         *  theme toggle while the halo is visible never leaves invisible text (audit M-23). */
        private fun applyThemeColors() {
            val isDarkTheme = (ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            titleText?.setTextColor(if (isDarkTheme) Color.WHITE else Color.BLACK)
            subtitleText?.setTextColor(if (isDarkTheme) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
        }

        private fun setState(state: HaloState, title: String, subtitle: String?) {
            applyThemeColors()
            currentState = state
            titleText?.text = title
            if (subtitle != null && expanded) {
                subtitleText?.text = subtitle
                subtitleText?.visibility = View.VISIBLE
            } else if (subtitle == null) {
                // Clear stale subtitle text whenever a state passes null,
                // so expanding later never shows outdated content.
                subtitleText?.text = ""
                if (!expanded) subtitleText?.visibility = View.GONE
            }
        }

        private fun toggleExpanded() {
            applyThemeColors()
            // Instantly bring the app to the foreground on any click of the Halo Notch!
            val intent = Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { ctx.startActivity(intent) }

            expanded = !expanded
            subtitleText?.visibility = if (expanded) View.VISIBLE else View.GONE
            if (expanded) {
                // Show a meaningful default when expanding during IDLE
                // instead of stale text from a previous state.
                if (subtitleText?.text.isNullOrEmpty()) {
                    subtitleText?.text = when (currentState) {
                        HaloState.IDLE -> "tap to open app"
                        else -> "tap to open app"
                    }
                }
            } else {
                subtitleText?.text = ""
            }
            rootView?.let { root ->
                root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                    ) {
                        val w = right - left
                        if (w > 0) {
                            val dm = ctx.resources.displayMetrics
                            layoutParams.x = ((dm.widthPixels - w) / 2).coerceAtLeast(0)
                            runCatching { windowManager?.updateViewLayout(root, layoutParams) }
                            root.removeOnLayoutChangeListener(this)
                        }
                    }
                })
                root.requestLayout()
                runCatching { windowManager?.updateViewLayout(root, layoutParams) }
            }
        }

        private var pulseRunnable: Runnable? = null
        private fun startPulse() {
            stopPulse()
            val r = object : Runnable {
                override fun run() {
                    val dot = dotView ?: return
                    // Smooth sinusoidal pulse between 0.3 and 1.0 alpha.
                    // Previously this flipped between 1.0 and 0.25 every
                    // 600ms — a harsh on/off flicker uncomfortable for
                    // photosensitive users. Now we step through 8 phase
                    // levels over ~1.6s for a gentle breathing effect.
                    val phase = ((System.currentTimeMillis() % 1600) / 200).toInt()
                    val t = (kotlin.math.sin(phase * Math.PI / 4.0) * 0.5 + 0.5).toFloat()
                    dot.alpha = 0.3f + 0.7f * t
                    handler.postDelayed(this, 200)
                }
            }
            pulseRunnable = r
            handler.post(r)
        }
        private fun stopPulse() {
            pulseRunnable?.let { handler.removeCallbacks(it) }
            pulseRunnable = null
            dotView?.alpha = 1.0f
        }

        private fun getStatusBarHeight(ctx: Context): Int {
            val resourceId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (resourceId > 0) {
                ctx.resources.getDimensionPixelSize(resourceId)
            } else {
                dp(36)
            }
        }

        private fun dp(value: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics
        ).toInt()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, OmniApplication.CHANNEL_AGENT)
            .setContentTitle("Omni Halo")
            .setContentText("Agent status overlay is active.")
            .setSmallIcon(R.drawable.ic_omni_mono)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

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

    private enum class HaloState { IDLE, RUNNING, DONE, FAILED }

    companion object {
        @Volatile private var instance: HaloOverlayService? = null
        fun isRunning(): Boolean = instance != null
        const val EXTRA_STATUS = "com.omniclaw.app.service.HaloOverlayService.EXTRA_STATUS"
        private const val TAG = "HaloOverlayService"
        private const val NOTIF_ID = 0x0B52

        fun start(ctx: Context, statusText: String? = null) {
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
            val i = Intent(ctx, HaloOverlayService::class.java)
            if (statusText != null) i.putExtra(EXTRA_STATUS, statusText)
            // H-20: the service promotes itself to a specialUse foreground
            // service in onCreate, so start it as a foreground service on O+.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HaloOverlayService::class.java))
        }
    }
}
