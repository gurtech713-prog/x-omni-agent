package com.omniclaw.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Entity(
    tableName = "sessions",
    indices = [
        Index("lastActiveAt"),
        Index("status"),
    ],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val lastActiveAt: Long,
    val status: String,
    val stepCount: Int,
    val tokenUsage: Long,
    val messagesJson: String? = null,  // Deprecated: kept for backward compatibility during migration
)

/**
 * Chat message entity — stored in separate table for efficient querying and pagination.
 *
 * Previously, all messages were stored as JSON in sessions.messagesJson. This made
 * querying, filtering, and paginating messages inefficient. Now each message is a
 * row in this table with a foreign key to sessions.id.
 *
 * FIX #7: Added explicit Room @ForeignKey declaration so Room enforces the FK
 * constraint at the ORM level AND SQLite enforces ON DELETE CASCADE. Without this,
 * deleting a session could leave orphaned chat_messages rows. The onDelete = CASCADE
 * ensures messages are deleted automatically when their parent session is deleted.
 */
@Entity(
    tableName = "chat_messages",
    indices = [
        Index("sessionId"),
        Index("timestamp"),
        Index("role"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,  // USER, ASSISTANT, TOOL, SYSTEM
    val content: String,
    val timestamp: Long,
    val toolCallId: String? = null,
)

@Entity(tableName = "memory")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val content: String,
    val createdAt: Long,
    val source: String,
    val pinned: Boolean,
)

/**
 * A learned lesson — persisted cross-session memory of what worked / didn't.
 * See [com.omniclaw.app.data.model.Lesson] for the domain counterpart.
 *
 * Indexes:
 *   - screenFingerprint: fast lookup of lessons matching the current screen
 *   - actionSignature: fast lookup of lessons matching a candidate action
 *   - outcome: filter to FAILURE lessons for avoidance, SUCCESS for repetition
 */
@Entity(
    tableName = "lessons",
    indices = [
        Index("screenFingerprint"),
        Index("actionSignature"),
        Index("outcome"),
    ]
)
data class LessonEntity(
    @PrimaryKey val id: String,
    val screenFingerprint: String,
    val actionSignature: String,
    val outcome: String,        // LESSON_OUTCOME enum name
    val lessonText: String,
    val confidence: Int,
    val createdAt: Long,
    val lastSeenAt: Long,
    val sourceSessionId: String?,
)

class Converters {
    @TypeConverter
    fun fromStringList(value: String): List<String> =
        if (value.isBlank()) emptyList()
        else Json.decodeFromString(ListSerializer(String.serializer()), value)

    @TypeConverter
    fun toStringList(list: List<String>): String =
        Json.encodeToString(ListSerializer(String.serializer()), list)

    /**
     * Deserialize messages from JSON string.
     * Used during migration to extract messages from sessions.messagesJson.
     */
    fun deserializeMessages(messagesJson: String): List<com.omniclaw.app.data.model.ChatMessage> {
        if (messagesJson.isBlank()) return emptyList()
        return runCatching {
            Json.decodeFromString(ListSerializer(com.omniclaw.app.data.model.ChatMessage.serializer()), messagesJson)
        }.getOrDefault(emptyList())
    }

    /**
     * Serialize messages to JSON string.
     * Kept for backward compatibility with old schema.
     */
    fun serializeMessages(messages: List<com.omniclaw.app.data.model.ChatMessage>): String {
        return Json.encodeToString(ListSerializer(com.omniclaw.app.data.model.ChatMessage.serializer()), messages)
    }
}
