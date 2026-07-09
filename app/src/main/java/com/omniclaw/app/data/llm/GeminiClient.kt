package com.omniclaw.app.data.llm

import android.util.Log
import com.omniclaw.app.data.model.LlmUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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

/**
 * Native Google Gemini API client.
 *
 * Talks directly to the Generative Language API (generativelanguage.googleapis.com)
 * using the v1beta REST surface and `x-goog-api-key` header auth.
 *
 * Key fixes vs original:
 *   - temperature / maxOutputTokens are nested under `generationConfig` (required by API)
 *   - convertMessages() merges consecutive same-role turns to satisfy Gemini's
 *     strict alternating user/model requirement (violating it causes HTTP 400)
 *   - Better error logging: full body included in exception message
 */
class GeminiClient(
    private val http: OkHttpClient,
    private val json: Json,
) {

    /** Default API base URL — pinned to v1beta (Gemini 2.x lives there). */
    val defaultBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta"

    /**
     * Non-streaming completion. Mirrors [LlmClient.complete] signature so
     * callers can swap implementations transparently.
     */
    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<LlmClient.Message>,
        temperature: Float = 0.2f,
        maxTokens: Int = 2048,
    ): LlmClient.CompletionResult = withContext(Dispatchers.IO) {
        val (systemInstruction, contents) = convertMessages(messages)
        val payload = buildJsonObject {
            // generationConfig is the correct nesting for these parameters.
            // Placing temperature/maxOutputTokens at the top level is ignored by
            // the Gemini REST API and causes responses to use default values.
            putJsonObject("generationConfig") {
                put("temperature", temperature.toDouble())
                put("maxOutputTokens", maxTokens)
            }
            if (systemInstruction != null) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", systemInstruction) })
                    }
                }
            }
            putJsonArray("contents") {
                contents.forEach { add(it) }
            }
            // Permissive safety settings so agent actions don't get filtered.
            putJsonArray("safetySettings") {
                listOf(
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT",
                ).forEach { c ->
                    add(buildJsonObject {
                        put("category", c)
                        put("threshold", "BLOCK_ONLY_HIGH")
                    })
                }
            }
        }
        // URL format: /v1beta/models/<model>:generateContent
        val url = baseUrl.trimEnd('/') + "/models/$model:generateContent"
        Log.w(TAG, "Gemini request URL: $url")
        Log.w(TAG, "Gemini request payload: $payload")
        val resp = http.newCall(buildRequest(url, apiKey, payload.toString())).execute()
        val body = resp.use { it.body?.string().orEmpty() }
        if (!resp.isSuccessful) {
            Log.e(TAG, "Gemini complete error ${resp.code}: $body")
            throw LlmException("Gemini HTTP ${resp.code}: ${body.take(500)}")
        }
        Log.d(TAG, "complete ← ${body.take(200)}")

        val obj = json.parseToJsonElement(body).jsonObject
        val candidate = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        val text = candidate
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            .orEmpty()
        val finishReason = candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull.orEmpty()
        val usageObj = obj["usageMetadata"]?.jsonObject
        val usage = LlmUsage(
            promptTokens = usageObj?.get("promptTokenCount")?.jsonPrimitive?.intOrNull?.toLong() ?: 0L,
            completionTokens = usageObj?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull?.toLong() ?: 0L,
            totalTokens = usageObj?.get("totalTokenCount")?.jsonPrimitive?.intOrNull?.toLong() ?: 0L,
        )
        Log.d(TAG, "complete done: ${text.take(80)} | finish=$finishReason | tokens=${usage.totalTokens}")
        LlmClient.CompletionResult(text, usage, finishReason)
    }

    /**
     * Streaming completion — emits one text delta per generated token group.
     */
    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<LlmClient.Message>,
        temperature: Float = 0.2f,
        maxTokens: Int = 2048,
    ): Flow<String> = flow {
        val (systemInstruction, contents) = convertMessages(messages)
        val payload = buildJsonObject {
            putJsonObject("generationConfig") {
                put("temperature", temperature.toDouble())
                put("maxOutputTokens", maxTokens)
            }
            if (systemInstruction != null) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", systemInstruction) })
                    }
                }
            }
            putJsonArray("contents") { contents.forEach { add(it) } }
            putJsonArray("safetySettings") {
                listOf(
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT",
                ).forEach { c ->
                    add(buildJsonObject {
                        put("category", c)
                        put("threshold", "BLOCK_ONLY_HIGH")
                    })
                }
            }
        }
        val url = baseUrl.trimEnd('/') + "/models/$model:streamGenerateContent"
        Log.w(TAG, "Gemini stream request URL: $url")
        Log.w(TAG, "Gemini stream request payload: $payload")
        val resp = http.newCall(buildRequest(url, apiKey, payload.toString())).execute()
        resp.use { r ->
            if (!r.isSuccessful) {
                val errBody = r.body?.string().orEmpty()
                Log.e(TAG, "Gemini stream error ${r.code}: $errBody")
                throw LlmException("Gemini HTTP ${r.code}: ${errBody.take(500)}")
            }
            val src = r.body?.source() ?: return@use
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                val delta = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray
                    ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                    .orEmpty()
                if (delta.isNotEmpty()) emit(delta)
            }
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    /**
     * Convert OpenAI-style messages to Gemini's `contents` array.
     *
     * Gemini's strict rule: turns must strictly alternate user → model → user.
     * Consecutive turns of the same role cause a 400 error. We enforce this by:
     *   1. Extracting all system messages → systemInstruction field
     *   2. Merging consecutive same-role turns into one (concatenating their text)
     *   3. Ensuring the first content turn is always "user"
     *
     * Role mapping:
     *   - system    → systemInstruction (extracted, not in contents)
     *   - user      → "user"
     *   - assistant → "model"
     *   - tool      → "user" (tool results shown as user-side facts)
     */
    private fun convertMessages(messages: List<LlmClient.Message>): Pair<String?, List<JsonObject>> {
        val systemParts = mutableListOf<String>()
        // Raw role+text pairs before merging
        val raw = mutableListOf<Pair<String, String>>() // (geminiRole, text)

        messages.forEach { m ->
            when (m.role.lowercase()) {
                "system" -> systemParts.add(m.content)
                "user" -> raw.add("user" to m.content)
                "assistant" -> raw.add("model" to m.content)
                "tool" -> raw.add("user" to "[tool result] ${m.content}")
                else -> raw.add("user" to m.content)
            }
        }

        // Merge consecutive same-role turns to satisfy Gemini's strict alternation.
        val merged = mutableListOf<Pair<String, String>>()
        for ((role, text) in raw) {
            if (merged.isNotEmpty() && merged.last().first == role) {
                // Same role as previous — merge by concatenating text
                val prev = merged.removeLast()
                merged.add(role to "${prev.second}\n\n$text")
            } else {
                merged.add(role to text)
            }
        }

        // Gemini requires the first turn to be "user". If somehow the history
        // starts with a model turn, prepend a minimal user turn.
        if (merged.isNotEmpty() && merged.first().first == "model") {
            merged.add(0, "user" to "(start)")
        }

        // If contents is empty (only system messages), add a placeholder user turn
        // so the API has something to respond to.
        if (merged.isEmpty()) {
            merged.add("user" to "(no message)")
        }

        val contents = merged.map { (role, text) ->
            buildJsonObject {
                put("role", role)
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", text) })
                }
            }
        }

        val sys = systemParts.joinToString("\n\n").takeIf { it.isNotBlank() }
        return Pair(sys, contents)
    }

    private fun buildRequest(url: String, apiKey: String, jsonBody: String): Request {
        return Request.Builder()
            .url(url)
            // Gemini uses x-goog-api-key, NOT Bearer auth.
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
    }

    companion object {
        private const val TAG = "GeminiClient"
    }
}
