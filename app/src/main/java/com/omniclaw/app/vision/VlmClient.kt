package com.omniclaw.app.vision

import android.util.Base64
import com.omniclaw.app.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
 */
@Singleton
class VlmClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) {

    /**
     * Ask the VLM a question about an image (PNG bytes).
     * Returns the model's text answer, or null on failure.
     */
    suspend fun describe(
        pngBytes: ByteArray,
        question: String,
    ): String? = withContext(Dispatchers.IO) {
        val cfg = settings.modelConfig.first()
        if (cfg.vlmApiKey.isBlank() || cfg.vlmBaseUrl.isBlank()) return@withContext null

        val b64 = Base64.encodeToString(pngBytes, Base64.NO_WRAP)
        val dataUri = "data:image/png;base64,$b64"

        val payload = buildJsonObject {
            put("model", cfg.vlmModel)
            put("max_tokens", 1024)
            put("temperature", 0.1)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", question)
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", dataUri) }
                        })
                    }
                })
            }
        }

        val req = Request.Builder()
            .url(cfg.vlmBaseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${cfg.vlmApiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val body = r.body?.string().orEmpty()
                val obj = json.parseToJsonElement(body).jsonObject
                obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            }
        }.getOrNull()
    }

    /**
     * Convenience: load a PNG from a file path and ask the VLM about it.
     */
    suspend fun describeFile(path: String, question: String): String? {
        val bytes = runCatching {
            java.io.File(path).readBytes()
        }.getOrNull() ?: return null
        return describe(bytes, question)
    }
}
