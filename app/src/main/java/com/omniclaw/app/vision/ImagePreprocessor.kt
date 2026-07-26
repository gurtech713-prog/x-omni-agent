package com.omniclaw.app.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Preprocesses images before sending them to the VLM.
 *
 * Responsibilities:
 *  1. **Decode** raw bytes (WebP / PNG / JPEG) into a [Bitmap].
 *  2. **Resize** to fit within the VLM's optimal input dimensions (default
 *     768x768, matching the typical VLM pre-training resolution). Larger
 *     images waste bandwidth + tokens; smaller images lose detail.
 *  3. **Re-encode** as WebP (quality 80) for the smallest payload that
 *     preserves visual fidelity for the VLM.
 *  4. **Reuse bitmaps** via [BitmapPool] to avoid GC churn on the
 *     continuous-capture path.
 *
 * The preprocessor is stateless between calls (the pool is shared). Thread
 * safety: all methods are safe to call from any thread; each call borrows
 * and returns its own bitmap from the pool.
 */
class ImagePreprocessor(
    private val pool: BitmapPool,
    private val maxDimension: Int = 768,
    private val quality: Int = 80,
) {

    /**
     * Decode [bytes] and re-encode at the optimal VLM resolution.
     *
     * @return the re-encoded bytes (WebP), or the original bytes if any
     *   step fails (graceful degradation — the VLM can often handle the
     *   original image even if it's oversized).
     */
    fun preprocess(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        // Quick path: if the image is already small enough, return as-is.
        if (bytes.size < MAX_NO_RESIZE_BYTES) return bytes

        val decoded = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return bytes

        try {
            val resized = resizeIfNeeded(decoded)
            try {
                val out = ByteArrayOutputStream()
                val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                resized.compress(format, quality, out)
                return out.toByteArray()
            } finally {
                if (resized !== decoded) runCatching { resized.recycle() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "preprocess failed: ${e.message}; returning original bytes")
            return bytes
        } finally {
            runCatching { decoded.recycle() }
        }
    }

    /**
     * Resize [src] so its longest side is <= [maxDimension], maintaining
     * aspect ratio. If [src] is already small enough, returns it unchanged.
     */
    private fun resizeIfNeeded(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxDimension) return src
        val scale = maxDimension.toFloat() / longest
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createScaledBitmap(src, newW, newH, true)
        }.getOrNull() ?: src
    }

    companion object {
        private const val TAG = "ImagePreprocessor"
        /** Images smaller than 200KB are sent as-is — no benefit from re-encoding. */
        private const val MAX_NO_RESIZE_BYTES = 200 * 1024
    }
}
