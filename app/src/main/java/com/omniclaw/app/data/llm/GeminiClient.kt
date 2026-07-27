package com.omniclaw.app.data.llm

import android.util.Log
import com.omniclaw.app.core.retry
import com.omniclaw.app.data.model.LlmToolCall
import com.omniclaw.app.data.model.LlmUsage
import com.omniclaw.app.data.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
 *   - Streaming now uses callbackFlow + OkHttp async enqueue → cancellable.
 *   - SSE parsing buffers brace depth to handle multi-line JSON objects.
 *   - Removed duplicate executeWithRetry in favor of core.retry (which correctly
 *     re-throws CancellationException).
 *   - HTTP 429 surfaces [RateLimitException] carrying `Retry-After`.
 */
class GeminiClient(
    private val http: OkHttpClient,
    private val json: Json,
) {

    /** Default API base URL — pinned to v1beta (Gemini 2.x lives there). */
    val defaultBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta"

    /**
     * Non-streaming completion. Mirrors [LlmClient.complete] signature so
     * callers can swap implementations transparently. Cancellable.
     */
    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<LlmClient.Message>,
        temperature: Float = 0.2f,
        maxTokens: Int = 2048,
        tools: List<ToolSpec>? = null,
        toolChoice: String? = null,
    ): LlmClient.CompletionResult = withContext(Dispatchers.IO) {
        val (systemInstruction, contents) = convertMessages(messages)
        val payload = buildJsonObject {
            // generationConfig is the correct nesting for these parameters.
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
            // Hermes-style function-calling: declare the tools so the model can
            // emit structured functionCall parts instead of free-text actions.
            if (tools != null) {
                putJsonArray("tools") {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            tools.forEach { t ->
                                add(buildJsonObject {
                                    put("name", t.name)
                                    put("description", t.description)
                                    put("parameters", json.parseToJsonElement(t.parametersSchema).jsonObject)
                                })
                            }
                        }
                    })
                }
                putJsonObject("toolConfig") {
                    putJsonObject("functionCallingConfig") {
                        put("mode", when (toolChoice) {
                            "auto" -> "AUTO"
                            "none" -> "NONE"
                            "required" -> "ANY"
                            else -> "AUTO"
                        })
                    }
                }
            }
        }
        // URL format: /v1beta/models/<model>:generateContent
        val cleanModel = model.trim().removePrefix("models/").ifBlank { "gemini-2.0-flash" }
        val url = baseUrl.trimEnd('/') + "/models/$cleanModel:generateContent"
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Gemini request URL: $url")
        }
        // NOTE: We deliberately do NOT log the full request payload — it
        // contains the user's prompt + conversation history, which is user-
        // private content. Logging it at WARN (as the previous version did)
        // would surface prompts in Logcat on production builds where anyone
        // with adb access could read them. Only the URL is logged, and only
        // at VERBOSE (which must be explicitly enabled via `adb shell
        // setprop log.tag.GeminiClient VERBOSE`).

        val body = retry(
            // PERF-FIX (slow agent response): reduced 3 -> 2 attempts. On a 429
            // with Retry-After, each attempt delayed the user by up to 60s (now
            // 10s, see below) — 3 attempts meant a worst-case 180s wait (now 20s)
            // before the agent loop's outer retry/error handling took over. For
            // an interactive agent, surfacing the rate-limit after ONE retry is
            // far better UX than holding the user for minutes.
            maxAttempts = 2,
            baseDelayMs = 1000,
            maxDelayMs = 4000,
            retryable = { e ->
                when (e) {
                    is LlmException -> {
                        val msg = e.message.orEmpty()
                        msg.contains("HTTP 503") ||
                            msg.contains("HTTP 502") ||
                            msg.contains("HTTP 504") ||
                            msg.contains("HTTP 429")
                    }
                    is java.io.IOException -> true
                    is RateLimitException -> true
                    else -> false
                }
            },
        ) {
            val resp = executeCancellable(buildRequest(url, apiKey, payload.toString()))
            val b = resp.use { it.body?.string().orEmpty() }
            if (!resp.isSuccessful) {
                if (resp.code == 429) {
                    val retryAfter = resp.header("Retry-After")?.toIntOrNull()
                    // Honor the server Retry-After header before the retry helper backs
                    // off again, so a long quota window is not hammered at 1-8s (audit M-25).
                    // PERF-FIX (slow agent response): cap at 10s (was 60s). A 60s cap meant
                    // the user sat on a blank screen for a full minute per attempt —
                    // unacceptable for interactive use. 10s is long enough to honor a
                    // typical short quota reset (most providers return Retry-After: 1-5s),
                    // while still surfacing persistent rate-limits upstream quickly so the
                    // agent loop can inform the user instead of silently waiting.
                    if (retryAfter != null) delay(minOf(retryAfter, 10) * 1000L)
                    throw RateLimitException(
                        retryAfterSeconds = retryAfter,
                        body = "HTTP 429",
                    )
                }
                // D-H5: do NOT include the response body in the exception or the
                // log line — Gemini error responses can echo parts of the request
                // (system instruction, prompts, x-goog-api-key in headers) and
                // leaking that via Logcat / crash reports is a security regression.
                if (Log.isLoggable(TAG, Log.ERROR)) {
                    Log.e(TAG, "Gemini complete error HTTP ${resp.code}")
                }
                throw LlmException("Gemini HTTP ${resp.code}")
            }
            b
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "complete ← ${body.take(200)}")
        }

        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            throw LlmException("Malformed Gemini response (not JSON): ${body.take(200)}")
        }
        val candidate = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        val text = candidate
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.joinToString("") { partObj ->
                val p = partObj.jsonObject
                val txt = p["text"]?.jsonPrimitive?.contentOrNull
                val tht = p["thought"]?.jsonPrimitive?.contentOrNull
                txt ?: tht ?: ""
            }
            .orEmpty()
        val finishReason = candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull.orEmpty()
        val usageObj = obj["usageMetadata"]?.jsonObject
        val usage = LlmUsage(
            promptTokens = usageObj?.get("promptTokenCount")?.jsonPrimitive?.intOrNull?.toLong() ?: 0L,
            completionTokens = usageObj?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull?.toLong() ?: 0L,
            totalTokens = usageObj?.get("totalTokenCount")?.jsonPrimitive?.intOrNull?.toLong() ?: 0L,
        )
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "complete done: ${text.take(80)} | finish=$finishReason | tokens=${usage.totalTokens}")
        }
        // Structured tool calls (Gemini functionCall parts). Empty for plain text.
        val toolCalls = candidate
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { partObj ->
                val fc = partObj.jsonObject["functionCall"]?.jsonObject ?: return@mapNotNull null
                val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val args = fc["args"]?.jsonObject?.toString() ?: "{}"
                LlmToolCall(id = java.util.UUID.randomUUID().toString(), name = name, arguments = args)
            }.orEmpty()
        LlmClient.CompletionResult(text, usage, finishReason, toolCalls)
    }

    /**
     * Streaming completion — emits one text delta per generated token group.
     * Cancellable: coroutine cancellation cancels the underlying OkHttp call.
     *
     * NOTE: streaming is non-idempotent — if a stream emits N tokens then fails,
     * retrying would emit those N tokens again, producing duplicated text in the
     * agent's accumulated thought. We deliberately do NOT retry streaming here;
     * callers should fall back to the (idempotent) non-streaming [complete]
     * path on stream failure.
     */
    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<LlmClient.Message>,
        temperature: Float = 0.2f,
        maxTokens: Int = 2048,
    ): Flow<String> = callbackFlow {
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
        val cleanModel = model.trim().removePrefix("models/").ifBlank { "gemini-2.0-flash" }
        val url = baseUrl.trimEnd('/') + "/models/$cleanModel:streamGenerateContent"
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Gemini stream request URL: $url")
        }
        val call = http.newCall(buildRequest(url, apiKey, payload.toString()))

        // Coroutine cancellation → cancel the OkHttp call.
        awaitClose { runCatching { call.cancel() } }

        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                if (channel.isClosedForSend) return
                channel.close(
                    if (c.isCanceled()) CancellationException("Gemini stream cancelled")
                    else e
                )
            }

            override fun onResponse(c: Call, r: Response) {
                r.use { resp ->
                    if (!resp.isSuccessful) {
                        // D-H5: drain the error body so the connection can be
                        // reused, but do NOT include it in the exception or log —
                        // Gemini error responses can echo parts of the request.
                        runCatching { resp.body?.string() }
                        if (resp.code == 429) {
                            channel.close(RateLimitException(
                                resp.header("Retry-After")?.toIntOrNull(), "HTTP 429"
                            ))
                            return
                        }
                        if (Log.isLoggable(TAG, Log.ERROR)) {
                            Log.e(TAG, "Gemini stream error HTTP ${resp.code}")
                        }
                        channel.close(LlmException("Gemini HTTP ${resp.code}"))
                        return
                    }
                    val src = resp.body?.source()
                    if (src == null) {
                        channel.close(LlmException("Empty stream body"))
                        return
                    }
                    // Buffer-based SSE parser: Gemini streams a chunked JSON
                    // array, but a single JSON object can span multiple lines
                    // (newlines inside string values). Track brace depth so we
                    // only parse when we have a complete top-level object.
                    //
                    // D-M2: cap buf at MAX_BUFFER (1 MiB) so a malformed stream
                    // (missing closing brace, runaway string with no quote) can't
                    // grow buf without bound and OOM the process. Beyond the cap
                    // we close the channel with an explicit error rather than
                    // silently truncating.
                    val buf = StringBuilder()
                    try {
                        while (!src.exhausted()) {
                            if (channel.isClosedForSend) return
                            val line = src.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            buf.append(line)
                            if (buf.length > MAX_BUFFER) {
                                channel.close(LlmException("Gemini stream: object exceeded max buffer size ($MAX_BUFFER bytes)"))
                                return
                            }
                            // Try to parse every complete top-level object in buf.
                            while (true) {
                                val obj = extractNextObject(buf) ?: break
                                emitDelta(obj)?.let { delta ->
                                    // D-H11: on trySend failure (downstream can't keep up),
                                    // close the channel explicitly so the collector sees a
                                    // clean error rather than a silently-truncated stream.
                                    if (delta.isNotEmpty() && !channel.trySend(delta).isSuccess) {
                                        channel.close(IllegalStateException("Gemini stream back-pressure"))
                                        return
                                    }
                                }
                            }
                        }
                        // Flush any trailing object.
                        if (buf.isNotBlank()) {
                            extractNextObject(buf)?.let { obj ->
                                emitDelta(obj)?.let { delta ->
                                    // D-H11: same back-pressure handling on the flush path.
                                    if (delta.isNotEmpty() && !channel.trySend(delta).isSuccess) {
                                        channel.close(IllegalStateException("Gemini stream back-pressure"))
                                        return
                                    }
                                }
                            }
                        }
                        channel.close()
                    } catch (e: IOException) {
                        channel.close(e)
                    }
                }
            }

            /** Extract the first complete top-level JSON object from [buf], removing it. */
            fun extractNextObject(buf: StringBuilder): JsonObject? {
                var depth = 0
                var inStr = false
                var escape = false
                var startIdx = -1
                for (i in 0 until buf.length) {
                    val c = buf[i]
                    if (inStr) {
                        if (escape) escape = false
                        else if (c == '\\') escape = true
                        else if (c == '"') inStr = false
                        continue
                    }
                    when (c) {
                        '"' -> inStr = true
                        '{' -> {
                            if (depth == 0) startIdx = i
                            depth++
                        }
                        '}' -> {
                            depth--
                            if (depth == 0 && startIdx >= 0) {
                                val piece = buf.substring(startIdx, i + 1)
                                buf.delete(0, i + 1)
                                return runCatching { json.parseToJsonElement(piece).jsonObject }.getOrNull()
                            }
                        }
                    }
                }
                return null
            }

            fun emitDelta(obj: JsonObject): String? {
                val candidate = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                val delta = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray
                    ?.joinToString("") { partObj ->
                        val p = partObj.jsonObject
                        val txt = p["text"]?.jsonPrimitive?.contentOrNull
                        val tht = p["thought"]?.jsonPrimitive?.contentOrNull
                        txt ?: tht ?: ""
                    }
                    .orEmpty()
                return delta
            }
        })
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    /**
     * D-M2: hard cap on the SSE buffer. A malformed Gemini stream that never
     * closes its top-level object would otherwise grow `buf` without bound;
     * 1 MiB is well above any legitimate single-chunk response.
     */
    private val MAX_BUFFER = 1 * 1024 * 1024

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
                val prev = merged.removeAt(merged.lastIndex)
                merged.add(role to "${prev.second}\n\n$text")
            } else {
                merged.add(role to text)
            }
        }

        // Gemini requires the first turn to be "user".
        if (merged.isNotEmpty() && merged.first().first == "model") {
            merged.add(0, "user" to "(start)")
        }
        if (merged.isEmpty()) {
            merged.add("user" to "(no message)")
        }

        val contents = merged.map { (role, text) ->
            buildJsonObject {
                put("role", role)
                putJsonArray("parts") {
                    val safeText = if (text.isBlank()) "(empty)" else text
                    add(buildJsonObject { put("text", safeText) })
                }
            }
        }

        val sys = systemParts.joinToString("\n\n").takeIf { it.isNotBlank() }
        return Pair(sys, contents)
    }

    /** Execute an OkHttp call as a cancellable coroutine. */
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
