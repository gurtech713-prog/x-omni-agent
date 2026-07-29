package com.omniclaw.app.vision

import com.omniclaw.app.data.prefs.SettingsRepository
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vision LLM client for screenshot / frame understanding.
 *
 * Implements the "vision fallback" half of the X-OmniClaw dual-track decisions:
 * when the structured accessibility tree is empty / messy / unparseable, the
 * agent loop can capture a screenshot and ask the VLM "what do you see, and
 * what should I tap?".
 *
 * Compatible with any OpenAI-style /chat/completions endpoint that supports
 * image_url content parts (OpenRouter Qwen-VL, GPT-4o, Claude, etc.).
 *
 * This class is a thin facade over [VisionPipeline] which handles
 * preprocessing, caching, retry, and response parsing. The http / json /
 * settings dependencies are retained for backward compatibility with
 * existing DI bindings and for the legacy [describeFile] entry point.
 *
 * SCREENSHOT ANNOTATION: [describeWithAnnotation] overlays bounding boxes and
 * labels from the accessibility tree onto the screenshot before sending it to
 * the VLM. This dramatically improves coordinate accuracy because the VLM can
 * see exactly where each interactive element is, rather than guessing from
 * raw pixels.
 */
@Singleton
class VlmClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
    private val pipeline: VisionPipeline,
) {

    /**
     * Ask the VLM a question about an image (compressed bytes — WebP or PNG).
     *
     * Delegates to [VisionPipeline] which handles preprocessing, caching,
     * retry, and response parsing.
     */
    suspend fun describe(
        pngBytes: ByteArray,
        question: String,
    ): String? = pipeline.describe(pngBytes, question)

    /** Convenience: load an image from a file path and ask the VLM about it. */
    suspend fun describeFile(path: String, question: String): String? {
        val bytes = runCatching {
            java.io.File(path).readBytes()
        }.getOrNull() ?: return null
        return describe(bytes, question)
    }

    /**
     * Ask the VLM about a screenshot WITH accessibility-tree annotations
     * overlaid. Each [Annotation] draws a colored bounding box + label on
     * the image before it's sent to the vision model.
     *
     * This improves VLM accuracy because the model can see:
     *   - Where each interactive element is (exact bounds)
     *   - What text/label each element has
     *   - The TAP coordinate for each element
     *
     * The annotated image is sent with the question, and the VLM's response
     * can reference the annotated coordinates directly.
     */
    suspend fun describeWithAnnotation(
        pngBytes: ByteArray,
        question: String,
        annotations: List<Annotation>,
    ): String? {
        if (annotations.isEmpty()) return describe(pngBytes, question)
        val annotatedBytes = runCatching { annotateImage(pngBytes, annotations) }.getOrNull()
            ?: return describe(pngBytes, question)
        // Include annotation context in the prompt so the VLM knows what the
        // boxes mean.
        val contextPrompt = buildString {
            append(question)
            append("\n\nAnnotated elements on the screenshot:")
            annotations.forEachIndexed { i, a ->
                append("\n[${i + 1}] ${a.label} at (${a.x},${a.y}) bounds=[${a.left},${a.top},${a.right},${a.bottom}]")
            }
        }
        return pipeline.describe(annotatedBytes, contextPrompt)
    }

    /**
     * One annotation to draw on a screenshot. [left/top/right/bottom] are in
     * pixel coordinates matching the screenshot bitmap. [x]/[y] are the TAP
     * target (center). [label] is the text to draw next to the box.
     */
    data class Annotation(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val x: Int,
        val y: Int,
        val label: String,
    )

    /**
     * Draw bounding boxes + labels on the image. Returns PNG bytes.
     */
    private fun annotateImage(pngBytes: ByteArray, annotations: List<Annotation>): ByteArray {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            ?: return pngBytes
        val mutable = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(mutable)
        val boxPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.RED
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.RED
            textSize = 28f
            isAntiAlias = true
            setShadowLayer(2f, 1f, 1f, android.graphics.Color.BLACK)
        }
        val dotPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.RED
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
        annotations.forEach { a ->
            // Draw bounding box
            canvas.drawRect(a.left.toFloat(), a.top.toFloat(), a.right.toFloat(), a.bottom.toFloat(), boxPaint)
            // Draw TAP dot at center
            canvas.drawCircle(a.x.toFloat(), a.y.toFloat(), 6f, dotPaint)
            // Draw label
            canvas.drawText(a.label.take(30), a.left.toFloat(), (a.top - 5).coerceAtLeast(20).toFloat(), textPaint)
        }
        val out = java.io.ByteArrayOutputStream()
        mutable.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
        mutable.recycle()
        if (mutable !== bitmap) bitmap.recycle()
        return out.toByteArray()
    }

    /** Clear the underlying vision cache (e.g. on memory pressure). */
    fun clearCache() = pipeline.clearCache()

    /** Number of cached vision responses. */
    val cacheSize: Int get() = pipeline.cacheSize
}
