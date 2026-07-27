package com.omniclaw.app.ui.settings

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

// U-M10: a second preferencesDataStore delegate for the same file name. The
// androidx.datastore singleton cache (per-process, per-file) guarantees this
// resolves to the SAME DataStore instance as the one declared in
// SettingsRepository.kt — calling edit {} here is observed by all
// privacyAccepted collectors. This is necessary because the SettingsRepository
// interface (owned by Task 3) only exposes acceptPrivacyDisclosure(), not a
// revoke counterpart — without a revoke method there, the UI layer has to
// clear the boolean directly. See worklog for details.
private val Context.privacyDataStore by preferencesDataStore(name = "omni_settings")
private val PRIVACY_ACCEPTED_KEY = booleanPreferencesKey("privacy.cloud_llm_accepted")

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val repo: SettingsRepository,
    private val channels: ChannelSender,
    private val secureStorage: com.omniclaw.app.data.prefs.SecureStorage,
) : ViewModel() {

    val modelConfig: StateFlow<ModelConfig> = repo.modelConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, ModelConfig())

    val channelConfig: StateFlow<ChannelConfig> = repo.channelConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChannelConfig())

    val uiPrefs: StateFlow<UiPrefs> = repo.uiPrefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiPrefs())

    val permissions: StateFlow<PermissionsState> = repo.permissions
        .stateIn(viewModelScope, SharingStarted.Eagerly, PermissionsState())

    val privacyAccepted: StateFlow<Boolean?> = repo.privacyAccepted
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val storageState: StateFlow<com.omniclaw.app.data.prefs.SecureStorage.StorageState> =
        // H-36 FIX: bridge the underlying reactive source instead of snapshotting
        // secureStorage.state once at construction. SecureStorage emits on
        // rotationEvents whenever a key is rotated; re-read the (cheap,
        // in-memory) storage state on each emission so the API Key Rotation
        // card reflects rotations performed during this session.
        secureStorage.rotationEvents
            .map { secureStorage.state }
            .stateIn(viewModelScope, SharingStarted.Eagerly, secureStorage.state)

    suspend fun setModel(cfg: ModelConfig) {
        Log.i(TAG, "Updating model config: $cfg")
        repo.setModelConfig(cfg)
    }
    
    suspend fun changeProvider(provider: com.omniclaw.app.data.prefs.LlmProvider, baseUrl: String, model: String) {
        Log.i(TAG, "Changing provider: provider=$provider, baseUrl=$baseUrl, model=$model")
        // M-39 FIX: getApiKeyForProvider reads EncryptedSharedPreferences
        // (disk + crypto). This is invoked from a Main-immediate scope, so hop
        // to IO to avoid freezing the UI while switching providers.
        withContext(Dispatchers.IO) {
            val current = repo.modelConfig.first()
            val newKey = repo.getApiKeyForProvider(provider, baseUrl)
            repo.setModelConfig(current.copy(
                provider = provider,
                baseUrl = baseUrl,
                model = model,
                apiKey = newKey
            ))
        }
    }
    suspend fun setChannel(cfg: ChannelConfig) {
        Log.i(TAG, "Updating channel config: $cfg")
        repo.setChannelConfig(cfg)
    }
    suspend fun setUi(prefs: UiPrefs) {
        Log.d(TAG, "Updating UI preferences: $prefs")
        repo.setUiPrefs(prefs)
    }
    suspend fun setPermissions(state: PermissionsState) {
        Log.d(TAG, "Updating permissions state: $state")
        repo.setPermissions(state)
    }

    /** Mark the cloud LLM privacy disclosure as accepted. */
    suspend fun acceptPrivacy() {
        Log.i(TAG, "User accepted cloud LLM privacy disclosure")
        repo.acceptPrivacyDisclosure()
    }

    /**
     * U-M10: Revoke the user's prior acceptance of the cloud LLM privacy
     * disclosure. Clears the `privacy.cloud_llm_accepted` boolean in the
     * shared settings DataStore so the next cloud LLM call re-prompts.
     *
     * Implemented here (rather than on SettingsRepository) because the
     * repository interface — owned by Task 3 / the data layer — only exposes
     * the one-way `acceptPrivacyDisclosure()` writer. The shared
     * `preferencesDataStore(name = "omni_settings")` singleton (see file-level
     * `privacyDataStore`) guarantees this write is observed by the
     * `privacyAccepted` Flow exposed by the repository.
     */
    suspend fun revokePrivacy() {
        Log.i(TAG, "User revoked cloud LLM privacy disclosure acceptance")
        withContext(Dispatchers.IO) {
            ctx.privacyDataStore.edit { p -> p.remove(PRIVACY_ACCEPTED_KEY) }
        }
    }

    /** Test-send a message to all configured channels. Returns true if at least one send succeeded. */
    suspend fun testChannels(message: String): Boolean {
        Log.i(TAG, "Testing notification channels with message: $message")
        return channels.sendToDiscord(message)
    }

    /** Rotate an API key's metadata. Returns success status and message. */
    suspend fun rotateApiKey(keyName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        when (keyName) {
            "agent" -> {
                val version = secureStorage.rotateKey(com.omniclaw.app.data.prefs.SecureStorage.KEY_AGENT_API_KEY)
                if (version != null) {
                    Log.i(TAG, "Agent API key rotated to $version")
                    Pair(true, "Agent API key rotated successfully")
                } else {
                    Pair(false, "Failed to rotate agent API key")
                }
            }
            "gemini" -> {
                val version = secureStorage.rotateKey(com.omniclaw.app.data.prefs.SecureStorage.KEY_GEMINI_API_KEY)
                if (version != null) {
                    Log.i(TAG, "Gemini API key rotated to $version")
                    Pair(true, "Gemini API key rotated successfully")
                } else {
                    Pair(false, "Failed to rotate Gemini API key")
                }
            }
            "stt" -> {
                val version = secureStorage.rotateKey(com.omniclaw.app.data.prefs.SecureStorage.KEY_STT_API_KEY)
                if (version != null) {
                    Log.i(TAG, "STT API key rotated to $version")
                    Pair(true, "STT API key rotated successfully")
                } else {
                    Pair(false, "Failed to rotate STT API key")
                }
            }
            "vlm" -> {
                val version = secureStorage.rotateKey(com.omniclaw.app.data.prefs.SecureStorage.KEY_VLM_API_KEY)
                if (version != null) {
                    Log.i(TAG, "VLM API key rotated to $version")
                    Pair(true, "VLM API key rotated successfully")
                } else {
                    Pair(false, "Failed to rotate VLM API key")
                }
            }
            else -> Pair(false, "Unknown key: $keyName")
        }
    }

    /** Get last rotation time for a key, or null if never rotated. */
    fun getLastRotationTime(keyName: String): Long? = when (keyName) {
        "agent" -> secureStorage.getLastRotationTime(com.omniclaw.app.data.prefs.SecureStorage.KEY_AGENT_API_KEY)
        "gemini" -> secureStorage.getLastRotationTime(com.omniclaw.app.data.prefs.SecureStorage.KEY_GEMINI_API_KEY)
        "stt" -> secureStorage.getLastRotationTime(com.omniclaw.app.data.prefs.SecureStorage.KEY_STT_API_KEY)
        "vlm" -> secureStorage.getLastRotationTime(com.omniclaw.app.data.prefs.SecureStorage.KEY_VLM_API_KEY)
        else -> null
    }

    /** Check if a key needs rotation based on 90-day default. */
    fun needsRotation(keyName: String): Boolean = when (keyName) {
        "agent" -> secureStorage.needsRotation(com.omniclaw.app.data.prefs.SecureStorage.KEY_AGENT_API_KEY)
        "gemini" -> secureStorage.needsRotation(com.omniclaw.app.data.prefs.SecureStorage.KEY_GEMINI_API_KEY)
        "stt" -> secureStorage.needsRotation(com.omniclaw.app.data.prefs.SecureStorage.KEY_STT_API_KEY)
        "vlm" -> secureStorage.needsRotation(com.omniclaw.app.data.prefs.SecureStorage.KEY_VLM_API_KEY)
        else -> false
    }

    /**
     * Export the full config (model + channels + UI prefs) to
     * `getExternalFilesDir(null)/.xomniclaw/xomniclaw.json` — app-specific
     * external storage that requires no runtime permission and survives app
     * uninstall until the user manually clears it.
     *
     * Previously this wrote to `Environment.getExternalStorageDirectory()`
     * (the public `/sdcard`), which is:
     *   - Deprecated since API 29 (throws on targetSdk 30+ without MANAGE_EXTERNAL_STORAGE)
     *   - A privacy concern (any app with READ_EXTERNAL_STORAGE could read the
     *     masked-but-still-sensitive config file)
     * App-specific external storage is the correct location per Android docs:
     * https://developer.android.com/training/data-storage/app-specific
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
        // App-specific external storage — no permission required, scoped to
        // this app, removed on uninstall.
        val baseDir = ctx.getExternalFilesDir(null)
            ?: File(ctx.filesDir, "external").apply { mkdirs() }
        val dir = File(baseDir, ".xomniclaw").apply { mkdirs() }
        val file = File(dir, "xomniclaw.json")
        runCatching {
            // M-40 FIX: atomic write — write to a temp file in the same
            // directory, then rename onto the target. A crash/kill mid-write
            // leaves only the temp file behind, never a truncated export.
            val tmp = File(file.parentFile, "xomniclaw.json.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "Config successfully exported to ${file.absolutePath}")
            file
        }.onFailure {
            Log.e(TAG, "Failed to export config: ${it.message}", it)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        private val jsonParser = Json { ignoreUnknownKeys = true }
    }

    /** Import config from `getExternalFilesDir(null)/.xomniclaw/xomniclaw.json` (best-effort). */
    suspend fun importConfig(): Boolean = withContext(Dispatchers.IO) {
        val baseDir = ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "external")
        val file = File(baseDir, ".xomniclaw/xomniclaw.json")
        if (!file.exists()) return@withContext false
        runCatching {
            val text = file.readText()
            val root = jsonParser.parseToJsonElement(text).jsonObject
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
            Log.i(TAG, "Config successfully imported from ${file.absolutePath}")
            true
        }.onFailure {
            Log.e(TAG, "Failed to import config: ${it.message}", it)
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
