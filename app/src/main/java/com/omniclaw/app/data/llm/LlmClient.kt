package com.omniclaw.app.data.llm

import android.util.Log
import com.omniclaw.app.data.model.LlmToolCall
import com.omniclaw.app.data.model.LlmUsage
import com.omniclaw.app.data.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
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
import kotlinx.serialization.json.JsonPrimitive
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

    /**
     * Dedicated client for SSE streaming (audit M-29). OkHttp readTimeout fires
     * between byte reads, so the shared 60s client kills long-running streams
     * (reasoning models, slow tool calls) mid-generation.
     *
     * D-M8: readTimeout was 0 (infinite), which let a stalled SSE connection
     * hang the stream forever — the agent's per-step timeout would never fire
     * on the HTTP side. Capped at 120s so a server that stops sending bytes
     * without closing the socket is treated as a network failure rather than
     * an infinite wait. This matches the streaming-friendly behavior of most
     * production LLM SDKs.
     *
     * PERF-FIX (slow agent response): lowered 120s -> 45s. The agent loop's
     * step timeout (stepTimeoutMs = 45s) is the real user-facing bound; a
     * 120s read-timeout gave no useful behavior the step timeout didn't
     * already cover, and meant a stalled SSE stream (server accepts the
     * connection then never sends a byte) held the user on a blank screen
     * for 2 full minutes before OkHttp fired. 45s matches stepTimeoutMs so
     * the two layers fail together instead of one waiting out the other.
     */
    private val streamingHttp: OkHttpClient by lazy {
        http.newBuilder().readTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build()
    }

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

    /**
     * Streaming chunk emitted by [streamWithTools]. The collector assembles
     * these into a final [CompletionResult]-equivalent state:
     *   - [TextDelta]      — append to the assistant's visible text (live thinking)
     *   - [ToolCallDelta]  — append to `tool_calls[index].function.arguments`;
     *                        `id` and `name` arrive in the FIRST delta for that
     *                        index and are null in subsequent deltas.
     *   - [Done]           — the stream is complete; `finishReason` may be
     *                        "stop", "tool_calls", "length", etc.
     *
     * OpenAI SSE shape for tool-call streaming (what this parses):
     * ```
     * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"device_action","arguments":""}}]}}]}
     * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\""}}]}}]}
     * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"thought"}}]}}]}
     * ...
     * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"}"}}]}}]}
     * data: {"choices":[{"finish_reason":"tool_calls"}]}
     * data: [DONE]
     * ```
     *
     * The `arguments` string is split across many deltas — the collector MUST
     * concatenate them in order before parsing the final JSON. The `id` and
     * `name` are sent only once (in the first delta for that index) and are
     * null in all subsequent deltas for the same index.
     */
    sealed class StreamChunk {
        data class TextDelta(val text: String) : StreamChunk()
        data class ToolCallDelta(
            val index: Int,
            val id: String?,
            val name: String?,
            val argumentsChunk: String,
        ) : StreamChunk()
        data class Done(val finishReason: String?) : StreamChunk()
    }

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
                throw RateLimitException(retryAfterSeconds = retryAfter, body = "HTTP 429")
            }
            // D-H5: do NOT include the response body in the exception message —
            // some providers echo the request body (including the Bearer token
            // or the user's prompt) back in the error response, which would
            // then leak via Logcat / crash reports. Keep only the HTTP code.
            throw LlmException("HTTP ${resp.code}")
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
                // FIX (openai-compat agent failed): use the same messageToJson
                // helper that complete() uses, so tool_calls on assistant
                // messages and tool_call_id on tool messages are preserved.
                //
                // Previously this inlined a simplified {role, content} object
                // that STRIPPED tool_calls and tool_call_id. After the first
                // device_action tool call, the conversation history contains a
                // `{"role":"tool","content":"..."}` message — OpenAI-compat
                // endpoints that strictly validate (OpenAI itself, many
                // compatible providers) return HTTP 400 because tool messages
                // MUST carry tool_call_id. The streaming path would fail
                // silently, the non-streaming fallback would also fail (same
                // malformed history), and the user saw "agent failed".
                //
                // messageToJson already handles tool_calls + tool_call_id
                // correctly, so reusing it here is the one-line fix.
                messages.forEach { m -> add(messageToJson(m)) }
            }
        }
        val call = streamingHttp.newCall(buildRequest(baseUrl, apiKey, payload.toString()))

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
                        // D-H5: drain the error body so the connection can be
                        // reused (OkHttp requires the body to be read or closed
                        // before the connection is returned to the pool), but do
                        // NOT include it in the exception — it may contain the
                        // echoed request (prompt, Bearer token) and leak via Logcat.
                        runCatching { resp.body?.string() }
                        if (resp.code == 429) {
                            val retryAfter = resp.header("Retry-After")?.toIntOrNull()
                            channel.close(RateLimitException(retryAfter, "HTTP 429"))
                            return
                        }
                        channel.close(LlmException("HTTP ${resp.code}"))
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
                            // D-M3: use `as? JsonPrimitive` for the same reason as parseCompletion —
                            // a non-primitive `content` field would crash the stream mid-flight.
                            val deltaContent = (deltaObj?.get("content") as? JsonPrimitive)?.content.orEmpty()
                            val deltaReasoning = (deltaObj?.get("reasoning_content") as? JsonPrimitive)?.content.orEmpty()
                            val deltaReasoningAlt = (deltaObj?.get("reasoning") as? JsonPrimitive)?.content.orEmpty()
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
                                    channel.close(IllegalStateException("back-pressure"))
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
    }.buffer(capacity = 64, onBufferOverflow = BufferOverflow.SUSPEND).flowOn(Dispatchers.IO)

    /**
     * Streaming completion WITH tools support — emits [StreamChunk] events
     * (text deltas + tool_call deltas + done) so the caller can show live
     * "thinking" text while ALSO accumulating a structured tool_call.
     *
     * This is the streaming equivalent of [complete] with `tools` set. It's
     * what the agent loop's structured-tools path SHOULD use so the user
     * sees token-by-token thinking instead of a blank screen for the full
     * LLM latency.
     *
     * OpenAI's chat-completions API supports `stream: true` + `tools` + 
     * `tool_choice` natively. The SSE deltas include `delta.tool_calls[i]`
     * chunks that must be accumulated by `index` — the `arguments` field
     * arrives in many small string fragments that must be concatenated in
     * order before the final JSON can be parsed.
     *
     * Cancellable: coroutine cancellation cancels the underlying OkHttp call.
     *
     * Back-pressure: same as [stream] — if the downstream collector can't
     * keep up, the channel closes cleanly so the caller can fall back.
     */
    fun streamWithTools(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Message>,
        temperature: Float = 0.2f,
        maxTokens: Int = 2048,
        tools: List<ToolSpec>? = null,
        toolChoice: String? = null,
    ): Flow<StreamChunk> = callbackFlow {
        val payload = buildJsonObject {
            put("model", model)
            put("temperature", temperature.toDouble())
            put("max_tokens", maxTokens)
            put("stream", true)
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
        val call = streamingHttp.newCall(buildRequest(baseUrl, apiKey, payload.toString()))

        // Coroutine cancellation → cancel the OkHttp call.
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
                        // D-H5: drain the error body so the connection can be
                        // reused, but do NOT include it in the exception — it
                        // may contain the echoed request (prompt, Bearer token).
                        runCatching { resp.body?.string() }
                        if (resp.code == 429) {
                            val retryAfter = resp.header("Retry-After")?.toIntOrNull()
                            channel.close(RateLimitException(retryAfter, "HTTP 429"))
                            return
                        }
                        channel.close(LlmException("HTTP ${resp.code}"))
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
                            if (data == "[DONE]") {
                                channel.trySend(StreamChunk.Done(null))
                                break
                            }
                            val obj = runCatching {
                                json.parseToJsonElement(data).jsonObject
                            }.getOrNull() ?: continue
                            val choiceObj = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                                ?: continue
                            val deltaObj = choiceObj["delta"]?.jsonObject

                            // ---- Text content delta (the "live thinking" text) ----
                            val deltaContent = (deltaObj?.get("content") as? JsonPrimitive)?.content.orEmpty()
                            val deltaReasoning = (deltaObj?.get("reasoning_content") as? JsonPrimitive)?.content.orEmpty()
                            val deltaReasoningAlt = (deltaObj?.get("reasoning") as? JsonPrimitive)?.content.orEmpty()
                            val text = deltaContent.ifEmpty { deltaReasoning.ifEmpty { deltaReasoningAlt } }
                            if (text.isNotEmpty()) {
                                if (!channel.trySend(StreamChunk.TextDelta(text)).isSuccess) {
                                    Log.w(TAG, "streamWithTools back-pressure: closing")
                                    channel.close(IllegalStateException("back-pressure"))
                                    return
                                }
                            }

                            // ---- Tool call deltas (accumulated by index) ----
                            val toolCallsArray = deltaObj?.get("tool_calls")?.jsonArray
                            if (toolCallsArray != null) {
                                for (tcEl in toolCallsArray) {
                                    val tcObj = runCatching { tcEl.jsonObject }.getOrNull() ?: continue
                                    val idx = (tcObj["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                                    val id = (tcObj["id"] as? JsonPrimitive)?.content
                                    val fnObj = tcObj["function"]?.jsonObject
                                    val name = (fnObj?.get("name") as? JsonPrimitive)?.content
                                    val argsChunk = (fnObj?.get("arguments") as? JsonPrimitive)?.content.orEmpty()
                                    if (!channel.trySend(
                                            StreamChunk.ToolCallDelta(idx, id, name, argsChunk)
                                        ).isSuccess
                                    ) {
                                        Log.w(TAG, "streamWithTools back-pressure: closing")
                                        channel.close(IllegalStateException("back-pressure"))
                                        return
                                    }
                                }
                            }

                            // ---- Finish reason (sent in the final delta, not [DONE]) ----
                            val finishReason = (choiceObj["finish_reason"] as? JsonPrimitive)?.content
                            if (finishReason != null) {
                                channel.trySend(StreamChunk.Done(finishReason))
                            }
                        }
                        channel.close()
                    } catch (e: IOException) {
                        channel.close(e)
                    }
                }
            }
        })
    }.buffer(capacity = 64, onBufferOverflow = BufferOverflow.SUSPEND).flowOn(Dispatchers.IO)

    private fun parseCompletion(body: String): CompletionResult {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw LlmException("Malformed response (not JSON): ${body.take(200)}")
        }
        val firstChoice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw LlmException("No choices in response: ${body.take(200)}")
        val msgObj = firstChoice["message"] as? JsonObject
        // D-M3: use `as? JsonPrimitive` so a non-primitive `content` field (e.g.
        // a provider that returns content as an array of text parts) doesn't
        // crash with ClassCastException. We degrade gracefully to empty string
        // and let the caller's empty-response handling take over.
        val content = (msgObj?.get("content") as? JsonPrimitive)?.content.orEmpty()
        val reasoning = (msgObj?.get("reasoning_content") as? JsonPrimitive)?.content.orEmpty()
        val reasoningAlt = (msgObj?.get("reasoning") as? JsonPrimitive)?.content.orEmpty()
        val text = content.ifEmpty { reasoning.ifEmpty { reasoningAlt } }
        val finish = (firstChoice["finish_reason"] as? JsonPrimitive)?.content.orEmpty()
        val usageObj = obj["usage"] as? JsonObject
        val usage = LlmUsage(
            promptTokens = (usageObj?.get("prompt_tokens") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            completionTokens = (usageObj?.get("completion_tokens") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            totalTokens = (usageObj?.get("total_tokens") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
        )
        // Structured tool calls (Hermes-style function-calling). Empty for plain
        // text completions or providers that ignore the tools schema.
        val toolCalls = msgObj?.get("tool_calls")?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val fn = o["function"]?.jsonObject ?: return@mapNotNull null
            val name = (fn["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            LlmToolCall(
                id = (o["id"] as? JsonPrimitive)?.content ?: java.util.UUID.randomUUID().toString(),
                name = name,
                arguments = (fn["arguments"] as? JsonPrimitive)?.content ?: "{}",
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
        // Reject cleartext http:// endpoints that are not loopback: the Bearer
        // token below would otherwise be sent in cleartext (audit H-28). Loopback
        // hosts (localhost / 127.0.0.1 / 10.0.2.2) stay allowed for local model
        // servers such as Ollama and LM Studio.
        if (url.startsWith("http://")) {
            val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
            val loopback = host.equals("localhost", ignoreCase = true) ||
                host == "127.0.0.1" || host == "10.0.2.2"
            if (!loopback) {
                throw IllegalArgumentException(
                    "Refusing cleartext http:// LLM endpoint (host $host); use https:// or a loopback address."
                )
            }
        }
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
