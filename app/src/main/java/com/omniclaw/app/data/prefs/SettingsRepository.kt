package com.omniclaw.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "omni_settings")

/**
 * Which LLM backend the agent loop talks to.
 *
 *   - OPENAI_COMPAT: any OpenAI-compatible endpoint (GLM, OpenAI, Anthropic,
 *                    Moonshot, Ollama, vLLM, LM Studio). Uses [LlmClient].
 *   - GEMINI:        Google Gemini via the native REST API. Uses [GeminiClient]
 *                    with `x-goog-api-key` auth — bypasses the OpenAI shim.
 *   - LITERT:        On-device inference via LiteRT. Uses [LocalLlmClient]
 *                    with a bundled .tflite model — no network required.
 */
enum class LlmProvider {
    OPENAI_COMPAT,
    GEMINI,
    LITERT;

    companion object {
        fun fromString(s: String?): LlmProvider = when (s?.lowercase()?.trim()) {
            "gemini" -> GEMINI
            "litert", "local", "on-device" -> LITERT
            else -> OPENAI_COMPAT
        }
    }
}

/**
 * Agent LLM configuration. The original X-OmniClaw supports separate providers
 * for Agent / STT / VLM; we model all three here. Each can point to a different
 * OpenAI-compatible endpoint.
 *
 * The [provider] field selects which backend the agent loop uses. When GEMINI,
 * the [apiKey] is the Google AI Studio key (sent as x-goog-api-key). When
 * LITERT, [model] is "local-<family>:<path>" and [apiKey] is unused.
 */
data class ModelConfig(
    // Provider routing — OPENAI_COMPAT (default), GEMINI, or LITERT
    val provider: LlmProvider = LlmProvider.OPENAI_COMPAT,
    // Agent model — primary reasoning LLM
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val apiKey: String = "",
    val model: String = "glm-4.6",
    val temperature: Float = 0.2f,
    val maxTokens: Int = 2048,
    // Speech-to-text (STT) — e.g. SiliconFlow SenseVoice Small
    val sttBaseUrl: String = "https://api.siliconflow.cn/v1/audio/transcriptions",
    val sttApiKey: String = "",
    val sttModel: String = "FunAudioLLM/SenseVoiceSmall",
    // Vision LLM (VLM) — for screenshot / frame understanding
    val vlmBaseUrl: String = "https://openrouter.ai/api/v1",
    val vlmApiKey: String = "",
    val vlmModel: String = "qwen/qwen3.6-flash",
)

/**
 * Built-in provider presets — mirrors the original X-OmniClaw provider table.
 * Used by the Settings screen to let the user pick a provider and auto-fill
 * the base URL + example model ID.
 */
data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val exampleModel: String,
)

/** Latest Gemini models available via the Google AI Studio REST API. */
val GeminiModels: List<String> = listOf(
    // ── Gemini 3.x ──
    "gemini-3.5-flash",
    "gemini-3.1-pro-preview",
    "gemini-3-flash-preview",
    "gemini-3.1-flash-lite",
    // ── Gemini 2.5 ──
    "gemini-2.5-flash",
    "gemini-2.5-flash-lite",
    "gemini-2.5-pro",
)

val ProviderPresets: List<ProviderPreset> = listOf(
    ProviderPreset("openrouter",  "OpenRouter",  "https://openrouter.ai/api/v1",          "qwen/qwen3.6-flash"),
    ProviderPreset("anthropic",   "Anthropic",   "https://api.anthropic.com/v1",          "claude-opus-4"),
    ProviderPreset("openai",      "OpenAI",      "https://api.openai.com/v1",             "gpt-4.1"),
    ProviderPreset("moonshot",    "Moonshot",    "https://api.moonshot.cn/v1",            "kimi-k2.5"),
    ProviderPreset("minimax",     "MiniMax",     "https://api.minimax.chat/v1",           "MiniMax-M2.5"),
    ProviderPreset("ollama",      "Ollama",      "http://localhost:11434/v1",             "llama3.1:8b"),
    ProviderPreset("nvidia",      "NVIDIA",      "https://integrate.api.nvidia.com/v1",   "meta/llama-3.1-70b-instruct"),
    ProviderPreset("glm",         "GLM / Zhipu", "https://open.bigmodel.cn/api/paas/v4",  "glm-4.6"),
    // Gemini preset — uses the native GeminiClient (not the OpenAI shim).
    // Selecting this sets provider=GEMINI + the v1beta REST base URL.
    ProviderPreset("gemini",      "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"),
    // LiteRT preset — on-device inference via LiteRT. Selecting this sets
    // provider=LITERT + a model spec pointing at assets/models/.
    ProviderPreset("litert",      "LiteRT (local)", "on-device", "local-gemma:models/gemma-2b.tflite"),
)

