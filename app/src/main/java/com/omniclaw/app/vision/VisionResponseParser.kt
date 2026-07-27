package com.omniclaw.app.vision

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses VLM API responses into structured results.
 *
 * Handles the OpenAI-compatible response shape:
 *   {
 *     "choices": [
 *       { "message": { "content": "..." }, "finish_reason": "stop" }
 *     ],
 *     "usage": { "prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150 }
 *   }
 *
 * Also tolerates Gemini-style responses (candidates[].content.parts[].text)
 * for cross-provider compatibility.
 */
class VisionResponseParser(private val json: Json) {

    data class VisionResult(
        val text: String,
        val finishReason: String?,
        val promptTokens: Long?,
        val completionTokens: Long?,
    )

    /** Parse a raw JSON string into a [VisionResult], or null on failure. */
    fun parse(body: String): VisionResult? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null

        // OpenAI-compat shape: choices[0].message.content
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        if (choice != null) {
            val content = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            val usage = obj["usage"]?.jsonObject
            val prompt = usage?.get("prompt_tokens")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val completion = usage?.get("completion_tokens")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            if (content != null) {
                return VisionResult(content, finish, prompt, completion)
            }
        }

        // Gemini shape: candidates[0].content.parts[].text
        val candidate = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        if (candidate != null) {
            val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
            val text = parts?.joinToString("") {
                it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
            val usage = obj["usageMetadata"]?.jsonObject
            val prompt = usage?.get("promptTokenCount")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val completion = usage?.get("candidatesTokenCount")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            // Treat empty/blank text the same as "no text field at all" so the
            // caller reports "empty content from provider" instead of masking
            // the cause behind a downstream "Malformed VLM response" throw.
            if (!text.isNullOrEmpty()) {
                return VisionResult(text, finish, prompt, completion)
            }
        }

        return null
    }
}
