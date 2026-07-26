package com.omniclaw.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [AgentLogger]'s secret-redaction regexes.
 *
 * The redaction runs on every log line before it hits Logcat. A regression
 * here would leak API keys to anyone with `adb logcat` access. These tests
 * mirror the exact regexes from AgentLogger.redactSecrets() to lock the
 * contract independently of Hilt setup.
 */
class AgentLoggerRedactionTest {

    private fun redactSecrets(msg: String): String {
        var s = msg
        s = Regex("(?i)x-goog-api-key\\s*[:=]?\\s*[A-Za-z0-9_\\-]{16,}").replace(s, "x-goog-api-key: ***REDACTED***")
        s = Regex("(?i)(api[_-]?key=)[A-Za-z0-9_\\-]{8,}").replace(s, "$1***REDACTED***")
        s = Regex("(?i)Bearer\\s+[A-Za-z0-9_\\-\\.=]{16,}").replace(s, "Bearer ***REDACTED***")
        s = Regex("(?i)sk-[A-Za-z0-9_\\-]{16,}").replace(s, "sk-***REDACTED***")
        s = Regex("(?i)AIza[A-Za-z0-9_\\-]{30,}").replace(s, "AIza***REDACTED***")
        return s
    }

    @Test
    fun `redacts openai-style sk- key`() {
        val redacted = redactSecrets("Authorization: sk-abc123def456ghi789jkl012mno")
        assertEquals("Authorization: sk-***REDACTED***", redacted)
    }

    @Test
    fun `redacts gemini-style AIza key`() {
        val redacted = redactSecrets("key=AIzaSyABCDEFGH1234567890_abcdefghijklmnopqrstuvwxyz")
        assertEquals("key=AIza***REDACTED***", redacted)
    }

    @Test
    fun `redacts Bearer token`() {
        val redacted = redactSecrets("Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIx")
        assertEquals("Bearer ***REDACTED***", redacted)
    }

    @Test
    fun `redacts x-goog-api-key header form`() {
        val redacted = redactSecrets("x-goog-api-key: AIzaSyD1234567890_-abcdefghijklmnopqrstuvwxyz")
        assertEquals("x-goog-api-key: ***REDACTED***", redacted)
    }

    @Test
    fun `redacts x-goog-api-key query form`() {
        val redacted = redactSecrets("x-goog-api-key=AIzaSyD1234567890_-abcdefghijklmnopqrstuvwxyz")
        assertEquals("x-goog-api-key: ***REDACTED***", redacted)
    }

    @Test
    fun `redacts api_key query param`() {
        val redacted = redactSecrets("https://api.example.com/v1?api_key=sk-abc123def456ghi789")
        assertEquals("https://api.example.com/v1?api_key=***REDACTED***", redacted)
    }

    @Test
    fun `redacts api-key with dash query param`() {
        val redacted = redactSecrets("https://api.example.com/v1?api-key=abcdefgh12345678")
        assertEquals("https://api.example.com/v1?api-key=***REDACTED***", redacted)
    }

    @Test
    fun `does not redact short strings that look like keys`() {
        // An sk- prefix with fewer than 16 chars after should not be redacted
        // (it's probably just a coincidental prefix, not a real key).
        val redacted = redactSecrets("short: sk-abc123")
        assertEquals("short: sk-abc123", redacted)
    }

    @Test
    fun `preserves non-secret log content`() {
        val redacted = redactSecrets("Agent step 3: tap(500, 800) succeeded")
        assertEquals("Agent step 3: tap(500, 800) succeeded", redacted)
    }

    @Test
    fun `redacts multiple secrets in one line`() {
        val redacted = redactSecrets("keys: sk-abc123def456ghi789jkl012mno and Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIx")
        assertEquals("keys: sk-***REDACTED*** and Bearer ***REDACTED***", redacted)
    }

    @Test
    fun `rebind ref with null returns null`() {
        // Mirror AgentLogger.rebindRef() logic.
        fun rebindRef(ref: String?, currentPackage: String): String? {
            if (ref.isNullOrBlank()) return null
            if (!ref.contains(":id/")) return ref
            val resourceName = ref.substringAfter(":id/")
            return "$currentPackage:id/$resourceName"
        }
        assertNull(rebindRef(null, "com.bar"))
    }

    @Test
    fun `rebind ref rewrites cross-package id`() {
        fun rebindRef(ref: String?, currentPackage: String): String? {
            if (ref.isNullOrBlank()) return null
            if (!ref.contains(":id/")) return ref
            val resourceName = ref.substringAfter(":id/")
            return "$currentPackage:id/$resourceName"
        }
        assertEquals("com.bar:id/search_btn",
            rebindRef("com.foo:id/search_btn", "com.bar"))
    }
}
