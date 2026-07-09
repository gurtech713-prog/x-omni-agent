package com.omniclaw.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.omniclaw.app.MainActivity
import com.omniclaw.app.OmniApplication
import com.omniclaw.app.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground service that keeps the agent loop alive across long-running tasks
 * and scheduled automation. Started when a session moves into RUNNING state,
 * stopped when no sessions are running.
 */
@AndroidEntryPoint
class AgentForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, OmniApplication.CHANNEL_AGENT)
            .setContentTitle(getString(R.string.fg_service_notification_title))
            .setContentText(getString(R.string.fg_service_notification_text))
            .setSmallIcon(R.drawable.ic_omni_mono)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIF_ID = 0xC1A

        fun start(ctx: Context) {
            val i = Intent(ctx, AgentForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AgentForegroundService::class.java))
        }
    }
}
