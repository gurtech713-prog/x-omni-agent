package com.omniclaw.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY lastActiveAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    /**
     * H-26: one @Transaction join of sessions + their chat_messages via the
     * [SessionWithMessages] @Relation POJO. Replaces the N+1 per-session
     * getBySession() that re-ran for every session on every table change.
     */
    @Transaction
    @Query("SELECT * FROM sessions ORDER BY lastActiveAt DESC")
    fun observeSessionsWithMessages(): Flow<List<SessionWithMessages>>

    /**
     * Paginated session list for efficient UI rendering.
     * Returns latest [limit] sessions starting from [offset].
     */
    @Query("SELECT * FROM sessions ORDER BY lastActiveAt DESC LIMIT :limit OFFSET :offset")
    fun observePaginated(limit: Int, offset: Int): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Update
    suspend fun update(session: SessionEntity)

    @Query("UPDATE sessions SET status = :status, lastActiveAt = :timestamp WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, timestamp: Long)

    @Query("UPDATE sessions SET stepCount = stepCount + :by WHERE id = :id")
    suspend fun incSteps(id: String, by: Int)

    @Query("UPDATE sessions SET tokenUsage = tokenUsage + :n WHERE id = :id")
    suspend fun addTokens(id: String, n: Long)

    @Query("UPDATE sessions SET title = :title, lastActiveAt = :timestamp WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, timestamp: Long)

    @Query("UPDATE sessions SET lastActiveAt = :timestamp WHERE id = :id")
    suspend fun updateTimestamp(id: String, timestamp: Long)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sessions")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeBySession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySession(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    fun observePaginatedBySession(sessionId: String, limit: Int, offset: Int): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getById(id: String): ChatMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ChatMessageEntity>)

    @Query("UPDATE chat_messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: String, content: String)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun count(): Int
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory ORDER BY pinned DESC, createdAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MemoryEntity)

    @Query("UPDATE memory SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM memory WHERE kind = :kind")
    suspend fun deleteByKind(kind: String)

    @Query("SELECT COUNT(*) FROM memory")
    suspend fun count(): Int
}

@Dao
interface LessonDao {
    @Query("SELECT * FROM lessons ORDER BY confidence DESC, lastSeenAt DESC")
    fun observeAll(): Flow<List<LessonEntity>>

    /**
     * Find lessons matching a screen fingerprint — used by LearningEngine to
     * inject relevant past experience into the system prompt before each step.
     * Returns highest-confidence lessons first, limited to [limit] results.
     */
    @Query(
        "SELECT * FROM lessons WHERE screenFingerprint = :fingerprint " +
            "AND confidence >= :minConfidence " +
            "ORDER BY confidence DESC, lastSeenAt DESC LIMIT :limit"
    )
    suspend fun forScreen(fingerprint: String, limit: Int = 5, minConfidence: Int = 2): List<LessonEntity>

    /**
     * Find lessons matching an action signature — used to check if a candidate
     * action has failed before on any screen.
     */
    @Query(
        "SELECT * FROM lessons WHERE actionSignature = :actionSig AND outcome = 'FAILURE' " +
            "ORDER BY confidence DESC, lastSeenAt DESC LIMIT :limit"
    )
    suspend fun failuresForAction(actionSig: String, limit: Int = 3): List<LessonEntity>

    /**
     * Find an existing lesson with the same fingerprint + action + outcome,
     * so we can increment its confidence instead of creating a duplicate.
     */
    @Query(
        "SELECT * FROM lessons WHERE screenFingerprint = :fingerprint " +
            "AND actionSignature = :actionSig AND outcome = :outcome LIMIT 1"
    )
    suspend fun findExisting(
        fingerprint: String, actionSig: String, outcome: String,
    ): LessonEntity?

    /**
     * Find a lesson by ID — used by SkillEvaluator to score individual lesson quality.
     */
    @Query("SELECT * FROM lessons WHERE id = :lessonId")
    suspend fun getById(lessonId: String): LessonEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(lesson: LessonEntity)

    @Query("UPDATE lessons SET confidence = confidence + 1, lastSeenAt = :timestamp WHERE id = :id")
    suspend fun reinforce(id: String, timestamp: Long)

    @Query("DELETE FROM lessons WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM lessons")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM lessons")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM lessons WHERE confidence > 0")
    suspend fun countActive(): Int

    @Query("SELECT AVG(confidence) FROM lessons")
    suspend fun averageConfidence(): Float?

    @Query("SELECT COUNT(*) FROM lessons WHERE sourceSessionId = :sessionId")
    suspend fun countLessonsForSession(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM lessons WHERE sourceSessionId = :sessionId AND confidence > 0")
    suspend fun countApplications(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM lessons WHERE sourceSessionId = :sessionId AND confidence > 1")
    suspend fun countSuccessfulApplications(sessionId: String): Int

    @Query("UPDATE lessons SET confidence = confidence + 1 WHERE sourceSessionId = :sessionId")
    suspend fun reinforceLessonsForSession(sessionId: String)

    @Query("DELETE FROM lessons WHERE confidence < :minConfidence AND lastSeenAt < :before")
    suspend fun pruneStale(minConfidence: Int, before: Long)

    /**
     * Count how many times a specific lesson (by ID) has been reinforced.
     * Used by SkillEvaluator.evaluateLesson() to compute per-lesson application metrics.
     */
    @Query("SELECT SUM(confidence - 1) FROM lessons WHERE id = :lessonId")
    suspend fun getApplicationCountById(lessonId: String): Int?

    /**
     * Count applications where this lesson was successful (confidence increased after creation).
     */
    @Query("SELECT COUNT(*) FROM lessons WHERE id = :lessonId AND confidence > 1")
    suspend fun getSuccessfulApplicationsById(lessonId: String): Int
}