data class ChannelConfig(
    val feishuAppId: String = "",
    val feishuAppSecret: String = "",
    val feishuWebhook: String = "",
    val discordWebhook: String = "",
)

data class UiPrefs(
    val darkMode: Boolean = false,
    val monoFont: Boolean = true,
    val showToolCalls: Boolean = true,
    val showThoughts: Boolean = false,
    val showTokens: Boolean = true,
    val ttsEnabled: Boolean = false,
)

data class PermissionsState(
    val accessibility: Boolean = false,
    val overlay: Boolean = false,
    val screenCapture: Boolean = false,
    val camera: Boolean = false,
    val mic: Boolean = false,
    val media: Boolean = false,
    val notifications: Boolean = false,
)

interface SettingsRepository {
    val modelConfig: Flow<ModelConfig>
    val channelConfig: Flow<ChannelConfig>
    val uiPrefs: Flow<UiPrefs>
    val permissions: Flow<PermissionsState>
    /** Emits true when the user has accepted the cloud LLM privacy disclosure, false if not accepted, or null while loading. */
    val privacyAccepted: Flow<Boolean?>
    /** FIX #8: Expose SecureStorage state so the UI can warn about cleartext fallback. */
    val secureStorageState: Flow<SecureStorage.StorageState>

    suspend fun setModelConfig(cfg: ModelConfig)
    
