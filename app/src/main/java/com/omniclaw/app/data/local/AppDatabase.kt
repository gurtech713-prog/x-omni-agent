package com.omniclaw.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Database(
    entities = [SessionEntity::class, MemoryEntity::class, LessonEntity::class, ChatMessageEntity::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun memoryDao(): MemoryDao
    abstract fun lessonDao(): LessonDao
    abstract fun chatMessageDao(): ChatMessageDao
}

@Singleton
class DatabaseProvider @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    /**
     * The Room database. Built once and cached.
     *
     * Migration policy: explicit migrations are registered via [MIGRATION_1_2]
     * and [MIGRATION_2_3].
     * We do NOT use [androidx.room.RoomDatabase.Builder.fallbackToDestructiveMigration]
     * because it silently wipes ALL user data (sessions, memory, lessons) if a
     * future schema version lacks a migration — a catastrophic data-loss bug
     * in production. Instead, a missing migration will throw
     * [androidx.room.RoomOpenHelper.ValidationFailedException] at first DB
     * access, which is loud and debuggable.
     *
     * Journal mode: WAL (Write-Ahead Logging) is enabled by default on
     * API 16+ and allows concurrent reads + a single writer, which is what
     * the agent loop needs (it writes while the UI reads).
     */
    val db: AppDatabase by lazy {
        Room.databaseBuilder(ctx, AppDatabase::class.java, "omniclaw.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            // Write-Ahead Logging: enables concurrent readers + 1 writer.
            // Critical for the agent loop (writer) + chat UI (reader) to not
            // block each other. Default on modern Android, but we set it
            // explicitly for determinism across OEM builds.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }

    companion object {
        /**
         * v1 → v2: add the `lessons` table for the self-learning engine.
         *
         * Non-destructive — existing sessions and memory entries are preserved
         * so the user doesn't lose history or pinned memories on update.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lessons (
                        id TEXT NOT NULL PRIMARY KEY,
                        screenFingerprint TEXT NOT NULL,
                        actionSignature TEXT NOT NULL,
                        outcome TEXT NOT NULL,
                        lessonText TEXT NOT NULL,
                        confidence INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastSeenAt INTEGER NOT NULL,
                        sourceSessionId TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_lessons_screenFingerprint ON lessons(screenFingerprint)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_lessons_actionSignature ON lessons(actionSignature)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_lessons_outcome ON lessons(outcome)"
                )
            }
        }

        /**
         * v2 → v3: add query-performance indexes to the `sessions` table.
         *
         * `observeAll()` orders by `lastActiveAt DESC` on every UI frame —
         * without an index this is a full table scan. `status` is indexed
         * because the agent loop filters by terminal status (DONE/FAILED/
         * STOPPED) when polling for session completion. Both indexes are
         * non-destructive (no schema change to columns).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sessions_lastActiveAt ON sessions(lastActiveAt)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sessions_status ON sessions(status)"
                )
            }
        }

        /**
         * v3 → v4: normalize chat messages into a separate `chat_messages` table.
         *
         * Previously, all messages for a session were stored as a JSON string
         * in `sessions.messagesJson`. This made querying, pagination, and
         * incremental updates inefficient. The new schema stores each message
         * as a row in `chat_messages`, with a foreign key to `sessions.id`.
         *
         * Migration strategy:
         * 1. Create the new `chat_messages` table.
         * 2. For each existing session, parse `messagesJson` and insert rows.
         * 3. Mark the `messagesJson` column as deprecated but leave it intact
         *    for backward compatibility during the transition period.
         * 4. Add an index on `chat_messages.sessionId` for fast lookup.
         *
         * This is destructive to the `sessions` table structure but preserves
         * all message data in the new table.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create the new chat_messages table.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        toolCallId TEXT,
                        FOREIGN KEY (sessionId) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_messages_sessionId ON chat_messages(sessionId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_messages_timestamp ON chat_messages(timestamp)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_messages_role ON chat_messages(role)"
                )

                // Migrate existing messages from sessions.messagesJson.
                db.query("SELECT id, messagesJson FROM sessions").use { cursor ->
                    while (cursor.moveToNext()) {
                        val sessionId = cursor.getString(0)
                        val messagesJson = cursor.getString(1) ?: continue
                        if (messagesJson.isBlank()) continue

                        val messages = try {
                            Converters().deserializeMessages(messagesJson)
                        } catch (_: Exception) {
                            continue
                        }

                        for (message in messages) {
                            val toolCallId = message.toolCalls?.firstOrNull()?.id
                            db.execSQL(
                                """
                                INSERT OR REPLACE INTO chat_messages (id, sessionId, role, content, timestamp, toolCallId)
                                VALUES (?, ?, ?, ?, ?, ?)
                                """.trimIndent(),
                                arrayOf(
                                    message.id,
                                    sessionId,
                                    message.role.name,
                                    message.content,
                                    message.timestamp,
                                    toolCallId,
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
