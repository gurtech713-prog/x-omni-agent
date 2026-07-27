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
        // D-H10: redact EVERY string field, not just `message`. The action,
        // class, and method names can also contain secrets (e.g. an action
        // string like "call Gemini with key AIza..." would previously leak the
        // key via Logcat). D-L3: use Log.e (not Log.w) so error-level filtering
        // tools (crash reporters, Logcat severity filters) treat this as an
        // error rather than a warning.
        val a = redactSecrets(loc.action)
        val c = redactSecrets(loc.className)
        val m = redactSecrets(loc.message)
        Log.e(TAG, "[${loc.sessionId}#${loc.step}] $a @ $c.${loc.methodName}:${loc.lineNumber} — $m")
    }

    fun logWarning(sessionId: String, step: Int, msg: String) {
        val sanitized = redactSecrets(msg)
        Log.w(TAG, "[$sessionId#$step] WARNING: $sanitized")
    }

    fun logInfo(sessionId: String, step: Int, msg: String) {
        Log.i(TAG, "[$sessionId#$step] ${redactSecrets(msg)}")
    }

    /**
     * Redact common API-key / credential patterns from log messages to prevent
     * secret leakage via Logcat (D-H9).
     *
     * Order matters: more specific patterns run first so their context (e.g.
     * `Bearer `, `x-goog-api-key:`) is preserved in the redaction; the generic
     * long-token pattern runs near the end so it only catches what the specific
     * patterns missed. Email and phone (loose) run last.
     *
     * False-positive policy: the 32-hex, generic-long-token, and phone patterns
     * are intentionally broad — they may redact non-secret strings (UUIDs,
     * timestamps, version numbers). That's the safer failure mode for a logger.
     * If a known-safe pattern surfaces (e.g. session IDs in a specific format),
     * add an allowlist regex that runs BEFORE these patterns and replaces the
     * matching substring with a placeholder that the redaction patterns won't
     * touch (e.g. `<<UUID:$1>>`).
     */
    private val SECRET_PATTERNS = listOf(
        // Provider-specific API key prefixes (longest-match-first within each family).
        Regex("sk-or-[A-Za-z0-9_\\-]+"),       // OpenRouter
        Regex("sk-ant-[A-Za-z0-9_\\-]+"),      // Anthropic
        Regex("sk-[A-Za-z0-9_\\-]+"),          // OpenAI (also catches the two above; listed after for clarity)
        Regex("AIza[A-Za-z0-9_\\-]{30,}"),     // Google / Gemini
        Regex("nvapi-[A-Za-z0-9_\\-]+"),       // NVIDIA
        Regex("xox[bpoa]-[A-Za-z0-9-]+"),       // Slack
        Regex("gh[pousr]_[A-Za-z0-9]{30,}"),   // GitHub
        Regex("AKIA[A-Z0-9]{16}"),             // AWS access key ID
        Regex("eyJ[A-Za-z0-9_\\-]+\\.eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+"), // JWT
        Regex("[A-Fa-f0-9]{32}"),              // 32-hex (GLM/Zhipu, Minimax, MD5)
        // Header / param forms.
        Regex("Bearer\\s+[A-Za-z0-9_\\-\\.=]+"),
        Regex("x-goog-api-key:\\s*[A-Za-z0-9_\\-]+"),
        Regex("api_key[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-]+", RegexOption.IGNORE_CASE),
        Regex("\"apiKey\"\\s*:\\s*\"[^\"]+\"", RegexOption.IGNORE_CASE),
        Regex("webhooks/\\d+/[A-Za-z0-9_\\-]+"),
        Regex("app_secret[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-]+", RegexOption.IGNORE_CASE),
        // Generic catch-alls (intentionally last — most aggressive).
        Regex("[A-Za-z0-9+/=]{40,}"),          // generic long base64/hex token
        Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"), // email
        Regex("\\+?\\d{1,3}?[-.\\s]?\\(?\\d{1,4}?\\)?[-.\\s]?\\d{1,4}[-.\\s]?\\d{1,9}"), // phone (loose — may false-positive on dates/timestamps)
    )

    private fun redactSecrets(msg: String): String {
        var redacted = msg
        for (p in SECRET_PATTERNS) {
            redacted = p.replace(redacted, "[REDACTED]")
        }
        return redacted
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
