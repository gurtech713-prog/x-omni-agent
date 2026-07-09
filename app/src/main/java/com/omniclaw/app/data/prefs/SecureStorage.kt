package com.omniclaw.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps EncryptedSharedPreferences (Tink-backed AES-256) for storing API keys
 * and other secrets. Non-secret settings stay in DataStore for structured
 * access via Flow.
 *
 * On rooted devices or via `adb backup`, EncryptedSharedPreferences are still
 * encrypted at rest — the Tink master key is itself wrapped by the Android
 * Keystore, which is hardware-backed on devices with TEE/StrongBox.
 */
@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            ctx,
            "omni_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getSecret(key: String): String = prefs.getString(key, "").orEmpty()

    fun setSecret(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun hasSecret(key: String): Boolean = prefs.contains(key)

    fun removeSecret(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        const val KEY_AGENT_API_KEY = "agent.api_key"
        const val KEY_STT_API_KEY = "stt.api_key"
        const val KEY_VLM_API_KEY = "vlm.api_key"
        // Gemini-specific key (sent as x-goog-api-key). Stored separately so the
        // user can keep both an OpenAI-compat key and a Gemini key configured,
        // and switch providers without re-entering credentials.
        const val KEY_GEMINI_API_KEY = "gemini.api_key"
        const val KEY_FEISHU_APP_SECRET = "feishu.app_secret"
        const val KEY_FEISHU_APP_ID = "feishu.app_id"
        const val KEY_DISCORD_WEBHOOK = "discord.webhook"
    }
}
