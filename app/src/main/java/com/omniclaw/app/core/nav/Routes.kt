package com.omniclaw.app.core.nav

import kotlinx.serialization.Serializable

/**
 * Typed navigation routes for type-safe navigation.
 *
 * Replaces string-based routes with serializable route classes to prevent
 * typos and enable compile-time checking of navigation arguments.
 */
object Routes {
    @Serializable
    data class Chat(val sessionId: String? = null) {
        fun createRoute(sessionId: String? = null): String {
            return if (sessionId != null) "chat?sessionId=$sessionId" else "chat"
        }
    }

    @Serializable
    object Sessions {
        fun createRoute(): String = "sessions"
    }

    @Serializable
    object Memory {
        fun createRoute(): String = "memory"
    }

    @Serializable
    object Schedule {
        fun createRoute(): String = "schedule"
    }

    @Serializable
    object Settings {
        fun createRoute(): String = "settings"
    }

    @Serializable
    data class SessionDetail(val sessionId: String) {
        fun createRoute(): String = "session_detail/$sessionId"
    }
    
    // Backward compatibility constants
    const val CHAT_ROUTE = "chat"
    const val SESSIONS_ROUTE = "sessions"
    const val MEMORY_ROUTE = "memory"
    const val SCHEDULE_ROUTE = "schedule"
    const val SETTINGS_ROUTE = "settings"
}
