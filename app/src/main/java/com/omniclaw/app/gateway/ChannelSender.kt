package com.omniclaw.app.gateway

import com.omniclaw.app.data.prefs.SettingsRepository
import com.omniclaw.app.logging.AgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 *
 * Security: the webhook URL contains the secret token (e.g. Discord
 * `webhooks/<id>/<token>`). All log output is routed through [AgentLogger]
 * which redacts webhook URLs to `https://discord.com/REDACTED`.
 */
@Singleton
class ChannelSender @Inject constructor(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val logger: AgentLogger,
) {

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
                // Logger redacts the webhook URL (token is in the path).
                logger.logInfo(
                    sessionId = "-",
                    step = 0,
                    msg = "Channel POST to $url failed: HTTP ${r.code} ${r.body?.string()?.take(200).orEmpty()}"
                )
            }
            r.isSuccessful
        }
    }.onFailure { e ->
        logger.logInfo(
            sessionId = "-",
            step = 0,
            msg = "Channel POST to $url threw: ${e.message}"
        )
    }.getOrDefault(false)
}
