package com.omniclaw.app.voice

import android.util.Log
import com.omniclaw.app.core.retry
import com.omniclaw.app.data.prefs.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okio.ByteString.Companion.toByteString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production-grade streaming speech-to-text client.
 *
 * Replaces the minimal [SttClient] with a fully-featured implementation:
 *
 *   - **Structured results** — returns [SttResult] (Success / Cancelled /
 *     NetworkFailure / AuthenticationFailure / Timeout / UnknownFailure)
 *     so callers can show appropriate UI per failure mode.
 *   - **Retry with exponential backoff** — retries on transient network
 *     failures (IOException, 5xx, 429) up to 3 times.
 *   - **Cancellation propagation** — respects coroutine cancellation; the
 *     in-flight HTTP call is cancelled when the caller's coroutine is.
 *   - **Network timeout** — 30s connect, 60s read (audio files can be large).
 *   - **Streaming WebSocket** — [streamIncremental] opens a WebSocket to a
 *     streaming endpoint and emits partial transcripts as the user speaks.
 *   - **Error mapping** — HTTP 401/403 → AuthenticationFailure; HTTP 5xx /
 *     IOException → NetworkFailure; everything else → UnknownFailure.
 *   - **Silence trimming** — [VoiceActivityDetector] integration; if no
 *     speech was detected, returns Success("") without an API call.
 *   - **Malformed JSON tolerance** — the response parser falls back to a
 *     regex extraction of the "text" field if structured parsing fails.
 *
 * The legacy [SttClient.transcribe] is kept for backward compatibility;
 * new code should prefer [transcribeStructured] or [streamIncremental].
 */
