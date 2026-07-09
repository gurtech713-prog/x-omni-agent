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
     */
    private fun redactSecrets(msg: String): String {
        var s = msg
        s = Regex("(?i)sk-[A-Za-z0-9_\\-]{16,}").replace(s, "sk-***REDACTED***")
        s = Regex("(?i)AIza[A-Za-z0-9_\\-]{30,}").replace(s, "AIza***REDACTED***")
        s = Regex("(?i)Bearer\\s+[A-Za-z0-9_\\-\\.=]{16,}").replace(s, "Bearer ***REDACTED***")
        s = Regex("(?i)x-goog-api-key\\s*:?\\s*[A-Za-z0-9_\\-]{16,}").replace(s, "x-goog-api-key: ***REDACTED***")
        s = Regex("(?i)(api[_-]?key=)[A-Za-z0-9_\\-]{8,}").replace(s, "$1***REDACTED***")
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
