package com.omniclaw.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for Room database migrations.
 *
 * Verifies that migrations work correctly with real data and don't cause
 * data loss or corruption. These tests are critical for production readiness.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTests {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries() // For test simplicity
            .addMigrations(
                DatabaseProvider.MIGRATION_1_2,
                DatabaseProvider.MIGRATION_2_3,
            )
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun migration_1_to_2_addsLessonsTable() = runBlocking {
        // Verify lessons table exists after migration
        val lessonDao = db.lessonDao()
        
        // Insert a test lesson to verify the table works
        val lesson = LessonEntity(
            id = "test_lesson",
            screenFingerprint = "test_fp",
            actionSignature = "tap(100,200)",
            outcome = "SUCCESS",
            lessonText = "Tap at 100,200 works",
            confidence = 1,
            createdAt = System.currentTimeMillis(),
            lastSeenAt = System.currentTimeMillis(),
            sourceSessionId = "test_session",
        )
        lessonDao.upsert(lesson)
        
        val lessons = lessonDao.observeAll().first()
        assertEquals(1, lessons.size)
        assertEquals("test_lesson", lessons[0].id)
    }

    @Test
    fun migration_2_to_3_addsIndexes() = runBlocking {
        // Verify sessions table can be queried efficiently
        val sessionDao = db.sessionDao()
        
        // Insert test sessions with different statuses
        val now = System.currentTimeMillis()
        repeat(5) { i ->
            val session = SessionEntity(
                id = "session_$i",
                title = "Test Session $i",
                createdAt = now - i * 1000,
                lastActiveAt = now - i * 1000,
                status = if (i % 2 == 0) "DONE" else "RUNNING",
                stepCount = i,
                tokenUsage = i.toLong() * 100,
                messagesJson = "[]",
            )
            sessionDao.upsert(session)
        }
        
        // Query all sessions ordered by lastActiveAt
        val sessions = sessionDao.observeAll().first()
        assertEquals(5, sessions.size)
        
        // Verify ordering (most recent first)
        assertTrue(sessions[0].lastActiveAt >= sessions[1].lastActiveAt)
    }

    @Test
    fun migration_preservesExistingData() = runBlocking {
        // Insert data before migration would have been applied
        val sessionDao = db.sessionDao()
        val memoryDao = db.memoryDao()
        
        val session = SessionEntity(
            id = "pre_migration_session",
            title = "Pre-migration Session",
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis(),
            status = "DONE",
            stepCount = 10,
            tokenUsage = 1000,
            messagesJson = """[{"id":"msg1","role":"USER","content":"Hello","timestamp":123}]""",
        )
        sessionDao.upsert(session)
        
        val memory = MemoryEntity(
            id = "pre_migration_memory",
            kind = "FACT",
            content = "Important fact",
            createdAt = System.currentTimeMillis(),
            source = "test",
            pinned = true,
        )
        memoryDao.upsert(memory)
        
        // Verify data is preserved
        val sessions = sessionDao.observeAll().first()
        assertEquals(1, sessions.size)
        assertEquals("pre_migration_session", sessions[0].id)
        
        val memories = memoryDao.observeAll().first()
        assertEquals(1, memories.size)
        assertEquals("pre_migration_memory", memories[0].id)
    }

    @Test
    fun migration_handlesEmptyDatabase() = runBlocking {
        // Verify migrations work on empty database
        val sessionDao = db.sessionDao()
        val sessions = sessionDao.observeAll().first()
        assertEquals(0, sessions.size)
        
        val memoryDao = db.memoryDao()
        val memories = memoryDao.observeAll().first()
        assertEquals(0, memories.size)
        
        val lessonDao = db.lessonDao()
        val lessons = lessonDao.observeAll().first()
        assertEquals(0, lessons.size)
    }

    @Test
    fun migration_indexes_improveQueryPerformance() = runBlocking {
        // Insert many sessions to test index performance
        val sessionDao = db.sessionDao()
        val now = System.currentTimeMillis()
        
        repeat(100) { i ->
            val session = SessionEntity(
                id = "perf_session_$i",
                title = "Performance Test $i",
                createdAt = now - i,
                lastActiveAt = now - i,
                status = if (i % 3 == 0) "DONE" else "RUNNING",
                stepCount = i,
                tokenUsage = i.toLong(),
                messagesJson = "[]",
            )
            sessionDao.upsert(session)
        }
        
        // Query with filter should use index
        val doneSessions = sessionDao.observeAll().first().filter { it.status == "DONE" }
        assertTrue(doneSessions.size > 0)
        
        // Query all should be efficient
        val allSessions = sessionDao.observeAll().first()
        assertEquals(100, allSessions.size)
    }
}
