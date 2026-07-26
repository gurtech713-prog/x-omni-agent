package com.omniclaw.app.deeplink

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deep link handler for session-based navigation.
 *
 * Supports URLs like:
 *   omniclaw://session/{sessionId}
 *   https://omniclaw.app/session/{sessionId}
 */
@Singleton
class DeepLinkHandler @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    companion object {
        const val SCHEME = "omniclaw"
        const val HOST_SESSION = "session"
        const val HOST_SKILL = "skill"
        
        fun createSessionUri(sessionId: String): Uri {
            return Uri.Builder()
                .scheme(SCHEME)
                .authority(HOST_SESSION)
                .path("/")
                .appendQueryParameter("id", sessionId)
                .build()
        }
        
        fun isOmniClawDeepLink(intent: Intent?): Boolean {
            if (intent == null) return false
            val uri = intent.data ?: return false
            return uri.scheme == SCHEME || 
                   uri.host?.contains("omniclaw") == true
        }
        
        fun extractSessionId(intent: Intent?): String? {
            if (intent == null) return null
            val uri = intent.data ?: return null
            
            return when {
                uri.scheme == SCHEME -> uri.getQueryParameter("id")
                uri.host?.contains("session") == true -> uri.lastPathSegment
                else -> null
            }
        }
    }
    
    /** Open a specific session by ID via deep link. */
    fun openSession(sessionId: String) {
        val uri = createSessionUri(sessionId)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        runCatching { ctx.startActivity(intent) }
    }
    
    /** Parse an incoming intent to see if it's a deep link. */
    fun handleIntent(intent: Intent?): DeepLinkResult {
        if (!isOmniClawDeepLink(intent)) return DeepLinkResult.NONE
        
        val sessionId = extractSessionId(intent)
        return if (sessionId != null) {
            DeepLinkResult.SESSION_OPEN(sessionId)
        } else {
            DeepLinkResult.INVALID
        }
    }
}

sealed class DeepLinkResult {
    data object NONE : DeepLinkResult()
    data class SESSION_OPEN(val sessionId: String) : DeepLinkResult()
    data object INVALID : DeepLinkResult()
}
