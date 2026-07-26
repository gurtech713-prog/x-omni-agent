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

    /** Clear the underlying vision cache (e.g. on memory pressure). */
    fun clearCache() = pipeline.clearCache()

    /** Number of cached vision responses. */
    val cacheSize: Int get() = pipeline.cacheSize
}
