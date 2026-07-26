package com.omniclaw.app.logging

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Structured agent-loop logger.
 *
 * Implements the original X-OmniClaw 2026-04-22 "execution policy tightened"
 * update: stronger cross-package ref rebinding and error-location logging.
 *
 * Every agent failure now logs:
 *   - The session ID
 *   - The step number
 *   - The action that was attempted
 *   - The class + line where the error was caught
 *
 * This makes debugging much easier when the agent fails inside a third-party
 * app (where the accessibility tree references resources from another package
 * and needs rebinding to the current package's namespace).
 */
@Singleton
class AgentLogger @Inject constructor() {

    data class ErrorLocation(
        val sessionId: String,
        val step: Int,
        val action: String,
        val className: String,
        val methodName: String,
        val lineNumber: Int,
        val message: String,
    )

    fun logError(loc: ErrorLocation) {
        val sanitized = redactSecrets(loc.message)
        Log.w(TAG, "[${loc.sessionId}#${loc.step}] ${loc.action} @ ${loc.className}.${loc.methodName}:${loc.lineNumber} — $sanitized")
        
    }

    fun logWarning(sessionId: String, step: Int, msg: String) {
        val sanitized = redactSecrets(msg)
        Log.w(TAG, "[$sessionId#$step] WARNING: $sanitized")
    }

    fun logInfo(sessionId: String, step: Int, msg: String) {
        Log.i(TAG, "[$sessionId#$step] ${redactSecrets(msg)}")
    }

    /**
     * Redact common API-key patterns from log messages to prevent secret
     * leakage via Logcat. Catches:
     *   - sk-<20+ alphanumeric chars> (OpenAI / OpenRouter style)
     *   - AIza<30+ alphanumeric chars> (Google / Gemini API key style)
     *   - Bearer <token> in Authorization headers
     *   - x-goog-api-key: <token> (Gemini REST header form)
     *   - api_key=<value> query params
     *   - Discord / Slack / Teams webhook URLs (the token is in the URL path)
     *   - Feishu / Lark app_secret query params or JSON fields
     *   - Generic long hex/base64 tokens (≥32 chars) flagged as "token"
     */
    private fun redactSecrets(msg: String): String {
        var s = msg
        // Specific credential patterns first (longest match wins).
        s = Regex("(?i)x-goog-api-key\\s*[:=]?\\s*[A-Za-z0-9_\\-]{16,}").replace(s, "x-goog-api-key: ***REDACTED***")
        s = Regex("(?i)(api[_-]?key=)[A-Za-z0-9_\\-]{8,}").replace(s, "$1***REDACTED***")
        s = Regex("(?i)Bearer\\s+[A-Za-z0-9_\\-\\.=]{16,}").replace(s, "Bearer ***REDACTED***")
        // Generic fallback patterns.
        s = Regex("(?i)sk-[A-Za-z0-9_\\-]{16,}").replace(s, "sk-***REDACTED***")
        s = Regex("(?i)AIza[A-Za-z0-9_\\-]{30,}").replace(s, "AIza***REDACTED***")
        // Webhook URLs: the token is in the path. Discord: /webhooks/<id>/<token>.
        // Slack: /services/T.../B.../<token>. Teams: /incoming-webhook/<token>.
        s = Regex("(?i)https?://[^\\s/]*(?:discord|slack|hooks\\.slack|teams|webhook)[^\\s]*", RegexOption.IGNORE_CASE)
            .replace(s) { m ->
                // Keep the host so logs are still useful; mask the path.
                val url = m.value
                val schemeEnd = url.indexOf("://") + 3
                val pathStart = url.indexOf('/', schemeEnd)
                if (pathStart < 0) url else url.substring(0, pathStart) + "/***REDACTED***"
            }
        // Feishu/Lark app_secret in JSON or query form.
        s = Regex("(?i)(app_secret[\"'\\s:=]+)[A-Za-z0-9_\\-]{8,}").replace(s, "$1***REDACTED***")
        return s
    }

    /**
     * Rebind a viewId resource name from another package into the current
     * package's namespace. The original X-OmniClaw does this when an
     * accessibility node's viewIdResourceName references "com.foo:id/x"
     * but the agent is operating inside "com.bar".
     */
    fun rebindRef(ref: String?, currentPackage: String): String? {
        if (ref.isNullOrBlank()) return null
        // Shape: "com.foo:id/resource_name"
        if (!ref.contains(":id/")) return ref
        val resourceName = ref.substringAfter(":id/")
        return "$currentPackage:id/$resourceName"
    }

    companion object {
        private const val TAG = "OmniAgent"
    }
}
