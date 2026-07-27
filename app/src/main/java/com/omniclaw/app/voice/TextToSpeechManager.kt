package com.omniclaw.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Android's [TextToSpeech] engine so the chat UI can optionally speak
 * assistant thoughts aloud.
 *
 * Lifecycle:
 *  - Initialized lazily on first [speak] call (or eagerly on construction
 *    via a background thread so app startup isn't blocked).
 *  - The underlying [TextToSpeech] instance is held for the life of the
 *    singleton and reused across chat sessions.
 *  - [stop] flushes the TTS queue so a new user message interrupts any
 *    in-progress speech.
 *
 * Thread safety:
 *  - [TextToSpeech] is thread-safe for the public API surface we use
 *    (speak / stop / shutdown). We additionally guard the ready/speaking
 *    flags with an [AtomicBoolean] so Compose can read them without races.
 *  - Completion is observed via [UtteranceProgressListener] — NO background
 *    polling thread is spawned per speak() call. This was previously a
 *    per-call Thread.sleep heuristic that leaked 240+ threads per session
 *    under streaming-thoughts usage.
 *  - [shutdown] is @Synchronized and sets [shuttingDown] so the eager init
 *    thread / its async callback can't construct (or mark ready) a new engine
 *    after teardown — previously the init thread could assign tts AFTER
 *    shutdown nulled it, leaking an engine that was never shut down.
 *
 * Error handling:
 *  - If the TTS engine is unavailable (no TTS package on the device),
 *    [speak] silently no-ops rather than crashing. The chat UI continues
 *    to work; only the audio cue is lost.
 *  - Init failures are logged at WARN but do not throw — TTS is strictly
 *    optional and gated by the `ui.ttsEnabled` setting.
 */
@Singleton
class TextToSpeechManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    private val ready = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)

    @Volatile
    private var tts: TextToSpeech? = null

    /** Set by [shutdown] so an in-flight init can't resurrect a leaked engine. */
    @Volatile
    private var shuttingDown = false

    /** Background thread that runs the eager init; interrupted on [shutdown]. */
    private var initThread: Thread? = null

    private val _isReady = MutableStateFlow(false)
    /** True once the TTS engine has reported successful init. */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    /** True while speech is actively being synthesized. */
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        // Initialize eagerly but off the main thread — TextToSpeech's
        // constructor can take 100-300ms on first launch as it binds to the
        // system TTS service. A background thread keeps app startup snappy.
        initThread = Thread({
            ensureInitialized()
        }, "TtsInit").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Lazily create the [TextToSpeech] engine if it hasn't been created yet.
     * Safe to call multiple times — subsequent calls are no-ops once the
     * engine is ready.
     */
    @Synchronized
    fun ensureInitialized() {
        // Don't construct a new engine after shutdown() has run.
        if (shuttingDown) return
        if (ready.get() || tts != null) return
        tts = TextToSpeech(ctx) { status ->
            when {
                shuttingDown -> {
                    // shutdown() ran while we were binding to the TTS service —
                    // tear down the freshly-created engine instead of marking it
                    // ready and leaking it.
                    runCatching { tts?.shutdown() }
                    tts = null
                }
                status == TextToSpeech.SUCCESS -> {
                    runCatching {
                        val result = tts?.setLanguage(Locale.getDefault())
                        if (result == TextToSpeech.LANG_MISSING_DATA ||
                            result == TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            Log.w(TAG, "TTS language not supported; falling back to en-US")
                            tts?.setLanguage(Locale.US)
                        }
                    }
                    // Wire the UtteranceProgressListener for accurate completion
                    // (no per-call Thread.sleep heuristic — those leak threads).
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            speaking.set(true)
                            _isSpeaking.value = true
                        }
                        override fun onDone(utteranceId: String?) {
                            speaking.set(false)
                            _isSpeaking.value = false
                        }
                        @Deprecated("Required override", ReplaceWith(""))
                        override fun onError(utteranceId: String?) {
                            speaking.set(false)
                            _isSpeaking.value = false
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            speaking.set(false)
                            _isSpeaking.value = false
                        }
                    })
                    ready.set(true)
                    _isReady.value = true
                    Log.i(TAG, "TTS engine ready")
                }
                else -> {
                    Log.w(TAG, "TTS init failed: status=$status")
                    tts = null
                }
            }
        }
    }

    /**
     * Speak [text] aloud. If the TTS engine isn't ready yet, the call is
     * silently dropped — TTS is an optional accessibility feature, not a
     * critical path. Any currently-queued speech is flushed first so the
     * new utterance starts immediately.
     *
     * Completion is observed via [UtteranceProgressListener] (no background
     * thread spawned per call).
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts ?: run {
            ensureInitialized()
            tts
        } ?: return
        if (!ready.get()) return
        speaking.set(true)
        _isSpeaking.value = true
        val utteranceId = "omni-${System.currentTimeMillis()}"
        runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }.onFailure { e ->
            Log.w(TAG, "TTS speak failed: ${e.message}")
            speaking.set(false)
            _isSpeaking.value = false
        }
        // No background thread — UtteranceProgressListener.onDone() will clear
        // the speaking flag accurately when synthesis completes.
    }

    /** Immediately stop any in-progress speech and clear the queue. */
    fun stop() {
        runCatching { tts?.stop() }
        speaking.set(false)
        _isSpeaking.value = false
    }

    /** Release the TTS engine. Safe to call multiple times. */
    @Synchronized
    fun shutdown() {
        // Mark shutting down first (and interrupt the eager init thread) so an
        // in-flight ensureInitialized / its async callback can't construct or
        // mark-ready a new engine after we tear the current one down.
        shuttingDown = true
        initThread?.interrupt()
        runCatching { tts?.stop() }
        runCatching { tts?.setOnUtteranceProgressListener(null) }
        runCatching { tts?.shutdown() }
        tts = null
        ready.set(false)
        _isReady.value = false
        speaking.set(false)
        _isSpeaking.value = false
    }

    /**
     * V-M3: lifecycle hook for the SettingsViewModel / ProcessLifecycleObserver.
     *
     * TTS is initialized eagerly on construction and held for the app's life.
     * That's correct when TTS is enabled, but if the user disables TTS in
     * Settings the engine keeps a system service binding open. This helper lets
     * the settings toggle path release the engine as soon as TTS is turned off.
     *
     * The next [speak] (if TTS is re-enabled later) lazily re-initializes via
     * [ensureInitialized] — but note that [ensureInitialized] short-circuits
     * while `shuttingDown` is true, so [resetShutdownFlag] must be called first
     * to allow re-init.
     */
    fun shutdownIfDisabled(ttsEnabled: Boolean) {
        if (!ttsEnabled) shutdown()
    }

    /**
     * V-M3: clear the [shuttingDown] flag so [ensureInitialized] can construct
     * a fresh engine after [shutdownIfDisabled]. Call from the settings toggle
     * path BEFORE the next speak() when re-enabling TTS.
     */
    @Synchronized
    fun resetShutdownFlag() {
        shuttingDown = false
    }

    companion object {
        private const val TAG = "TextToSpeechManager"
    }
}
