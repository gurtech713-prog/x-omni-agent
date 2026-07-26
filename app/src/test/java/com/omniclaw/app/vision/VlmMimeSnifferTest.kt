package com.omniclaw.app.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [VlmClient]'s image-MIME sniffer.
 *
 * The sniffer is private in VlmClient, so we mirror the exact byte-signature
 * logic here. A regression would cause the VLM data URI to declare the wrong
 * MIME type, which some providers reject with a 400.
 *
 * The two capture paths produce different formats:
 *   - ScreenCaptureService → WebP (RIFF....WEBP)
 *   - OmniAccessibilityService → PNG (89 50 4E 47)
 * The sniffer must distinguish them correctly.
 */
class VlmMimeSnifferTest {

    private fun sniffImageMime(bytes: ByteArray): String = when {
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "image/webp"
        bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        else -> "image/png"
    }

    @Test
    fun `webp signature returns image-webp`() {
        // RIFF....WEBP
        val webp = ByteArray(14).apply {
            this[0] = 'R'.code.toByte()
            this[1] = 'I'.code.toByte()
            this[2] = 'F'.code.toByte()
            this[3] = 'F'.code.toByte()
            this[8] = 'W'.code.toByte()
            this[9] = 'E'.code.toByte()
            this[10] = 'B'.code.toByte()
            this[11] = 'P'.code.toByte()
        }
        assertEquals("image/webp", sniffImageMime(webp))
    }

    @Test
    fun `png signature returns image-png`() {
        // 89 50 4E 47 0D 0A 1A 0A
        val png = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
        )
        assertEquals("image/png", sniffImageMime(png))
    }

    @Test
    fun `jpeg signature returns image-jpeg`() {
        // FF D8 FF E0 ...
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals("image/jpeg", sniffImageMime(jpeg))
    }

    @Test
    fun `unknown signature defaults to image-png`() {
        assertEquals("image/png", sniffImageMime(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
        assertEquals("image/png", sniffImageMime("hello world".toByteArray()))
    }

    @Test
    fun `empty bytes default to image-png`() {
        // The sniffer's when-block falls through to else for size < 3.
        assertEquals("image/png", sniffImageMime(ByteArray(0)))
        assertEquals("image/png", sniffImageMime(ByteArray(2)))
    }

    @Test
    fun `webp signature with trailing data still detected`() {
        // Real WebP files have VP8/VP8L/VP8X chunks after the 12-byte header.
        val webp = ByteArray(100).apply {
            this[0] = 'R'.code.toByte()
            this[1] = 'I'.code.toByte()
            this[2] = 'F'.code.toByte()
            this[3] = 'F'.code.toByte()
            this[8] = 'W'.code.toByte()
            this[9] = 'E'.code.toByte()
            this[10] = 'B'.code.toByte()
            this[11] = 'P'.code.toByte()
            // Fill the rest with arbitrary chunk data
            for (i in 12 until size) this[i] = (i and 0xFF).toByte()
        }
        assertEquals("image/webp", sniffImageMime(webp))
    }
}