    fun getApiKeyForProvider(provider: LlmProvider, baseUrl: String): String
    suspend fun setChannelConfig(cfg: ChannelConfig)
    suspend fun setUiPrefs(prefs: UiPrefs)
    suspend fun setPermissions(state: PermissionsState)
    /** Mark the privacy disclosure as accepted by the user. */
    suspend fun acceptPrivacyDisclosure()
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val secure: SecureStorage,
) : SettingsRepository {

    companion object {
        private const val TAG = "SettingsRepository"
    }

    private object Keys {
        // Agent model
        val provider = stringPreferencesKey("llm.provider")
        val baseUrl = stringPreferencesKey("llm.base_url")
        val apiKey = stringPreferencesKey("llm.api_key")
        val model = stringPreferencesKey("llm.model")
        val temp = stringPreferencesKey("llm.temperature")
        val maxTokens = intPreferencesKey("llm.max_tokens")

        // STT (speech-to-text)
        val sttBaseUrl = stringPreferencesKey("stt.base_url")
        val sttApiKey = stringPreferencesKey("stt.api_key")
        val sttModel = stringPreferencesKey("stt.model")

        // VLM (vision LLM)
        val vlmBaseUrl = stringPreferencesKey("vlm.base_url")
        val vlmApiKey = stringPreferencesKey("vlm.api_key")
        val vlmModel = stringPreferencesKey("vlm.model")

        // Channel
        val feishuAppId = stringPreferencesKey("ch.feishu_app_id")
        val feishuAppSecret = stringPreferencesKey("ch.feishu_app_secret")
        val feishuWebhook = stringPreferencesKey("ch.feishu_webhook")
        val discordWebhook = stringPreferencesKey("ch.discord_webhook")

        // UI
        val darkMode = booleanPreferencesKey("ui.dark_mode")
        val monoFont = booleanPreferencesKey("ui.mono_font")
        val showToolCalls = booleanPreferencesKey("ui.show_tool_calls")
        val showThoughts = booleanPreferencesKey("ui.show_thoughts")
        val showTokens = booleanPreferencesKey("ui.show_tokens")
        val ttsEnabled = booleanPreferencesKey("ui.tts_enabled")

        // Privacy disclosure acceptance flag
        val privacyAccepted = booleanPreferencesKey("privacy.cloud_llm_accepted")

        // Permissions (cached; the source of truth is the system)
        val permAccessibility = booleanPreferencesKey("perm.accessibility")
        val permOverlay = booleanPreferencesKey("perm.overlay")
        val permScreenCapture = booleanPreferencesKey("perm.screen_capture")
        val permCamera = booleanPreferencesKey("perm.camera")
        val permMic = booleanPreferencesKey("perm.mic")
        val permMedia = booleanPreferencesKey("perm.media")
        val permAllFiles = booleanPreferencesKey("perm.all_files")
        val permNotifications = booleanPreferencesKey("perm.notifications")
    }

    // FIX #8: Expose SecureStorage state via SettingsRepository so the UI can
    // display a warning when secrets are stored in cleartext (DEBUG FALLBACK)
    // or when SecureStorage has failed entirely (RELEASE FAILED). This lets the
    // Settings screen banner alert users that their API keys may not be protected.
    override val secureStorageState: Flow<SecureStorage.StorageState> =
        kotlinx.coroutines.flow.flow { emit(secure.state) }

    // Secrets (API keys, webhooks) are read from SecureStorage (encrypted at
    // rest via Tink + Android Keystore). Non-secret fields come from DataStore.
    override val modelConfig: Flow<ModelConfig> = ctx.dataStore.data.map { p ->
        val provider = LlmProvider.fromString(p[Keys.provider])
        val storedBaseUrl = p[Keys.baseUrl] ?: "https://open.bigmodel.cn/api/paas/v4"
        val key = when (provider) {
            LlmProvider.GEMINI -> secure.getSecret(SecureStorage.KEY_GEMINI_API_KEY)
            LlmProvider.OPENAI_COMPAT -> {
                // Determine the provider ID to use as a key suffix by comparing trimmed URLs
                val trimmedStored = storedBaseUrl.trimEnd('/')
                val presetMatch = ProviderPresets.find { it.baseUrl.trimEnd('/') == trimmedStored }
                val providerId = presetMatch?.id ?: "custom"
                secure.getSecret("agent.api_key.$providerId")
            }
            LlmProvider.LITERT -> ""
        }
        ModelConfig(
            provider = provider,
            baseUrl = storedBaseUrl,
            apiKey = key,
            model = p[Keys.model] ?: "glm-4.6",
            temperature = (p[Keys.temp] ?: "0.2").toFloatOrNull() ?: 0.2f,
            maxTokens = p[Keys.maxTokens] ?: 2048,
            sttBaseUrl = p[Keys.sttBaseUrl] ?: "https://api.siliconflow.cn/v1/audio/transcriptions",
            sttApiKey = secure.getSecret(SecureStorage.KEY_STT_API_KEY),
            sttModel = p[Keys.sttModel] ?: "FunAudioLLM/SenseVoiceSmall",
            vlmBaseUrl = p[Keys.vlmBaseUrl] ?: "https://openrouter.ai/api/v1",
            vlmApiKey = secure.getSecret(SecureStorage.KEY_VLM_API_KEY),
            vlmModel = p[Keys.vlmModel] ?: "qwen/qwen3.6-flash",
        )
    }.flowOn(Dispatchers.IO)

    override val channelConfig: Flow<ChannelConfig> = ctx.dataStore.data.map { p ->
        ChannelConfig(
            feishuAppId = secure.getSecret(SecureStorage.KEY_FEISHU_APP_ID).ifBlank { p[Keys.feishuAppId].orEmpty() },
            feishuAppSecret = secure.getSecret(SecureStorage.KEY_FEISHU_APP_SECRET),
            feishuWebhook = p[Keys.feishuWebhook].orEmpty(),
            discordWebhook = secure.getSecret(SecureStorage.KEY_DISCORD_WEBHOOK),
        )
    }.flowOn(Dispatchers.IO)

    override val uiPrefs: Flow<UiPrefs> = ctx.dataStore.data.map { p ->
        val systemDark = ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        UiPrefs(
            darkMode = p[Keys.darkMode] ?: systemDark,
            monoFont = p[Keys.monoFont] ?: true,
            showToolCalls = p[Keys.showToolCalls] ?: true,
            showThoughts = p[Keys.showThoughts] ?: false,
            showTokens = p[Keys.showTokens] ?: true,
            ttsEnabled = p[Keys.ttsEnabled] ?: false,
        )
    }.flowOn(Dispatchers.IO)

    override val privacyAccepted: Flow<Boolean?> = ctx.dataStore.data.map { p ->
        p[Keys.privacyAccepted]
    }.flowOn(Dispatchers.IO)

    override val permissions: Flow<PermissionsState> = ctx.dataStore.data.map { p ->
        PermissionsState(
            accessibility = p[Keys.permAccessibility] ?: false,
            overlay = p[Keys.permOverlay] ?: false,
            screenCapture = p[Keys.permScreenCapture] ?: false,
            camera = p[Keys.permCamera] ?: false,
            mic = p[Keys.permMic] ?: false,
            media = p[Keys.permMedia] ?: false,
            notifications = p[Keys.permNotifications] ?: false,
        )
    }.flowOn(Dispatchers.IO)

    override fun getApiKeyForProvider(provider: LlmProvider, baseUrl: String): String {
        return when (provider) {
            LlmProvider.GEMINI -> secure.getSecret(SecureStorage.KEY_GEMINI_API_KEY)
            LlmProvider.OPENAI_COMPAT -> {
                val trimmedBaseUrl = baseUrl.trimEnd('/')
                val presetMatch = ProviderPresets.find { it.baseUrl.trimEnd('/') == trimmedBaseUrl }
                val providerId = presetMatch?.id ?: "custom"
                secure.getSecret("agent.api_key.$providerId")
            }
            LlmProvider.LITERT -> ""
        }
    }

    override suspend fun setModelConfig(cfg: ModelConfig) {
        // Secrets go to SecureStorage (encrypted at rest).
        when (cfg.provider) {
            LlmProvider.GEMINI -> secure.setSecret(SecureStorage.KEY_GEMINI_API_KEY, cfg.apiKey)
            LlmProvider.OPENAI_COMPAT -> {
                val storedBaseUrl = cfg.baseUrl.trimEnd('/')
                val presetMatch = ProviderPresets.find { it.baseUrl.trimEnd('/') == storedBaseUrl }
                val providerId = presetMatch?.id ?: "custom"
                secure.setSecret("agent.api_key.$providerId", cfg.apiKey)
            }
            LlmProvider.LITERT -> {} // No API key for local models
        }
        secure.setSecret(SecureStorage.KEY_STT_API_KEY, cfg.sttApiKey)
        secure.setSecret(SecureStorage.KEY_VLM_API_KEY, cfg.vlmApiKey)
        // Non-secret fields go to DataStore.
        ctx.dataStore.edit { p ->
            p[Keys.provider] = cfg.provider.name.lowercase()
            p[Keys.baseUrl] = cfg.baseUrl
            p[Keys.model] = cfg.model
            p[Keys.temp] = cfg.temperature.toString()
            p[Keys.maxTokens] = cfg.maxTokens
            p[Keys.sttBaseUrl] = cfg.sttBaseUrl
            p[Keys.sttModel] = cfg.sttModel
            p[Keys.vlmBaseUrl] = cfg.vlmBaseUrl
            p[Keys.vlmModel] = cfg.vlmModel
        }
    }

    override suspend fun setChannelConfig(cfg: ChannelConfig) {
        secure.setSecret(SecureStorage.KEY_FEISHU_APP_ID, cfg.feishuAppId)
        secure.setSecret(SecureStorage.KEY_FEISHU_APP_SECRET, cfg.feishuAppSecret)
        secure.setSecret(SecureStorage.KEY_DISCORD_WEBHOOK, cfg.discordWebhook)
        ctx.dataStore.edit { p ->
            p[Keys.feishuWebhook] = cfg.feishuWebhook
        }
    }

    override suspend fun setUiPrefs(prefs: UiPrefs) {
        ctx.dataStore.edit { p ->
            p[Keys.darkMode] = prefs.darkMode
            p[Keys.monoFont] = prefs.monoFont
            p[Keys.showToolCalls] = prefs.showToolCalls
            p[Keys.showThoughts] = prefs.showThoughts
            p[Keys.showTokens] = prefs.showTokens
            p[Keys.ttsEnabled] = prefs.ttsEnabled
        }
    }

    override suspend fun setPermissions(state: PermissionsState) {
        ctx.dataStore.edit { p ->
            p[Keys.permAccessibility] = state.accessibility
            p[Keys.permOverlay] = state.overlay
            p[Keys.permScreenCapture] = state.screenCapture
            p[Keys.permCamera] = state.camera
            p[Keys.permMic] = state.mic
            p[Keys.permMedia] = state.media
            p[Keys.permNotifications] = state.notifications
        }
    }

    override suspend fun acceptPrivacyDisclosure() {
        ctx.dataStore.edit { p ->
            p[Keys.privacyAccepted] = true
        }
    }
}
