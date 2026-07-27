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
 * [runCatching] that re-throws [CancellationException] instead of swallowing it.
 *
 * Standard `runCatching { ... }` catches every [Throwable] including
 * [CancellationException], which breaks structured concurrency: a cancelled
 * coroutine (parent scope timeout, user stop, supersession) gets converted
 * into a `Result.failure` and the cancellation never propagates. This helper
 * restores the contract by re-throwing CancellationException.
 *
 * Duplicated (top-level, file-private) in each layer that needs it because
 * the shared `core/` package is owned by a different fix subagent.
 */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/**
 * Sends messages to external channels (Feishu / Discord).
 *
 * Mirrors the original X-OmniClaw channel-config skill: the agent can push
 * summaries, notifications, or results to a configured webhook.
 *
 * Both Feishu and Discord use simple webhook POSTs with a JSON body —
 * the only difference is the payload schema:
 *   - Discord: `{ "content": "..." }`
 *   - Feishu:  `{ "msg_type": "text", "content": { "text": "..." } }`
 *     (Feishu's v2 custom-bot API nests the text under `content.text`;
 *      putting it at the top level silently drops the message.)
 *
 * Security: the webhook URL contains the secret token (e.g. Discord
 * `webhooks/<id>/<token>` or Feishu `/open-apis/bot/v2/hook/<token>`).
 * All log output is routed through [AgentLogger] which redacts webhook URLs,
 * AND the URL is also redacted locally via [redactWebhookUrl] BEFORE being
 * interpolated into the failure log message — defense in depth in case the
 * logger's redactor misses a host. (A-H6)
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
        sendToChannel(webhook, text)
    }

    /**
     * Send [text] to the configured Feishu (Lark) custom-bot webhook.
     *
     * A-L1 FIX: this method was referenced in the class docstring but had
     * NO implementation, so the documented Feishu channel was silently
     * broken (no method existed for callers to invoke). The Feishu v2
     * custom-bot API requires the payload
     * `{ "msg_type": "text", "content": { "text": "..." } }` — the text MUST
     * be nested under `content.text`, not at the top level (Discord style).
     */
    suspend fun sendToFeishu(text: String): Boolean = withContext(Dispatchers.IO) {
        val cfg = settings.channelConfig.first()
        val webhook = cfg.feishuWebhook
        if (webhook.isBlank()) return@withContext false
        sendToChannel(webhook, text)
    }

    /**
     * Route [text] to the appropriate channel payload format based on the
     * [url] host, then POST it. (A-L1)
     *
     * Detection: URLs whose host contains `discord.com` get the Discord
     * payload `{ "content": "..." }`; all other URLs are treated as Feishu
     * (Lark) custom-bot webhooks and get the Feishu payload
     * `{ "msg_type": "text", "content": { "text": "..." } }`. Host-based
     * routing lets callers store a single webhook URL without naming the
     * provider — the URL itself disambiguates.
     *
     * Both `sendToDiscord` and `sendToFeishu` delegate here so the
     * payload-building logic lives in exactly one place; the type-specific
     * entry points remain for callers that want to address a single
     * configured channel by name (and to skip the host check).
     */
    suspend fun sendToChannel(url: String, text: String): Boolean = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext false
        val payload = if (url.contains("discord.com")) {
            // Discord webhook expects { "content": "..." }
            buildJsonObject { put("content", text) }
        } else {
            // Feishu v2 custom-bot expects { "msg_type": "text", "content": { "text": "..." } }
            buildJsonObject {
                put("msg_type", "text")
                put("content", buildJsonObject { put("text", text) })
            }
        }
        postJson(url, payload.toString())
    }

    /**
     * Redact the secret token in a webhook URL before interpolating it into
     * log / exception messages. Covers Discord (`webhooks/<id>/<token>`) and
     * Feishu (`/open-apis/bot/v2/hook/<token>`). Returns the URL with the
     * token segment replaced by `REDACTED`. (A-H6)
     */
    private fun redactWebhookUrl(url: String): String =
        url.replace(Regex("webhooks/[^/]+/[^/?]+"), "webhooks/REDACTED")
            .replace(Regex("bot/v2/hook/[^/?]+"), "bot/v2/hook/REDACTED")

    private fun postJson(url: String, body: String): Boolean = runCatchingCancellable {
        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                // A-H6 FIX: redact the webhook URL (token is in the path) BEFORE
                // interpolating into the log message — defense in depth, in case
                // the logger's redactor misses a host. Also do NOT log the raw
                // response body (A-L2): the body could echo the secret URL back
                // (Discord does this on auth failure) and the previous
                // `r.body?.string()?.take(200)` slurped the ENTIRE response
                // before truncating — unbounded I/O on a 500 response. The HTTP
                // code alone is enough for debugging.
                val safeUrl = redactWebhookUrl(url)
                logger.logInfo(
                    sessionId = "-",
                    step = 0,
                    msg = "Channel POST to $safeUrl failed: HTTP ${r.code}"
                )
            }
            r.isSuccessful
        }
    }.onFailure { e ->
        // A-H6 FIX: redact the URL here too — the previous form interpolated
        // the raw URL (with token) into the failure log.
        val safeUrl = redactWebhookUrl(url)
        logger.logInfo(
            sessionId = "-",
            step = 0,
            msg = "Channel POST to $safeUrl threw: ${e.message}"
        )
    }.getOrDefault(false)
}
