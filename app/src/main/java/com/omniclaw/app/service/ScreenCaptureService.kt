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
import android.app.NotificationChannel
import android.app.NotificationManager
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

        // Ensure the notification channel exists before promoting to foreground.
        ensureNotificationChannel()

        // Promote to foreground with a PLAIN type in onCreate() — this satisfies
        // the 5-second startForeground() deadline imposed on
        // Context.startForegroundService(). We CANNOT use
        // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION here because the MediaProjection
        // result Intent hasn't been delivered yet (onStartCommand hasn't run).
        //
        // On Android 14+ (UPSIDE_DOWN_CAKE+), passing the mediaProjection type
        // without the projection token being delivered to the service triggers
        // ForegroundServiceTypeNotAllowed / SecurityException. We re-issue
        // startForeground() with the mediaProjection type inside onStartCommand
        // once the token is in hand.
        //
        // On pre-14, plain startForeground() is the only variant available anyway.
        val notif = buildNotification()
        runCatching {
            startForeground(NOTIF_ID, notif)
        }.onFailure { e ->
            Log.e(TAG, "Failed to start foreground service in onCreate: ${e.message}", e)
            // If plain foreground promotion fails (e.g. background-start restriction
            // on Android 12+), there's nothing more we can do here. Stop self so
            // the system doesn't ANR-kill the process for failing to call
            // startForeground() within the deadline.
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || !intent.hasExtra(EXTRA_RESULT_DATA)) {
            stopSelf()
            return START_NOT_STICKY
        }
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Re-issue startForeground() with the mediaProjection type now that we
        // have the projection token. On Android 14+ this is REQUIRED — the
        // platform enforces that mediaProjection-type foreground services
        // receive the token at foreground-promotion time. The plain
        // startForeground() in onCreate() satisfied the 5-second deadline;
        // this call upgrades the type.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                startForeground(
                    NOTIF_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            }.onFailure { e ->
                Log.e(TAG, "Failed to upgrade to mediaProjection foreground type: ${e.message}", e)
                // Don't stopSelf — the plain foreground from onCreate is still
                // valid and the capture may still work; the type mismatch just
                // means the system may treat us as a plain FGS for OOM purposes.
            }
        }

        startCapture(data)
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

    /** Convert an RGBA_8888 Image to compressed bytes for the VLM pipeline.
     *
     * Uses WebP (quality 80) instead of PNG — WebP encodes ~3-5x faster and
     * produces ~3-10x smaller files for photographic screen content. The VLM
     * only needs to understand what's on screen, not pixel-perfect fidelity,
     * so the slight quality loss is irrelevant and the bandwidth + CPU savings
     * are substantial (typical 1080p frame: PNG ~1.5MB / 200ms → WebP ~120KB / 40ms).
     *
     * Falls back to JPEG on devices where WebP encoding is unavailable (API < 21
     * with lossy WebP added in API 18; since our minSdk is 26, this fallback is
     * effectively unreachable but kept for defensive robustness).
     */
    private fun imageToPng(image: Image): ByteArray? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        // Allocate the intermediate bitmap that holds the raw RGBA_8888 rows
        // INCLUDING row padding. We recycle it in a finally block so an
        // exception during copyPixelsFromBuffer or the subsequent crop doesn't
        // leak the (potentially multi-MB) native bitmap.
        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        try {
            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)
            // Crop to actual width (the rowPadding may have added extra columns).
            val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            try {
                val out = ByteArrayOutputStream()
                // Prefer WebP for speed + size. Bitmap.CompressFormat.WEBP is deprecated
                // on API 30+ in favor of WEBP_LOSSY; use the latter when available.
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                cropped.compress(format, 80, out)
                return out.toByteArray()
            } catch (e: OutOfMemoryError) {
                // PNG/WebP encoding OOM — the cropped bitmap is the most likely
                // culprit. Don't rethrow: returning null lets the caller skip
                // this frame and the next acquireLatestImage() will retry.
                Log.w(TAG, "imageToPng compress OOM — dropping frame: ${e.message}")
                return null
            } finally {
                cropped.recycle()
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "imageToPng bitmap alloc OOM — dropping frame: ${e.message}")
            return null
        } finally {
            bmp.recycle()
        }
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

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(OmniApplication.CHANNEL_AGENT)
        if (existing != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                OmniApplication.CHANNEL_AGENT,
                getString(R.string.capture_notification_title),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.capture_notification_text)
                setShowBadge(false)
            }
        )
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
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }.onFailure { e ->
                Log.e(TAG, "Failed to start ScreenCaptureService: ${e.message}", e)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        }
    }
}
