package com.omniclaw.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Battery optimization exemption helper.
 *
 * Requests ignore battery optimizations for the agent foreground service.
 * Without this, Android may kill the app in the background to save battery.
 */
@Singleton
class BatteryOptimizationHelper @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    /** Check if the app is exempt from battery optimizations. */
    fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true

        val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = ctx.packageName
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    /** Request battery optimization exemption. */
    fun requestExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (!powerManager.isIgnoringBatteryOptimizations(ctx.packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${ctx.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            runCatching { ctx.startActivity(intent) }
                .onFailure { Log.e(TAG, "Battery exemption intent unresolvable", it) }
        }
    }

    /** Open device settings for manual battery optimization configuration. */
    fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            runCatching { ctx.startActivity(intent) }
                .onFailure { Log.e(TAG, "Battery settings intent unresolvable", it) }
        }
    }

    companion object {
        private const val TAG = "BatteryOptimizationHelper"
    }
}
