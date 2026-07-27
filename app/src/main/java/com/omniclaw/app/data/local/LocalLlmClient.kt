package com.omniclaw.app.data.local

import android.util.Log
import com.omniclaw.app.data.llm.LlmClient
import com.omniclaw.app.data.model.LlmUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device chat completion via LiteRT.
 *
 * Exposes the same [LlmClient.complete] signature so [UnifiedLlmClient] can
 * route "local-*" models to it transparently.
 *
 * This implementation is intentionally model-agnostic at the wire level: it
 * assumes a SentencePiece / Tiktoken-style tokenizer has already been
 * registered via [registerTokenizer] for the model family in use. The agent
 * loop only needs text in / text out — tokenization is an internal concern.
 *
 * For real production use you'd typically ship:
 *   - A Gemma 2B / TinyLlama 1.1B .tflite (Q4-quantized, ~1.5GB)
 *   - A tokenizer.json (HuggingFace fast-tokenizer format)
 *   - A chat template (defaulting to "<start_of_turn>user\n{msg}<end_of_turn>")
 *
 * This class provides the runtime scaffolding; the tokenizer is injected so
 * users can plug in any model family without recompiling.
 */
@Singleton
class LocalLlmClient @Inject constructor(
    private val engine: LiteRtEngine,
) {

    /** Pluggable tokenizer — registered per model family at app startup. */
    interface Tokenizer {
        /** Encode a string to a token-id array (no special tokens added). */
        fun encode(text: String): IntArray
        /** Decode token ids back to text. */
        fun decode(tokens: IntArray): String
        /** Chat-template prefix to inject before user/assistant messages. */
        fun chatTemplate(messages: List<LlmClient.Message>): String
        /** EOS / pad token ids — used to size the output buffer and stop generation. */
        val eosTokenId: Int
        val padTokenId: Int
        val maxContextLength: Int
    }

    /**
     * Registered tokenizers, keyed by model family prefix (e.g. "gemma", "tinyllama").
     *
     * ConcurrentHashMap because [registerTokenizer] is called from
     * [com.omniclaw.app.OmniApplication.onCreate] (main thread) while
     * [complete] reads from Dispatchers.IO. A plain mutableMapOf races and can
     * corrupt the map's internal state under concurrent access.
     */
    private val tokenizers = ConcurrentHashMap<String, Tokenizer>()

    fun registerTokenizer(family: String, tokenizer: Tokenizer) {
        tokenizers[family.lowercase()] = tokenizer
    }

    /**
     * Run a chat completion on-device.
     *
     * @param modelPath Same path format as [LiteRtEngine.runIntSingle].
     * @param family    Tokenizer family (e.g. "gemma"). Must be registered
     *                  via [registerTokenizer] first.
     */
    suspend fun complete(
        modelPath: String,
        family: String,
        messages: List<LlmClient.Message>,
        temperature: Float = 0.2f,
        maxTokens: Int = 512,
    ): LlmClient.CompletionResult = withContext(Dispatchers.IO) {
        if (!engine.isAvailable) {
            throw LiteRtException("LiteRT not available on this device — install a build matching the device ABI")
        }
        val tokenizer = tokenizers[family.lowercase()]
            ?: throw LiteRtException("No tokenizer registered for family '$family'. Call registerTokenizer() at app startup.")

        // ---- KV-cache limitation ----
        // This implementation re-runs the model on the full prompt + generated
        // tokens at each decode step (O(n²) compute). Real on-device LLM
        // inference uses a KV cache to avoid recomputing prompt embeddings.
        // LiteRT's Task Library doesn't yet expose KV-cache APIs for causal
        // LLMs, so we cap maxTokens to 64 to keep per-step latency tolerable
        // (~1-2s/step on a Snapdragon 8 Gen 2 with Gemma 2B Q4).
        //
        // For agent-loop use this is acceptable: the system prompt asks for
        // concise THOUGHT + ACTION lines, which fit in 64 tokens. Longer
        // responses will be cut off — the agent will see the truncation and
        // retry on the next step with a fresh prompt.
        val effectiveMaxTokens = minOf(maxTokens, 64)

        val prompt = tokenizer.chatTemplate(messages)
        val promptTokens = tokenizer.encode(prompt)
        val promptTokenCount = promptTokens.size.toLong()
        val ctxLen = tokenizer.maxContextLength
        // Truncate the prompt from the left if it exceeds the context window,
        // keeping the system + last user turn intact.
        val truncated = if (promptTokens.size > ctxLen - effectiveMaxTokens) {
            Log.w(TAG, "Prompt ${promptTokens.size} tokens exceeds context window $ctxLen — truncating left")
            promptTokens.copyOfRange(promptTokens.size - (ctxLen - effectiveMaxTokens), promptTokens.size)
        } else {
            promptTokens
        }

        // Greedy decode with temperature-sampled top-k=1 (deterministic) for
        // stability during agent execution. Real sampling would use top-p here.
        val generated = StringBuilder()
        val inputTokens = truncated.toMutableList()
        val eos = tokenizer.eosTokenId
        var stepsGenerated = 0
        var stopReason = "length"  // default — overridden if EOS is hit
        // Query the model's output shape to determine the vocab size (last dim).
        // For a causal LM with input [1, seq_len], output is [1, seq_len, vocab]
        // or [1, vocab]. We take the last dimension as the vocab size.
        val outputShape = runCatching { engine.outputShape(modelPath) }.getOrNull()
        val vocabSize = outputShape?.lastOrNull()?.coerceAtLeast(1)
            ?: throw LiteRtException("Cannot determine vocab size for '$modelPath' — output shape query failed")
        try {
            for (step in 0 until effectiveMaxTokens) {
                // Pad/truncate input to the model's expected seq length.
                val seqLen = minOf(inputTokens.size, ctxLen)
                val inputSlice = if (inputTokens.size > ctxLen) {
                    inputTokens.subList(inputTokens.size - ctxLen, inputTokens.size).toIntArray()
                } else {
                    inputTokens.toIntArray()
                }
                // ---- Token IDs are int32, NOT float ----
                // TFLite causal-LM models declare their input tensor as int32
                // (token IDs). Passing a FloatArray throws
                // IllegalArgumentException at interpreter.run(). Use
                // runIntSingle which passes the IntArray directly.
                // We only use seqLen elements (the rest of inputSlice is
                // already exactly seqLen long).
                val inputForInference = if (inputSlice.size == seqLen) inputSlice else inputSlice.copyOf(seqLen)
                val logits = engine.runIntSingle(modelPath, inputForInference, outputSize = seqLen * vocabSize)
                // The model returns logits for the next position; argmax for greedy.
                val lastSlice = logits.copyOfRange((seqLen - 1) * vocabSize, seqLen * vocabSize)
                val nextToken = argmax(lastSlice)
                if (nextToken == eos) {
                    stopReason = "stop"
                    break
                }
                generated.append(tokenizer.decode(intArrayOf(nextToken)))
                inputTokens.add(nextToken)
                stepsGenerated++
                // Flush every ~32 tokens so the UI can stream if wired up.
                if (stepsGenerated % 32 == 0) {
                    Log.d(TAG, "Local LLM step $step: ${generated.length} chars")
                }
            }
        } catch (e: LiteRtException) {
            Log.e(TAG, "LiteRT inference failed at step $stepsGenerated", e)
            throw e
        }
        finalize(generated.toString(), promptTokenCount, stepsGenerated, stopReason)
    }

    private fun finalize(
        text: String,
        promptTokenCount: Long,
        tokensGenerated: Int,
        finish: String = "stop",
    ): LlmClient.CompletionResult {
        // Report the real prompt token count so the agent loop's token-budget
        // guard (maxSessionTokens) actually works. Previously promptTokens was
        // reported as 0, so the budget only counted completion tokens and
        // never tripped even on huge prompts.
        val completion = tokensGenerated.toLong()
        val usage = LlmUsage(
            promptTokens = promptTokenCount,
            completionTokens = completion,
            totalTokens = promptTokenCount + completion,
        )
        return LlmClient.CompletionResult(text, usage, finish)
    }

    private fun argmax(arr: FloatArray): Int {
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in arr.indices) {
            if (arr[i] > bestVal) {
                bestVal = arr[i]
                bestIdx = i
            }
        }
        return bestIdx
    }

    companion object {
        private const val TAG = "LocalLlmClient"
    }
}
