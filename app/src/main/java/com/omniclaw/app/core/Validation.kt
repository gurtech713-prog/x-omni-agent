package com.omniclaw.app.core

import android.util.Patterns

/**
 * Validates that [url] is a well-formed HTTP or HTTPS URL.
 * Used by the Settings screen to catch typos before persisting.
 */
fun isValidHttpUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val lower = url.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    return Patterns.WEB_URL.matcher(url).matches()
}
