package com.omniclaw.app.vision

import android.util.Base64
import com.omniclaw.app.core.retry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Retry policy for VLM requests.
 *
 * VLM calls are more expensive than text-only LLM calls (image encoding +
 * larger prompts), so we retry less aggressively: 2 attempts with 1.5s
 * backoff. Retries are limited to transient failures (IOException, 5xx,
 * 429) — authentication and client errors propagate immediately.
 */
object VisionRetryPolicy {
    const val MAX_ATTEMPTS = 2
    const val BASE_DELAY_MS = 1500L
    const val MAX_DELAY_MS = 6000L

    fun isRetryable(t: Throwable): Boolean = when (t) {
        is java.io.IOException -> true
        is RuntimeException -> {
            val msg = t.message.orEmpty()
            msg.contains("HTTP 5") || msg.contains("HTTP 429") || msg.contains("HTTP 502") || msg.contains("HTTP 503") || msg.contains("HTTP 504")
        }
        else -> false
    }
}

/**
 * End-to-end vision pipeline: preprocess → encode → cache → upload → parse.
 *
 * This is the production replacement for the ad-hoc logic in [VlmClient.describe].
 * It delegates to:
 *   - [ImagePreprocessor] for resize + re-encode.
 *   - [VisionCache] for response caching.
 *   - [VisionRequestBuilder] for payload construction.
 *   - [VisionResponseParser] for response extraction.
 *   - [VisionRetryPolicy] for transient-failure retry.
 *
 * The pipeline is a [Singleton] so the [VisionCache] is shared across all
 * callers (agent loop, skills, UI). Thread safety: all collaborators are
 * thread-safe; the pipeline itself is stateless.
 */
@Singleton
class VisionPipeline @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val settings: com.omniclaw.app.data.prefs.SettingsRepository,
) {

    // M-17: the fixed-size BitmapPool was injected into ImagePreprocessor but
    // never used (preprocess resizes to a variable aspect ratio that a fixed
    // 768x768 pooled bitmap can't back without padding/crashes). Removed per the
    // finding's 'delete the pool' option; BitmapPool remains available elsewhere.
    private val preprocessor = ImagePreprocessor()
    private val cache = VisionCache()
    private val requestBuilder = VisionRequestBuilder()
    private val parser = VisionResponseParser(json)

    /**
     * Ask the VLM a question about [imageBytes]. Returns the model's text
     * answer, or null on failure (after all retries exhausted).
     *
     * Pipeline:
     *   1. Check [VisionCache] — return immediately on hit.
     *   2. Preprocess (resize + re-encode to WebP).
     *   3. Base64-encode + construct data URI.
     *   4. Build the OpenAI-compat payload via [VisionRequestBuilder].
     *   5. POST to the VLM endpoint with retry per [VisionRetryPolicy].
     *   6. Parse the response via [VisionResponseParser].
     *   7. Cache the result.
     */
    suspend fun describe(imageBytes: ByteArray, question: String): String? {
        // 1. Cache check
        cache.get(imageBytes, question)?.let { return it }

        // 2. Preprocess
        val processed = preprocessor.preprocess(imageBytes)

        // 3. Encode
        val b64 = Base64.encodeToString(processed, Base64.NO_WRAP)
        val mime = sniffImageMime(processed)
        val dataUri = "data:$mime;base64,$b64"

        // 4. Build payload (M-18: read modelConfig ONCE and share it with the
        //    builder instead of each calling settings.modelConfig.first()).
        val cfg = settings.modelConfig.first()
        if (cfg.vlmApiKey.isBlank() || cfg.vlmBaseUrl.isBlank()) return null
        val payload = requestBuilder.build(cfg, dataUri, question)

        // 5. Upload with retry
        val result = runCatching {
            retry(
                maxAttempts = VisionRetryPolicy.MAX_ATTEMPTS,
                baseDelayMs = VisionRetryPolicy.BASE_DELAY_MS,
                maxDelayMs = VisionRetryPolicy.MAX_DELAY_MS,
                retryable = VisionRetryPolicy::isRetryable,
            ) {
                val req = Request.Builder()
                    .url(cfg.vlmBaseUrl.trimEnd('/') + "/chat/completions")
                    .header("Authorization", "Bearer ${cfg.vlmApiKey}")
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
                    val body = r.body?.string().orEmpty()
                    parser.parse(body)?.text
                        ?: throw RuntimeException("Malformed VLM response")
                }
            }
        }.getOrNull()

        // 6. Cache + return
        if (result != null) {
            cache.put(imageBytes, question, result)
        }
        return result
    }

    /**
     * Streaming variant — emits text deltas as the VLM generates them.
     * Currently delegates to the non-streaming path and emits the full
     * result at once; a true SSE streaming implementation would parse
     * the `stream: true` response line-by-line.
     */
    fun stream(imageBytes: ByteArray, question: String): Flow<String> = flow {
        val full = describe(imageBytes, question) ?: return@flow
        // Emit in ~32-char chunks to simulate streaming.
        var i = 0
        while (i < full.length) {
            val end = (i + 32).coerceAtMost(full.length)
            emit(full.substring(i, end))
            i = end
        }
    }.flowOn(Dispatchers.IO)

    /** Clear the vision cache (e.g. on memory pressure). */
    fun clearCache() = cache.clear()

    /** Number of cached vision responses. */
    val cacheSize: Int get() = cache.size

    /**
     * Sniff the image MIME type from the magic byte signature.
     * (Mirrored from VlmClient for the pipeline's internal use.)
     */
    private fun sniffImageMime(bytes: ByteArray): String = when {
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "image/webp"
        bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        else -> "image/png"
    }
}
