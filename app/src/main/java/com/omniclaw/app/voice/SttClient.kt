package com.omniclaw.app.voice

import com.omniclaw.app.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speech-to-text client. Mirrors the original X-OmniClaw STT config:
 *   - Default provider: SiliconFlow
 *   - Default model: FunAudioLLM/SenseVoiceSmall
 *
 * Compatible with any OpenAI-style /audio/transcriptions endpoint
 * (SiliconFlow, OpenAI Whisper, Groq, etc.).
 */
@Singleton
class SttClient @Inject constructor(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val json: Json,
) {

    suspend fun transcribe(audioFile: File): String? = withContext(Dispatchers.IO) {
        val cfg = settings.modelConfig.first()
        if (cfg.sttApiKey.isBlank()) return@withContext null
        if (cfg.sttBaseUrl.isBlank()) return@withContext null

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(audioMimeType(audioFile).toMediaType()))
            .addFormDataPart("model", cfg.sttModel)
            .build()

        // Normalize the STT URL. The setting is labeled "STT BASE URL" but the
        // default value is a full endpoint (…/v1/audio/transcriptions). Accept
        // both: if the URL already ends with /audio/transcriptions use it as-is,
        // otherwise append the standard OpenAI-compat transcription path.
        val url = cfg.sttBaseUrl.trimEnd('/').let { base ->
            when {
                base.endsWith("/audio/transcriptions", ignoreCase = true) -> base
                base.endsWith("/audio/transcriptions/", ignoreCase = true) -> base.trimEnd('/')
                else -> "$base/audio/transcriptions"
            }
        }

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.sttApiKey}")
            .header("Accept", "application/json")
            .post(body)
            .build()

        runCatching {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    android.util.Log.w("SttClient", "STT HTTP ${r.code}: ${r.body?.string()?.take(300).orEmpty()}")
                    return@use null
                }
                val text = r.body?.string().orEmpty()
                // Response shape: {"text":"..."} — parse the top-level "text" field
                // structurally so nested shapes like {"segments":[{"text":"a"}],"text":"b"}
                // return "b" (the regex used to grab the first "text", i.e. "a").
                parseTranscript(text)
            }
        }.getOrNull()
    }

    /** Parse the STT JSON response. Falls back to regex if structured parse fails. */
    private fun parseTranscript(body: String): String? {
        // Try structured JSON first: {"text": "..."}
        runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            val text = obj["text"]?.jsonPrimitive?.contentOrNull
            if (text != null) return text.trim()
        }
        // Fallback: regex extract the LAST "text" field. Some providers return
        // {"segments":[{"text":"a"},...],"text":"b"} where the top-level text
        // is the joined transcript; if structured parsing fails, the LAST text
        // field in the JSON is the most likely full-transcript value.
        val matches = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(body)
        return matches.lastOrNull()?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?.trim()
    }

    // V-M18: infer the multipart MIME from the file extension instead of
    // hardcoding "audio/mp4". Mirrors StreamingSttClient.audioMimeType.
    private fun audioMimeType(file: File): String = when (file.extension.lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}
