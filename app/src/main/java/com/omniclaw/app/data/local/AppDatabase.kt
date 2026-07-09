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
    entities = [SessionEntity::class, MemoryEntity::class, LessonEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun memoryDao(): MemoryDao
    abstract fun lessonDao(): LessonDao
}

@Singleton
class DatabaseProvider @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    val db: AppDatabase by lazy {
        Room.databaseBuilder(ctx, AppDatabase::class.java, "omniclaw.db")
            .addMigrations(MIGRATION_1_2)
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
    }
}
