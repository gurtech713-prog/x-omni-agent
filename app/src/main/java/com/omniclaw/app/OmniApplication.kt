package com.omniclaw.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.StrictMode
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.omniclaw.app.BuildConfig
import com.omniclaw.app.data.local.LocalLlmClient
import com.omniclaw.app.data.llm.LlmClient
import com.omniclaw.app.voice.TextToSpeechManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OmniApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var localLlmClient: LocalLlmClient
    @Inject lateinit var ttsManager: TextToSpeechManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        // Install a process-wide uncaught-exception handler BEFORE any subsystem
        // initializes — if Hilt graph construction or DB init throws, we still
        // capture the trace. The handler logs the crash and then delegates to
        // the previous handler (the default JVM behavior, which terminates the
        // process) so crash-reporting SDKs chained later still see the throw.
        installCrashHandler()
        // StrictMode in debug builds surfaces accidental main-thread I/O and
        // resource leaks (unclosed cursors, etc.) as log warnings + a visible
        // dialog. Disabled in release.
        if (BuildConfig.DEBUG) installStrictMode()
        super.onCreate()
        registerNotificationChannels()
        registerLocalTokenizers()
    }

    /**
     * Release native / system resources when the process is being torn down.
     * [onTerminate] is only called on emulator / debug builds in some cases,
     * but it's the correct place to release the TTS engine if it fires.
     * The TTS engine is also cleaned up via [TextToSpeechManager.shutdown]
     * from the chat ViewModel's onCleared.
     */
    override fun onTerminate() {
        runCatching { ttsManager.shutdown() }
        super.onTerminate()
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
        private const val TAG = "OmniApp"

        /**
         * Process-wide uncaught-exception handler. Logs the crash to Logcat
         * with the offending thread + stack, then delegates to the previous
         * handler. In release builds, this is the only crash signal we get;
         * the user sees the standard "App keeps stopping"
         * dialog. In debug, StrictMode + Logcat together give full visibility.
         */
        private fun installCrashHandler() {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
                } catch (_: Throwable) {
                    // Logging itself failed — nothing more we can do.
                }
                previous?.uncaughtException(thread, throwable)
            }
        }

        /**
         * StrictMode in debug only — flags main-thread disk reads/writes,
         * network access (via `penaltyLog`), and untagged SQLite cursors.
         * Penalty is LOG only (no death penalty / dialog) so the app stays
         * usable while still surfacing violations in Logcat.
         */
        private fun installStrictMode() {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }
    }
}

/**
 * No-op fallback tokenizer — used when no real tokenizer.json is bundled.
 *
 * This is a CHARACTER-LEVEL tokenizer: it encodes each Unicode code point
 * and decodes back. It won't produce meaningful LLM output (the model's
 * vocabulary is token-based, not char-based), but it lets the LiteRT path
 * run end-to-end for testing the runtime plumbing without a real tokenizer.
 * Replace by registering a real tokenizer at app startup.
 */
private object FallbackTokenizer : LocalLlmClient.Tokenizer {
    override fun encode(text: String): IntArray =
        text.codePoints().toArray()

    override fun decode(tokens: IntArray): String =
        String(tokens, 0, tokens.size)

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
