package com.omniclaw.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.omniclaw.app.data.local.LocalLlmClient
import com.omniclaw.app.data.llm.LlmClient
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OmniApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var localLlmClient: LocalLlmClient

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannels()
        registerLocalTokenizers()
    }

    /**
     * Register pluggable tokenizers for the LiteRT local-LLM path.
     *
     * In production you'd ship real SentencePiece / Tiktoken files under
     * assets/tokenizers/<family>/ and instantiate a proper tokenizer here.
     * For now we register a no-op fallback tokenizer for each known family
     * so the LITERT path doesn't crash with "no tokenizer registered" —
     * real tokenizers override these when their files are present.
     */
    private fun registerLocalTokenizers() {
        val fallback = FallbackTokenizer
        listOf("gemma", "tinyllama", "phi", "llama").forEach { family ->
            localLlmClient.registerTokenizer(family, fallback)
        }
    }

    private fun registerNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_AGENT,
                getString(R.string.fg_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.fg_service_channel_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = getString(R.string.overlay_channel_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_AGENT = "agent.fg"
        const val CHANNEL_OVERLAY = "overlay.bubble"
    }
}

/**
 * No-op fallback tokenizer — used when no real tokenizer.json is bundled.
 *
 * This is a CHARACTER-LEVEL tokenizer: it encodes each char to its Unicode
 * code point and decodes back. It won't produce meaningful LLM output (the
 * model's vocabulary is token-based, not char-based), but it lets the LiteRT
 * path run end-to-end for testing the runtime plumbing without a real
 * tokenizer. Replace by registering a real tokenizer at app startup.
 */
private object FallbackTokenizer : LocalLlmClient.Tokenizer {
    override fun encode(text: String): IntArray =
        IntArray(text.length) { text[it].code }

    override fun decode(tokens: IntArray): String =
        String(tokens.map { it.toChar() }.toCharArray())

    override fun chatTemplate(messages: List<LlmClient.Message>): String =
        messages.joinToString("\n") { m ->
            when (m.role.lowercase()) {
                "system" -> "[SYSTEM] ${m.content}"
                "user" -> "[USER] ${m.content}"
                "assistant" -> "[ASSISTANT] ${m.content}"
                "tool" -> "[TOOL] ${m.content}"
                else -> "${m.role}: ${m.content}"
            }
        } + "\n[ASSISTANT] "

    override val eosTokenId: Int = 0  // null char — unlikely in normal text
    override val padTokenId: Int = 0
    override val maxContextLength: Int = 512  // conservative for small models
}

