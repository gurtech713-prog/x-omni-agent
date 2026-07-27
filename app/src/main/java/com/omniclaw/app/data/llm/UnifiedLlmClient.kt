package com.omniclaw.app.data.llm

import android.util.Log
import com.omniclaw.app.data.local.LiteRtException
import com.omniclaw.app.data.local.LocalLlmClient
import com.omniclaw.app.data.model.ToolSpec
import com.omniclaw.app.data.prefs.LlmProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for all chat completions in the app.
 *
 * Routes each request to the right backend based on [LlmProvider]:
 *   - OPENAI_COMPAT -> [LlmClient]          (GLM / OpenAI / Anthropic / Moonshot / Ollama)
 *   - GEMINI        -> [GeminiClient]       (Google Gemini via REST + x-goog-api-key)
 *   - LITERT        -> [LocalLlmClient]     (on-device inference via LiteRT)
 *
 * The agent loop calls [complete] exactly as it called the old [LlmClient.complete] —
 * it doesn't need to know which provider is active. This keeps the call-site
 * clean and lets us add providers later (e.g. MLX, ExecuTorch) without
 * touching AgentLoop.
 *
 * Fallback behavior:
 *   - If the active provider fails (network / inference error), we do NOT
 *     automatically fall through to another provider — that would silently
 *     consume API budget on a different key. Callers (AgentLoop) handle
 *     retries via [com.omniclaw.app.core.retry].
 */
@Singleton
class UnifiedLlmClient @Inject constructor(
    private val openAi: LlmClient,
    private val gemini: GeminiClient,
    private val local: LocalLlmClient,
) {

    /**
     * Run a chat completion using the given provider config.
     *
     * The [LlmProvider] tells us which backend to use; the [baseUrl] /
     * [apiKey] / [model] fields are interpreted per-backend:
     *
     *   OPENAI_COMPAT: baseUrl = OpenAI-compat endpoint, apiKey = Bearer token
     *   GEMINI:        baseUrl = generativelanguage.googleapis.com/v1beta
     *                  (ignored if blank — uses GeminiClient.defaultBaseUrl),
     *                  apiKey = Google AI Studio key (sent as x-goog-api-key)
     *   LITERT:        model = "local-<family>:<path>" e.g.
     *                  "local-gemma:models/gemma-2b.tflite"
     *                  (the family prefix selects the tokenizer; the path
     *                   after the colon is the LiteRT model path)
     */
    suspend fun complete(
        provider: LlmProvider,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<LlmClient.Message>,
        temperature: Float = 0.2f,
        maxTokens: Int = 2048,
        tools: List<ToolSpec>? = null,
        toolChoice: String? = null,
    ): LlmClient.CompletionResult = when (provider) {
        LlmProvider.OPENAI_COMPAT -> openAi.complete(
            baseUrl, apiKey, model, messages, temperature, maxTokens, tools, toolChoice,
        )
        LlmProvider.GEMINI -> {
            val url = baseUrl.ifBlank { gemini.defaultBaseUrl }
            gemini.complete(url, apiKey, model, messages, temperature, maxTokens, tools, toolChoice)
        }
        LlmProvider.LITERT -> {
            // Model format: "local-<family>:<path>"
            //   family = tokenizer to use (gemma, tinyllama, etc.)
            //   path   = LiteRT model file (assets:// or absolute)
            //
            // D-L5: temperature is not forwarded — LocalLlmClient uses greedy
            // decode and does not yet sample. See LocalLlmClient.complete KDoc.
            val (family, path) = parseLocalModelSpec(model)
            try {
                local.complete(path, family, messages, maxTokens)
            } catch (e: LiteRtException) {
                Log.w(TAG, "LiteRT failed, no fallback configured: ${e.message}")
                throw e
            }
        }
    }

    /**
     * Streaming variant — currently only OPENAI_COMPAT and GEMINI support it.
     * LITERT streaming is implemented internally by LocalLlmClient (greedy
     * decode loop) but not exposed as a Flow here yet; callers that need it
     * should call LocalLlmClient directly.
     */
    fun stream(
        provider: LlmProvider,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<LlmClient.Message>,
        temperature: Float = 0.2f,
        maxTokens: Int = 2048,
    ): kotlinx.coroutines.flow.Flow<String> = when (provider) {
        LlmProvider.OPENAI_COMPAT -> openAi.stream(
            baseUrl, apiKey, model, messages, temperature, maxTokens,
        )
        LlmProvider.GEMINI -> {
            val url = baseUrl.ifBlank { gemini.defaultBaseUrl }
            gemini.stream(url, apiKey, model, messages, temperature, maxTokens)
        }
        LlmProvider.LITERT -> {
            // Streaming not yet exposed — return a single-element flow with
            // the full completion. Callers that want token-by-token should
            // call LocalLlmClient directly.
            //
            // D-L5: temperature is not forwarded — LocalLlmClient uses greedy
            // decode and does not yet sample.
            kotlinx.coroutines.flow.flow {
                val (family, path) = parseLocalModelSpec(model)
                val result = local.complete(path, family, messages, maxTokens)
                emit(result.text)
            }
        }
    }

    /** Split "local-gemma:models/gemma-2b.tflite" -> ("gemma", "models/gemma-2b.tflite"). */
    private fun parseLocalModelSpec(model: String): Pair<String, String> {
        val s = model.removePrefix("local-")
        val colon = s.indexOf(':')
        return if (colon > 0) {
            Pair(s.substring(0, colon), s.substring(colon + 1))
        } else {
            // Default family "gemma" if none specified.
            Pair("gemma", s)
        }
    }

    companion object {
        private const val TAG = "UnifiedLlmClient"
    }
}
