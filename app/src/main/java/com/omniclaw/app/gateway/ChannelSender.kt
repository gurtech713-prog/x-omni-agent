package com.omniclaw.app.gateway

import com.omniclaw.app.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends messages to external channels (Feishu / Discord).
 *
 * Mirrors the original X-OmniClaw channel-config skill: the agent can push
 * summaries, notifications, or results to a configured webhook.
 *
 * Both Feishu and Discord use simple webhook POSTs with a JSON body —
 * the only difference is the field name ("text" for Feishu's bot v2 hook,
 * "content" for Discord).
 */
@Singleton
class ChannelSender @Inject constructor(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
) {

    suspend fun sendToFeishu(text: String): Boolean = withContext(Dispatchers.IO) {
        val cfg = settings.channelConfig.first()
        val webhook = cfg.feishuWebhook
        if (webhook.isBlank()) return@withContext false
        // Feishu bot v2 webhook expects { "msg_type": "text", "content": { "text": "..." } }
        val payload = buildJsonObject {
            put("msg_type", "text")
            putJsonObject("content") { put("text", text) }
        }
        postJson(webhook, payload.toString())
    }

    suspend fun sendToDiscord(text: String): Boolean = withContext(Dispatchers.IO) {
        val cfg = settings.channelConfig.first()
        val webhook = cfg.discordWebhook
        if (webhook.isBlank()) return@withContext false
        // Discord webhook expects { "content": "..." }
        val payload = buildJsonObject { put("content", text) }
        postJson(webhook, payload.toString())
    }

    private fun postJson(url: String, body: String): Boolean = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                android.util.Log.w(
                    "ChannelSender",
                    "Channel POST to ${url.take(60)}… failed: HTTP ${r.code} ${r.body?.string()?.take(200).orEmpty()}"
                )
            }
            r.isSuccessful
        }
    }.onFailure { e ->
        android.util.Log.w("ChannelSender", "Channel POST to ${url.take(60)}… threw: ${e.message}")
    }.getOrDefault(false)
}
