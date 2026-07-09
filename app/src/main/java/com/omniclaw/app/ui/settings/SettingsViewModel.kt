package com.omniclaw.app.ui.settings

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omniclaw.app.data.prefs.ChannelConfig
import com.omniclaw.app.data.prefs.ModelConfig
import com.omniclaw.app.data.prefs.PermissionsState
import com.omniclaw.app.data.prefs.SettingsRepository
import com.omniclaw.app.data.prefs.UiPrefs
import com.omniclaw.app.gateway.ChannelSender
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: SettingsRepository,
    private val channels: ChannelSender,
) : ViewModel() {

    val modelConfig: StateFlow<ModelConfig> = repo.modelConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, ModelConfig())

    val channelConfig: StateFlow<ChannelConfig> = repo.channelConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChannelConfig())

    val uiPrefs: StateFlow<UiPrefs> = repo.uiPrefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiPrefs())

    val permissions: StateFlow<PermissionsState> = repo.permissions
        .stateIn(viewModelScope, SharingStarted.Eagerly, PermissionsState())

    suspend fun setModel(cfg: ModelConfig) = repo.setModelConfig(cfg)
    suspend fun setChannel(cfg: ChannelConfig) = repo.setChannelConfig(cfg)
    suspend fun setUi(prefs: UiPrefs) = repo.setUiPrefs(prefs)
    suspend fun setPermissions(state: PermissionsState) = repo.setPermissions(state)

    /** Test-send a message to all configured channels. Returns true if at least one send succeeded. */
    suspend fun testChannels(message: String): Boolean {
        val feishuOk = channels.sendToFeishu(message)
        val discordOk = channels.sendToDiscord(message)
        return feishuOk || discordOk
    }

    /**
     * Export the full config (model + channels + UI prefs) to
     * /sdcard/.xomniclaw/xomniclaw.json — mirrors the original X-OmniClaw
     * config-file location for backup, migration, and debugging.
     */
    suspend fun exportConfig(): File? = withContext(Dispatchers.IO) {
        val m = repo.modelConfig.first()
        val c = repo.channelConfig.first()
        val u = repo.uiPrefs.first()
        val json = buildJsonObject {
            putJsonObject("models") {
                putJsonObject("agent") {
                    put("provider", m.provider.name.lowercase())
                    put("baseUrl", m.baseUrl)
                    // Mask API key — only write first 4 + last 4 chars, drop the middle.
                    put("apiKey", maskSecret(m.apiKey))
                    put("model", m.model)
                    put("temperature", m.temperature.toDouble())
                    put("maxTokens", m.maxTokens)
                }
                putJsonObject("stt") {
                    put("baseUrl", m.sttBaseUrl)
                    put("apiKey", maskSecret(m.sttApiKey))
                    put("model", m.sttModel)
                }
                putJsonObject("vlm") {
                    put("baseUrl", m.vlmBaseUrl)
                    put("apiKey", maskSecret(m.vlmApiKey))
                    put("model", m.vlmModel)
                }
            }
            putJsonObject("channels") {
                put("feishuAppId", c.feishuAppId)
                put("feishuAppSecret", maskSecret(c.feishuAppSecret))
                put("feishuWebhook", maskSecret(c.feishuWebhook))
                put("discordWebhook", maskSecret(c.discordWebhook))
            }
            putJsonObject("ui") {
                put("darkMode", u.darkMode)
                put("monoFont", u.monoFont)
                put("showToolCalls", u.showToolCalls)
                put("showThoughts", u.showThoughts)
                put("showTokens", u.showTokens)
            }
        }
        val dir = File(Environment.getExternalStorageDirectory(), ".xomniclaw")
        dir.mkdirs()
        val file = File(dir, "xomniclaw.json")
        runCatching {
            file.writeText(json.toString())
            file
        }.getOrNull()
    }

    /** Import config from /sdcard/.xomniclaw/xomniclaw.json (best-effort). */
    suspend fun importConfig(): Boolean = withContext(Dispatchers.IO) {
        val file = File(Environment.getExternalStorageDirectory(), ".xomniclaw/xomniclaw.json")
        if (!file.exists()) return@withContext false
        runCatching {
            val text = file.readText()
            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(text).jsonObject
            // Restore model config (agent + STT + VLM).
            // Secrets are masked on export — skip any value containing '*' so we
            // don't overwrite the user's real key with the masked placeholder.
            val models = root["models"]?.jsonObject
            val agent = models?.get("agent")?.jsonObject
            val stt = models?.get("stt")?.jsonObject
            val vlm = models?.get("vlm")?.jsonObject
            val current = repo.modelConfig.first()
            repo.setModelConfig(
                ModelConfig(
                    provider = com.omniclaw.app.data.prefs.LlmProvider.fromString(
                        agent?.get("provider")?.jsonPrimitive?.content
                    ).takeIf { agent?.get("provider") != null } ?: current.provider,
                    baseUrl = unmaskedOr(agent?.get("baseUrl")?.jsonPrimitive?.content, current.baseUrl),
                    apiKey = unmaskedOr(agent?.get("apiKey")?.jsonPrimitive?.content, current.apiKey),
                    model = unmaskedOr(agent?.get("model")?.jsonPrimitive?.content, current.model),
                    temperature = agent?.get("temperature")?.jsonPrimitive?.content?.toFloatOrNull() ?: current.temperature,
                    maxTokens = agent?.get("maxTokens")?.jsonPrimitive?.content?.toIntOrNull() ?: current.maxTokens,
                    sttBaseUrl = unmaskedOr(stt?.get("baseUrl")?.jsonPrimitive?.content, current.sttBaseUrl),
                    sttApiKey = unmaskedOr(stt?.get("apiKey")?.jsonPrimitive?.content, current.sttApiKey),
                    sttModel = unmaskedOr(stt?.get("model")?.jsonPrimitive?.content, current.sttModel),
                    vlmBaseUrl = unmaskedOr(vlm?.get("baseUrl")?.jsonPrimitive?.content, current.vlmBaseUrl),
                    vlmApiKey = unmaskedOr(vlm?.get("apiKey")?.jsonPrimitive?.content, current.vlmApiKey),
                    vlmModel = unmaskedOr(vlm?.get("model")?.jsonPrimitive?.content, current.vlmModel),
                )
            )
            // Restore channels
            val ch = root["channels"]?.jsonObject
            if (ch != null) {
                val currentCh = repo.channelConfig.first()
                repo.setChannelConfig(
                    ChannelConfig(
                        feishuAppId = unmaskedOr(ch["feishuAppId"]?.jsonPrimitive?.content, currentCh.feishuAppId),
                        feishuAppSecret = unmaskedOr(ch["feishuAppSecret"]?.jsonPrimitive?.content, currentCh.feishuAppSecret),
                        feishuWebhook = unmaskedOr(ch["feishuWebhook"]?.jsonPrimitive?.content, currentCh.feishuWebhook),
                        discordWebhook = unmaskedOr(ch["discordWebhook"]?.jsonPrimitive?.content, currentCh.discordWebhook),
                    )
                )
            }
            // Restore UI prefs
            val ui = root["ui"]?.jsonObject
            if (ui != null) {
                val currentUi = repo.uiPrefs.first()
                repo.setUiPrefs(
                    UiPrefs(
                        darkMode = ui["darkMode"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: currentUi.darkMode,
                        monoFont = ui["monoFont"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: currentUi.monoFont,
                        showToolCalls = ui["showToolCalls"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: currentUi.showToolCalls,
                        showThoughts = ui["showThoughts"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: currentUi.showThoughts,
                        showTokens = ui["showTokens"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: currentUi.showTokens,
                    )
                )
            }
            true
        }.getOrDefault(false)
    }
}

/**
 * Mask a secret for export — keeps first 4 + last 4 characters, replaces
 * the middle with asterisks. Returns empty string if input is blank.
 * Examples:
 *   "sk-abc123xyz789" -> "sk-a*****z789"
 *   "short"           -> "*****"
 *   ""                -> ""
 */
private fun maskSecret(s: String): String {
    if (s.length <= 8) return if (s.isBlank()) "" else "*****"
    val first = s.take(4)
    val last = s.takeLast(4)
    val stars = "*".repeat((s.length - 8).coerceAtMost(20))
    return "$first$stars$last"
}

/**
 * Returns [imported] if it's a real (non-masked) value, otherwise [current].
 * Masked values contain '*' (from [maskSecret]) and must not overwrite the
 * user's real key on import.
 */
private fun unmaskedOr(imported: String?, current: String): String {
    if (imported.isNullOrBlank()) return current
    if (imported.contains('*')) return current
    return imported
}
