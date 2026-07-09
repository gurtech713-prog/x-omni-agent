package com.omniclaw.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY lastActiveAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

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

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sessions")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM sessions")
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
            "ORDER BY confidence DESC, lastSeenAt DESC LIMIT :limit"
    )
    suspend fun forScreen(fingerprint: String, limit: Int = 5): List<LessonEntity>

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

    @Query("DELETE FROM lessons WHERE confidence < :minConfidence AND lastSeenAt < :before")
    suspend fun pruneStale(minConfidence: Int, before: Long)
}
