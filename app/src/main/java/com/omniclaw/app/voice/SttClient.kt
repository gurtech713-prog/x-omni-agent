package com.omniclaw.app.voice

import com.omniclaw.app.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
) {

    suspend fun transcribe(audioFile: File): String? = withContext(Dispatchers.IO) {
        val cfg = settings.modelConfig.first()
        if (cfg.sttApiKey.isBlank()) return@withContext null
        if (cfg.sttBaseUrl.isBlank()) return@withContext null

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/mp4".toMediaType()))
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
                // Response shape: {"text":"..."} — extract just the text field.
                val m = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)
                m?.groupValues?.getOrNull(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")?.trim()
            }
        }.getOrNull()
    }
}
