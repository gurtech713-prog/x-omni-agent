package com.omniclaw.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val lastActiveAt: Long,
    val status: String,
    val stepCount: Int,
    val tokenUsage: Long,
    val messagesJson: String,  // serialized List<ChatMessage>
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
}