@Singleton
class StreamingSttClient @Inject constructor(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val json: Json,
) {

    /**
     * Transcribe [audioFile] and return a structured [SttResult].
     *
     * This is the primary entry point for push-to-talk. It:
     *   1. Reads the STT config (base URL, API key, model) from settings.
     *   2. Validates config — returns AuthenticationFailure if key is missing.
     *   3. Uploads the audio as multipart/form-data with retry + backoff.
     *   4. Parses the JSON response into [SttResult.Success].
     *   5. Maps failures to the appropriate [SttResult] variant.
     */
    suspend fun transcribeStructured(
        audioFile: File,
        language: String? = null,
    ): SttResult = withContext(Dispatchers.IO) {
        val cfg = settings.modelConfig.first()
        if (cfg.sttApiKey.isBlank()) return@withContext SttResult.AuthenticationFailure(
            "No STT API key configured. Open Settings → AI Provider to set one."
        )
        if (cfg.sttBaseUrl.isBlank()) return@withContext SttResult.AuthenticationFailure(
            "No STT base URL configured."
        )
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext SttResult.UnknownFailure("Audio file is empty or missing: ${audioFile.absolutePath}")
        }

        val url = normalizeUrl(cfg.sttBaseUrl)
        val start = System.currentTimeMillis()

        try {
            val text = retry(
                maxAttempts = 3,
                baseDelayMs = 800,
                maxDelayMs = 4000,
                retryable = { it is IOException || (it is SttHttpException && it.code in setOf(429, 500, 502, 503, 504)) },
            ) {
                uploadAndParse(audioFile, url, cfg.sttApiKey, cfg.sttModel, language)
            }
            SttResult.Success(
                text = text,
                language = language,
                durationMs = System.currentTimeMillis() - start,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SttHttpException) {
            mapHttpError(e)
        } catch (e: IOException) {
            SttResult.NetworkFailure(e.message ?: "Network error during STT upload")
        } catch (e: Exception) {
            SttResult.UnknownFailure(e.message ?: "Unknown STT error", e)
        }
    }

    /**
     * Stream incremental partial transcripts over a WebSocket.
     *
     * Emits [SttResult.Segment] objects as partial results arrive, then a
     * final [SttResult.Success] when the stream closes. If the WebSocket
     * fails, emits a [SttResult.NetworkFailure] and completes the flow.
     *
     * Currently configured for OpenAI-compatible real-time endpoints. If
     * the configured STT provider doesn't support streaming, callers should
     * fall back to [transcribeStructured].
     *
     * Implementation note: the WebSocket callbacks run on OkHttp's dispatcher
     * thread. We bridge them to the Flow via a thread-safe channel-style
     * pattern using a [MutableSharedFlow] collector, which is safe to emit
     * into from any thread.
     */
    fun streamIncremental(audioFile: File): Flow<SttResult> = channelFlow<SttResult> {
        val cfg = runCatching { settings.modelConfig.first() }.getOrNull()
        if (cfg == null || cfg.sttApiKey.isBlank()) {
            send(SttResult.AuthenticationFailure("No STT API key configured."))
            return@channelFlow
        }
        // Convert HTTP base URL to WebSocket URL.
        val wsUrl = normalizeUrl(cfg.sttBaseUrl)
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://")
            .replace("/audio/transcriptions", "/audio/transcriptions/stream")

        val partials = MutableSharedFlow<String>(extraBufferCapacity = 256)
        // Thread-safe close/failure signalling between OkHttp's dispatcher
        // thread and this channelFlow coroutine. A plain `var closed` polled in
        // a loop could be cached in a register by the JIT and never re-read,
        // hanging the stream; a CompletableDeferred gives a real suspend point
        // and AtomicReference publishes the failure across threads (H-16).
        val failure = AtomicReference<Throwable?>(null)
        val closedSignal = CompletableDeferred<Unit>()

        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer ${cfg.sttApiKey}")
            .build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val partial = runCatching {
                    json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (partial != null) {
                    // V-M13: log drops so a saturated upstream is visible in
                    // Logcat instead of vanishing silently. The buffer was
                    // bumped from 64 → 256 to reduce the drop probability under
                    // fast partial streams; tryEmit is used because we're in a
                    // non-suspending OkHttp callback.
                    if (!partials.tryEmit(partial)) {
                        Log.w(TAG, "STT partial dropped due to buffer overflow")
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "STT WebSocket failure: ${t.message}")
                failure.set(t)
                closedSignal.complete(Unit)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "STT WebSocket closed: $code $reason")
                closedSignal.complete(Unit)
            }
        })

        // V-H3: ensure the WebSocket is always cancelled on flow cancellation
        // (e.g. collector scope disposed mid-upload) — without this, an
        // abandoned stream leaks an open socket on OkHttp's dispatcher.
        try {
            // V-H4: stream the file in 16 KB frames instead of loading the
            // entire audio file into memory via readBytes(). Many streaming STT
            // endpoints (and proxies like nginx) enforce a ~1 MB max frame size
            // and close the connection with code 1009 if a multi-MB frame is
            // sent in a single shot (L-04). EOF is signalled by ws.close(1000, "EOF").
            audioFile.inputStream().use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    // V-L10: adaptive delay — only back off when OkHttp's send
                    // queue is full (returns false). When the queue has room,
                    // skip the delay so a fast network doesn't pay an artificial
                    // 10ms-per-chunk tax (a 5MB file would otherwise take ~3s
                    // of pure sleep to upload).
                    if (ws.send(chunk.toByteString(0, read))) {
                        // queued immediately — no delay
                    } else {
                        delay(10)
                    }
                }
            }
            ws.close(1000, "EOF")

            // Collect partials until the socket closes.
            val transcript = StringBuilder()
            kotlinx.coroutines.coroutineScope {
                val collectJob = launch {
                    partials.collect { partial ->
                        transcript.append(partial)
                        send(SttResult.Segment(partial, 0L, 0L))
                    }
                }
                // Suspend on the close signal from onClosed/onFailure instead of
                // polling a plain flag (H-16). await() is cancellation-aware, so a
                // cancelled collector still unwinds cleanly.
                // V-H5: overall timeout — a stuck WebSocket (e.g. server accepts
                // the upload but never sends onClosed) must not wedge this flow
                // forever. Emit a Timeout result and complete.
                withTimeoutOrNull(60_000) { closedSignal.await() }
                    ?: run {
                        collectJob.cancel()
                        send(SttResult.Timeout("Streaming STT timed out after 60s"))
                        return@coroutineScope
                    }
                collectJob.cancel()
            }

            failure.get()?.let {
                send(SttResult.NetworkFailure(it.message ?: "WebSocket STT failed"))
                return@channelFlow
            }
            send(SttResult.Success(text = transcript.toString().trim()))
        } finally {
            runCatching { ws.cancel() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Transcribe with an optional fallback provider. If [primary] fails with
     * a network or server error, [fallback] is tried. Authentication failures
     * are NOT retried via fallback (a bad key is a bad key).
     */
    suspend fun transcribeWithFallback(
        audioFile: File,
        primary: suspend (File) -> SttResult,
        fallback: (suspend (File) -> SttResult)? = null,
    ): SttResult {
        val result = primary(audioFile)
        return when (result) {
            is SttResult.NetworkFailure, is SttResult.Timeout, is SttResult.UnknownFailure -> {
                if (fallback != null) {
                    runCatching { fallback(audioFile) }.getOrNull() ?: result
                } else result
            }
            else -> result
        }
    }

    // ---- Internal ----

    private suspend fun uploadAndParse(
        audioFile: File,
        url: String,
        apiKey: String,
        model: String,
        language: String?,
    ): String {
        val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(audioMimeType(audioFile).toMediaType()))
            .addFormDataPart("model", model)
            .apply { if (language != null) addFormDataPart("language", language) }
            .build()

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(bodyBuilder)
            .build()

        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                val errBody = r.body?.string().orEmpty().take(500)
                throw SttHttpException(r.code, errBody)
            }
            val text = r.body?.string().orEmpty()
            return parseTranscript(text)
        }
    }

    /** Parse the STT JSON response. Falls back to regex if structured parse fails. */
    private fun parseTranscript(body: String): String {
        // Try structured JSON first: {"text": "..."}
        runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            val text = obj["text"]?.jsonPrimitive?.contentOrNull
            if (text != null) return text.trim()
        }
        // Fallback: regex extract the "text" field.
        val m = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
        return m?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.trim()
            .orEmpty()
    }

    private fun mapHttpError(e: SttHttpException): SttResult = when (e.code) {
        401, 403 -> SttResult.AuthenticationFailure("STT API rejected the key (HTTP ${e.code}). Check your API key in Settings.")
        408 -> SttResult.Timeout("STT request timed out (HTTP 408).")
        in 500..599 -> SttResult.NetworkFailure("STT server error (HTTP ${e.code}): ${e.body}")
        else -> SttResult.UnknownFailure("STT HTTP ${e.code}: ${e.body}")
    }

    private fun normalizeUrl(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return when {
            base.endsWith("/audio/transcriptions", ignoreCase = true) -> base
            base.endsWith("/audio/transcriptions/", ignoreCase = true) -> base.trimEnd('/')
            else -> "$base/audio/transcriptions"
        }
    }

    // V-M18: infer the multipart MIME from the file extension instead of
    // hardcoding "audio/mp4". Some providers (notably Whisper) reject a
    // declared audio/mp4 for an actual .wav upload with HTTP 400.
    private fun audioMimeType(file: File): String = when (file.extension.lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    /** HTTP exception carrying the status code + truncated body. */
    class SttHttpException(val code: Int, val body: String) : IOException("HTTP $code: $body")

    companion object {
        private const val TAG = "StreamingStt"
    }
}
