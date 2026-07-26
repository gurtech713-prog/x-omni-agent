package com.omniclaw.app.vision

import com.omniclaw.app.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds VLM request payloads (OpenAI-compatible /chat/completions format).
 *
 * Decouples payload construction from the HTTP transport so the same builder
 * can be used for both [VlmClient] (OkHttp) and future transports (gRPC,
 * on-device).
 *
 * The builder produces a kotlinx JsonObject ready for serialization.
 */
class VisionRequestBuilder(
    private val settings: SettingsRepository,
) {

    /**
     * Build a vision chat-completion payload for [imageDataUri] + [question].
     *
     * @param imageDataUri A data URI like "data:image/webp;base64,...."
     * @param question The natural-language question about the image.
     * @param maxTokens Response token cap (default 1024).
     * @param temperature Sampling temperature (default 0.1 — low for deterministic descriptions).
     */
    suspend fun build(
        imageDataUri: String,
        question: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.1f,
    ): kotlinx.serialization.json.JsonObject {
        val cfg = settings.modelConfig.first()
        return kotlinx.serialization.json.buildJsonObject {
            put("model", cfg.vlmModel)
            put("max_tokens", maxTokens)
            put("temperature", temperature.toDouble())
            putJsonArray("messages") {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("type", "text")
                            put("text", question)
                        })
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", imageDataUri) }
                        })
                    }
                })
            }
        }
    }
}
