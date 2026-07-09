package com.omniclaw.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.omniclaw.app.MainActivity
import com.omniclaw.app.OmniApplication
import com.omniclaw.app.R
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * MediaProjection screen-capture service.
 *
 * Captures the device's screen continuously and exposes the latest frame to
 * the agent loop via [latestFramePng]. Used by the VLM vision fallback when
 * the accessibility tree alone isn't enough to understand the current screen.
 *
 * Lifecycle:
 *   1. Activity calls [requestPermission] -> user grants via system dialog
 *   2. Activity forwards the result Intent to [startWithPermission]
 *   3. Service starts foreground + creates MediaProjection + ImageReader
 *   4. Each new frame is PNG-encoded into [latestFramePng]
 *   5. Stop via [stop] or unbind
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    /** Dedicated background thread for ImageReader callbacks + PNG encoding — never blocks the UI. */
    private val captureThread = HandlerThread("screen-capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val latestFrame = AtomicReference<ByteArray?>(null)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Register this instance so the agent loop can pull the latest frame
        // via ScreenCaptureService.latestFramePng().
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // On Android 14+ (API 34) we MUST pass the foregroundServiceType to
        // startForeground() — the 2-arg form throws ForegroundServiceTypeNotAllowed.
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
        if (intent?.hasExtra(EXTRA_RESULT_DATA) == true) {
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (data != null) startCapture(data)
        }
        return START_STICKY
    }

    private fun startCapture(resultData: Intent) {
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(android.app.Activity.RESULT_OK, resultData)
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            // Use the dedicated background HandlerThread — PNG encoding is expensive
            // and would jank the UI if run on the main looper.
            imageReader?.setOnImageAvailableListener({ reader ->
                val image: Image? = reader.acquireLatestImage()
                if (image != null) {
                    runCatching {
                        val bytes = imageToPng(image)
                        if (bytes != null && bytes.isNotEmpty()) latestFrame.set(bytes)
                    }
                    image.close()
                }
            }, captureHandler)
            virtualDisplay = projection?.createVirtualDisplay(
                "OmniScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null, mainHandler,
            )
            Log.i(TAG, "Screen capture started: ${width}x${height}")
        } catch (e: Exception) {
            Log.w(TAG, "startCapture failed: ${e.message}")
        }
    }

    /** Convert an RGBA_8888 Image to PNG bytes. */
    private fun imageToPng(image: Image): ByteArray? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        buffer.rewind()
        bmp.copyPixelsFromBuffer(buffer)
        // Crop to actual width
        val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
        bmp.recycle()
        val out = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.PNG, 80, out)
        cropped.recycle()
        return out.toByteArray()
    }

    override fun onDestroy() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        projection = null
        runCatching { captureThread.quitSafely() }
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, OmniApplication.CHANNEL_AGENT)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(R.drawable.ic_omni_mono)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "ScreenCapture"
        private const val NOTIF_ID = 0xC4A2
        private const val EXTRA_RESULT_DATA = "result_data"

        @Volatile private var instance: ScreenCaptureService? = null

        fun isRunning(): Boolean = instance != null

        /** The latest captured frame as PNG bytes, or null if capture isn't active. */
        fun latestFramePng(): ByteArray? = instance?.latestFrame?.get()

        fun startWithPermission(ctx: Context, resultData: Intent) {
            // instance will be assigned in onCreate() when the service binds.
            val i = Intent(ctx, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        }
    }
}
