package com.omniclaw.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.omniclaw.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.TimeUnit
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
 *
 * Fallback policy:
 *  - In DEBUG builds, if EncryptedSharedPreferences fails to initialize
 *    (rooted device, Keystore corruption, TEE failure, app restore on a new
 *    device), we fall back to a plain SharedPreferences so the app remains
 *    usable for development. [state] is exposed so the UI can surface a banner.
 *  - In RELEASE builds, no fallback — secrets cannot be silently downgraded
 *    to cleartext. [state] = [StorageState.FAILED]; callers see empty secrets
 *    and writes are dropped on the floor. The UI is expected to gate
 *    secret-requiring flows with an explanatory message.
 *
 * Key rotation support:
 *  - Tracks last rotation timestamp per key via metadata stored alongside the secret.
 *  - [needsRotation] returns true if the key exceeds [DEFAULT_ROTATION_DAYS].
 *  - [rotateKey] generates a new UUID-based rotation token and updates metadata.
 *  - Rotation does NOT change the actual secret value — it marks the key as
 *    recently rotated so downstream systems (LLM clients, etc.) can invalidate
 *    cached references and re-read from secure storage.
 */
@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    /** Reflects the storage backend that was actually initialized. */
    enum class StorageState { ENCRYPTED, FALLBACK, FAILED }

    private val _state: StorageState
    private val prefs: SharedPreferences

    private val _rotationEvents = MutableStateFlow<String?>(null)
    val rotationEvents: StateFlow<String?> = _rotationEvents.asStateFlow()

    init {
        val (p, s) = initPrefs()
        prefs = p
        _state = s
    }

    val state: StorageState get() = _state

    companion object {
        const val DEFAULT_ROTATION_DAYS = 90L
        private const val META_PREFIX = "meta."
        private const val ROTATED_AT_SUFFIX = ".rotated_at"
        private const val VERSION_SUFFIX = ".version"
        private const val TAG = "SecureStorage"

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

    private fun initPrefs(): Pair<SharedPreferences, StorageState> = try {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "omni_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ) to StorageState.ENCRYPTED
    } catch (e: Exception) {
        android.util.Log.e(TAG, "EncryptedSharedPreferences init failed", e)
        if (BuildConfig.DEBUG) {
            // Debug builds may use a cleartext fallback so devs can still use
            // the app while investigating Keystore issues. Exposed via state=FALLBACK
            // so the UI can warn developers that secrets are unencrypted.
            ctx.getSharedPreferences("omni_secrets_fallback", Context.MODE_PRIVATE) to
                StorageState.FALLBACK
        } else {
            // Release: refuse to silently downgrade. Use an in-memory no-op
            // store so reads return "" and writes are dropped.
            NoOpSharedPreferences to StorageState.FAILED
        }
    }

    fun getSecret(key: String): String {
        if (_state == StorageState.FAILED) return ""
        return runCatching { prefs.getString(key, "").orEmpty() }.getOrElse {
            // Keystore key invalidated (biometric reset, factory reset restore, etc.)
            // Reset the corrupted store so subsequent reads do not keep throwing.
            runCatching { removeSecret(key) }
            runCatching { prefs.edit().clear().commit() }
            android.util.Log.w(TAG, "SecureStorage read failed for key; store reset. Error: ${it.message}")
            ""
        }
    }

    fun setSecret(key: String, value: String) {
        if (_state == StorageState.FAILED) return
        prefs.edit().putString(key, value).apply()
        // Initialize rotation metadata on first write if not present.
        if (!prefs.contains(metadataKey(key, ROTATED_AT_SUFFIX))) {
            prefs.edit()
                .putString(metadataKey(key, ROTATED_AT_SUFFIX), System.currentTimeMillis().toString())
                .putString(metadataKey(key, VERSION_SUFFIX), "1")
                .apply()
        }
    }

    fun hasSecret(key: String): Boolean =
        _state != StorageState.FAILED && prefs.contains(key)

    fun removeSecret(key: String) {
        if (_state == StorageState.FAILED) return
        prefs.edit().remove(key).remove(metadataKey(key, ROTATED_AT_SUFFIX)).remove(metadataKey(key, VERSION_SUFFIX)).apply()
    }

    /**
     * Check whether a secret needs rotation based on its last rotation timestamp.
     * Returns false if the key has never been rotated (metadata missing).
     */
    fun needsRotation(key: String, maxDays: Long = DEFAULT_ROTATION_DAYS): Boolean {
        if (_state == StorageState.FAILED) return false
        val rotatedAtStr = prefs.getString(metadataKey(key, ROTATED_AT_SUFFIX), null) ?: return false
        val rotatedAt = rotatedAtStr.toLongOrNull() ?: return false
        val milliseconds = TimeUnit.DAYS.toMillis(maxDays)
        return (System.currentTimeMillis() - rotatedAt) > milliseconds
    }

    /**
     * Rotate a secret's metadata without changing its value.
     * This invalidates any cached references downstream and signals that the
     * caller should re-read the secret from secure storage.
     *
     * Returns the new version string, or null if rotation failed.
     */
    fun rotateKey(key: String): String? {
        if (_state == StorageState.FAILED) return null
        if (!hasSecret(key)) return null

        val currentVersion = prefs.getString(metadataKey(key, VERSION_SUFFIX), "0")?.toIntOrNull() ?: 0
        val newVersion = currentVersion + 1
        val now = System.currentTimeMillis().toString()

        val success = prefs.edit()
            .putString(metadataKey(key, ROTATED_AT_SUFFIX), now)
            .putString(metadataKey(key, VERSION_SUFFIX), newVersion.toString())
            .commit()

        if (success) {
            _rotationEvents.value = "$key rotated to version $newVersion at $now"
            android.util.Log.i(TAG, "Secret rotated: $key -> v$newVersion")
        }
        return if (success) "v$newVersion" else null
    }

    /** Get the rotation timestamp for a key, or null if not available. */
    fun getLastRotationTime(key: String): Long? {
        val value = prefs.getString(metadataKey(key, ROTATED_AT_SUFFIX), null) ?: return null
        return value.toLongOrNull()
    }

    /** Get the version number for a key. */
    fun getKeyVersion(key: String): Int {
        val value = prefs.getString(metadataKey(key, VERSION_SUFFIX), "0") ?: return 0
        return value.toIntOrNull() ?: 0
    }

    private fun metadataKey(key: String, suffix: String): String = "$META_PREFIX$key$suffix"
}

/** Empty in-memory SharedPreferences — used when SecureStorage fails in release builds. */
private object NoOpSharedPreferences : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()
    override fun getString(k: String?, def: String?): String? = def
    override fun getStringSet(k: String?, def: MutableSet<String>?) = def
    override fun getInt(k: String?, def: Int) = def
    override fun getLong(k: String?, def: Long) = def
    override fun getFloat(k: String?, def: Float) = def
    override fun getBoolean(k: String?, def: Boolean) = def
    override fun contains(k: String?) = false
    override fun edit(): SharedPreferences.Editor = NoOpEditor
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private object NoOpEditor : SharedPreferences.Editor {
        override fun putString(k: String?, v: String?) = this
        override fun putStringSet(k: String?, v: MutableSet<String>?) = this
        override fun putInt(k: String?, v: Int) = this
        override fun putLong(k: String?, v: Long) = this
        override fun putFloat(k: String?, v: Float) = this
        override fun putBoolean(k: String?, v: Boolean) = this
        override fun remove(k: String?) = this
        override fun clear() = this
        override fun commit() = false
        override fun apply() {}
    }
}
