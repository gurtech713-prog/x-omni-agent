package com.omniclaw.app.data.llm

import com.omniclaw.app.data.model.LlmUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI-compatible chat completion client.
 *
 * Works with:
 *   - OpenAI           https://api.openai.com/v1
 *   - GLM / ZhipuAI    https://open.bigmodel.cn/api/paas/v4
 *   - Ollama / vLLM    http://localhost:11434/v1
 *   - LM Studio        http://localhost:1234/v1
 *
 * Base URL + API key + model come from user-configured settings.
 */
@Singleton
class LlmClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) {

    @Serializable
    data class Message(val role: String, val content: String)

    @Serializable
    data class CompletionResult(
        val text: String,
        val usage: LlmUsage,
        val finishReason: String,
    )

    /** Non-streaming completion. */
    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Message>,
        temperature: Float = 0.2f,
        maxTokens: Int = 2048,
    ): CompletionResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", model)
            put("temperature", temperature.toDouble())
            put("max_tokens", maxTokens)
            put("stream", false)
            putJsonArray("messages") {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            }
        }
        val resp = http.newCall(buildRequest(baseUrl, apiKey, payload.toString())).execute()
        val body = resp.use { it.body?.string().orEmpty() }
        if (!resp.isSuccessful) throw LlmException("HTTP ${resp.code}: ${body.take(500)}")

        val obj = json.parseToJsonElement(body).jsonObject
        val firstChoice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw LlmException("No choices in response")
        val text = firstChoice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()
        val finish = firstChoice["finish_reason"]?.jsonPrimitive?.content.orEmpty()
        val usageObj = obj["usage"]?.jsonObject
        val usage = LlmUsage(
            promptTokens = usageObj?.get("prompt_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            completionTokens = usageObj?.get("completion_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            totalTokens = usageObj?.get("total_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
        CompletionResult(text, usage, finish)
    }

    /** Streaming completion — emits one chunk per token. */
    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Message>,
        temperature: Float = 0.2f,
        maxTokens: Int = 2048,
    ): Flow<String> = flow {
        val payload = buildJsonObject {
            put("model", model)
            put("temperature", temperature.toDouble())
            put("max_tokens", maxTokens)
            put("stream", true)
            putJsonArray("messages") {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            }
        }
        val resp = http.newCall(buildRequest(baseUrl, apiKey, payload.toString())).execute()
        resp.use { r ->
            if (!r.isSuccessful) throw LlmException("HTTP ${r.code}")
            val src = r.body?.source() ?: return@use
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                val delta = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()
                if (delta.isNotEmpty()) emit(delta)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildRequest(baseUrl: String, apiKey: String, jsonBody: String): Request {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
    }
}

class LlmException(message: String) : RuntimeException(message)
