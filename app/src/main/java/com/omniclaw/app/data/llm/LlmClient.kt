package com.omniclaw.app.data.llm

import android.util.Log
import com.omniclaw.app.data.model.LlmToolCall
import com.omniclaw.app.data.model.LlmUsage
import com.omniclaw.app.data.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI-compatible chat completion client.
 *
 * Works with:
 *   - OpenAI           https://api.openai.com/v1
 *   - GLM / ZhipuAI    https://open.bigmodel.cn/api/paas/v4
 *   - Ollama / vLLM    http://localhost:11434/v1
 *   - LM Studio        http://localhost:1234/v1
 *   - llama.cpp server http://localhost:8080/v1
 *
 * Base URL + API key + model come from user-configured settings.
 *
 * Both [complete] and [stream] are cancellable: coroutine cancellation
 * propagates to the underlying OkHttp [Call] via [suspendCancellableCoroutine]
 * + [Call.cancel]. This is critical for the agent loop's "Stop" button —
 * without it, in-flight HTTP calls would block until readTimeout (120s).
 */
@Singleton
class LlmClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
) {

    @Serializable
    data class Message(
        val role: String,
        val content: String,
        val toolCalls: List<LlmToolCall>? = null,
        val toolCallId: String? = null,
    )

    @Serializable
    data class CompletionResult(
        val text: String,
        val usage: LlmUsage,
        val finishReason: String,
        val toolCalls: List<LlmToolCall> = emptyList(),
    )

    /** Non-streaming completion. Cancellable. Supports Hermes-style structured tool-calling. */
    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Message>,
        temperature: Float = 0.2f,
        maxTokens: Int = 2048,
        tools: List<ToolSpec>? = null,
        toolChoice: String? = null,
    ): CompletionResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", model)
            put("temperature", temperature.toDouble())
            put("max_tokens", maxTokens)
            put("stream", false)
            putJsonArray("messages") {
                messages.forEach { m -> add(messageToJson(m)) }
            }
            if (tools != null) {
                putJsonArray("tools") {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", json.parseToJsonElement(t.parametersSchema).jsonObject)
                            }
                        })
                    }
                }
                put("tool_choice", toolChoice ?: "auto")
            }
        }
        val resp = executeCancellable(buildRequest(baseUrl, apiKey, payload.toString()))
        val body = resp.use { it.body?.string().orEmpty() }
        if (!resp.isSuccessful) {
            // 429 rate-limit: surface Retry-After so the caller's retry loop can honor it.
            if (resp.code == 429) {
                val retryAfter = resp.header("Retry-After")?.toIntOrNull()
                throw RateLimitException(retryAfterSeconds = retryAfter, body = body)
            }
            throw LlmException("HTTP ${resp.code}: ${body.take(500)}")
        }
        parseCompletion(body)
    }

    /** Streaming completion — emits one chunk per token. Cancellable. */
    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Message>,
        temperature: Float = 0.2f,
        maxTokens: Int = 2048,
    ): Flow<String> = callbackFlow {
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
        val call = http.newCall(buildRequest(baseUrl, apiKey, payload.toString()))

        // Coroutine cancellation → cancel the OkHttp call so the socket closes
        // promptly and the server stops generating tokens.
        awaitClose { runCatching { call.cancel() } }

        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                if (channel.isClosedForSend) return
                channel.close(
                    if (e is IOException && c.isCanceled()) CancellationException("Stream cancelled")
                    else e
                )
            }

            override fun onResponse(c: Call, r: Response) {
                r.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string().orEmpty()
                        if (resp.code == 429) {
                            val retryAfter = resp.header("Retry-After")?.toIntOrNull()
                            channel.close(RateLimitException(retryAfter, errBody))
                            return
                        }
                        channel.close(LlmException("HTTP ${resp.code}: ${errBody.take(500)}"))
                        return
                    }
                    val src = resp.body?.source()
                    if (src == null) {
                        channel.close(LlmException("Empty response body"))
                        return
                    }
                    try {
                        while (!src.exhausted()) {
                            if (channel.isClosedForSend) return
                            val line = src.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            val obj = runCatching {
                                json.parseToJsonElement(data).jsonObject
                            }.getOrNull() ?: continue
                            val deltaObj = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                                ?.get("delta")?.jsonObject
                            val deltaContent = deltaObj?.get("content")?.jsonPrimitive?.content.orEmpty()
                            val deltaReasoning = deltaObj?.get("reasoning_content")?.jsonPrimitive?.content.orEmpty()
                            val deltaReasoningAlt = deltaObj?.get("reasoning")?.jsonPrimitive?.content.orEmpty()
                            val delta = deltaContent.ifEmpty { deltaReasoning.ifEmpty { deltaReasoningAlt } }

                            if (delta.isNotEmpty()) {
                                // trySend fails when the downstream collector
                                // can't keep up (channel buffer full). Previously
                                // this silently returned, ending the stream and
                                // discarding the rest of the tokens — the
                                // AgentLoop's fallback to non-streaming would
                                // then re-send the whole prompt. We now log
                                // the back-pressure event so it's visible in
                                // Logcat, then close the stream cleanly.
                                val result = channel.trySend(delta)
                                if (!result.isSuccess) {
                                    Log.w(TAG, "stream back-pressure: trySend failed (${result.exceptionOrNull()?.message}) — closing stream, caller will fall back to non-streaming")
                                    return
                                }
                            }
                        }
                        channel.close()
                    } catch (e: IOException) {
                        channel.close(e)
                    }
                }
            }
        })
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    private fun parseCompletion(body: String): CompletionResult {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw LlmException("Malformed response (not JSON): ${body.take(200)}")
        }
        val firstChoice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw LlmException("No choices in response: ${body.take(200)}")
        val msgObj = firstChoice["message"] as? JsonObject
        val content = msgObj?.get("content")?.jsonPrimitive?.content.orEmpty()
        val reasoning = msgObj?.get("reasoning_content")?.jsonPrimitive?.content.orEmpty()
        val reasoningAlt = msgObj?.get("reasoning")?.jsonPrimitive?.content.orEmpty()
        val text = content.ifEmpty { reasoning.ifEmpty { reasoningAlt } }
        val finish = firstChoice["finish_reason"]?.jsonPrimitive?.content.orEmpty()
        val usageObj = obj["usage"] as? JsonObject
        val usage = LlmUsage(
            promptTokens = usageObj?.get("prompt_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            completionTokens = usageObj?.get("completion_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            totalTokens = usageObj?.get("total_tokens")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
        // Structured tool calls (Hermes-style function-calling). Empty for plain
        // text completions or providers that ignore the tools schema.
        val toolCalls = msgObj?.get("tool_calls")?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val fn = o["function"]?.jsonObject ?: return@mapNotNull null
            val name = fn["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            LlmToolCall(
                id = o["id"]?.jsonPrimitive?.content ?: java.util.UUID.randomUUID().toString(),
                name = name,
                arguments = fn["arguments"]?.jsonPrimitive?.content ?: "{}",
            )
        }.orEmpty()
        return CompletionResult(text, usage, finish, toolCalls)
    }

    /**
     * Execute an OkHttp call as a cancellable coroutine. On cancellation,
     * [Call.cancel] is invoked so the underlying socket is closed promptly.
     */
    private suspend fun executeCancellable(req: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(req)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(c: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(c: Call, r: Response) {
                    if (cont.isActive) cont.resume(r)
                }
            })
        }

    private fun buildRequest(baseUrl: String, apiKey: String, jsonBody: String): Request {
        val cleanBase = baseUrl.trim().trimEnd('/')
            .removeSuffix("/chat/completions")
            .trimEnd('/')
        val url = if (cleanBase.isEmpty()) "https://api.openai.com/v1/chat/completions" else "$cleanBase/chat/completions"
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
        val cleanKey = apiKey.trim()
        if (cleanKey.isNotEmpty()) {
            builder.header("Authorization", "Bearer $cleanKey")
        }
        return builder.build()
    }

    /** Serialize a [Message] honoring the OpenAI tool protocol (tool_calls / tool role). */
    private fun messageToJson(m: Message): JsonObject = buildJsonObject {
        put("role", m.role)
        put("content", m.content)
        m.toolCallId?.let { put("tool_call_id", it) }
        if (!m.toolCalls.isNullOrEmpty()) {
            putJsonArray("tool_calls") {
                m.toolCalls.forEach { tc ->
                    add(buildJsonObject {
                        put("id", tc.id)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tc.name)
                            put("arguments", tc.arguments)
                        }
                    })
                }
            }
        }
    }

    companion object {
        private const val TAG = "LlmClient"
    }
}

class LlmException(message: String) : RuntimeException(message)

/** HTTP 429 from the LLM provider. Carries the `Retry-After` header (seconds) when present. */
class RateLimitException(
    val retryAfterSeconds: Int?,
    val body: String,
) : RuntimeException(
    "Rate limited (HTTP 429)" + (retryAfterSeconds?.let { ", retry after $it s" } ?: "")
)
