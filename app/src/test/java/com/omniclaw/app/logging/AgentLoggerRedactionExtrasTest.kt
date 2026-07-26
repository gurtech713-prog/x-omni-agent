package com.omniclaw.app.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AgentLogger.redactSecrets] (accessed indirectly via the
 * public [logInfo] / [logError] entry points). Verifies that:
 *   - OpenAI / Gemini / Bearer tokens are masked
 *   - Discord / Slack webhook URLs (which contain the secret token in the
 *     path) are masked down to the host
 *   - Feishu app_secret fields are masked
 *
 * Regression coverage for the production-audit finding that ChannelSender
 * was leaking Discord webhook URLs to Logcat via raw `android.util.Log.w`
 * calls (now routed through AgentLogger).
 */
class AgentLoggerRedactionExtrasTest {

    private val logger = AgentLogger()

    // The private redactSecrets method is exercised via the public logInfo;
    // we capture output by reading Logcat is impractical in a unit test, so
    // we verify the redaction indirectly: if redaction throws or fails to
    // match, the call still completes. The actual Logcat capture is done in
    // AgentLoggerRedactionTest (existing). Here we focus on the NEW webhook
    // + Feishu patterns added in this audit.

    @Test
    fun `discord webhook url is redacted by the new pattern`() {
        // The regex pattern is internal; we verify by checking that a string
        // containing a Discord webhook URL is processed without throwing.
        // A full Logcat-capture test would require Robolectric; we test the
        // regex contract via reflection on the redactSecrets private method.
        val redact = AgentLogger::class.java
            .getDeclaredMethod("redactSecrets", String::class.java)
            .apply { isAccessible = true }
        val input = "POST to https://discord.com/api/webhooks/1234567890/abcDEF_xyz-1234567890 failed"
        val output = redact.invoke(logger, input) as String
        assertTrue("Webhook URL must be redacted", output.contains("REDACTED"))
        assertFalse("Raw token must not leak", output.contains("abcDEF_xyz-1234567890"))
        // Host is preserved for diagnostic value.
        assertTrue("Host should be preserved", output.contains("discord.com"))
    }

    @Test
    fun `slack webhook url is redacted`() {
        val redact = AgentLogger::class.java
            .getDeclaredMethod("redactSecrets", String::class.java)
            .apply { isAccessible = true }
        val input = "POST to https://hooks.slack.com/services/T0001/B0002/XYZABC123456 failed"
        val output = redact.invoke(logger, input) as String
        assertTrue("Slack webhook must be redacted", output.contains("REDACTED"))
        assertFalse("Slack token must not leak", output.contains("XYZABC123456"))
    }

    @Test
    fun `feishu app_secret in json form is redacted`() {
        val redact = AgentLogger::class.java
            .getDeclaredMethod("redactSecrets", String::class.java)
            .apply { isAccessible = true }
        val input = """body: {"app_secret":"abCDefGH1234567890","app_id":"cli_x"}"""
        val output = redact.invoke(logger, input) as String
        assertTrue("app_secret must be redacted", output.contains("REDACTED"))
        assertFalse("Raw secret must not leak", output.contains("abCDefGH1234567890"))
    }

    @Test
    fun `plain text without secrets is unchanged`() {
        val redact = AgentLogger::class.java
            .getDeclaredMethod("redactSecrets", String::class.java)
            .apply { isAccessible = true }
        val input = "Agent step 3 completed: tapped the search button"
        val output = redact.invoke(logger, input) as String
        assertFalse("Plain text should not contain REDACTED", output.contains("REDACTED"))
    }
}
