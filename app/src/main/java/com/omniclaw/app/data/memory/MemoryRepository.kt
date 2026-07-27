package com.omniclaw.app.data.memory

import android.util.Log
import com.omniclaw.app.data.local.MemoryDao
import com.omniclaw.app.data.local.MemoryEntity
import com.omniclaw.app.data.model.MemoryEntry
import com.omniclaw.app.data.model.MemoryEntry.MemoryKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface MemoryRepository {
    val entries: StateFlow<List<MemoryEntry>>
    fun add(kind: MemoryKind, content: String, source: String)
    fun pin(id: String, pinned: Boolean)
    fun forget(id: String)
    fun clearWorking()
}

@Singleton
class MemoryRepositoryImpl @Inject constructor(
    private val dao: MemoryDao,
) : MemoryRepository {

    companion object {
        private const val TAG = "MemoryRepository"
    }

    // M-32: hold the SupervisorJob separately so close() can cancel the
    // collector scope. Previously the anonymous scope was never cancelled,
    // leaking the repository's coroutines for the process lifetime.
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.Default)

    /** Cancel the collector scope (mirror of SessionRepositoryImpl.close). */
    fun close() {
        scope.cancel()
    }

    override val entries: StateFlow<List<MemoryEntry>> = run {
        val initial = MutableStateFlow<List<MemoryEntry>>(emptyList())
        // D-M11: wrap the collector in `.catch` so a transient DB error (e.g.
        // SQLiteFullException, transient corruption that Room auto-recovers from)
        // doesn't silently kill the collector and leave `entries` stale forever.
        // Previously the collector would throw, the SupervisorJob would swallow
        // the exception, and the UI would show no memories even after the DB
        // recovered — with no log entry explaining why.
        scope.launch {
            dao.observeAll().map { entities -> entities.map { it.toDomain() } }
                .catch { e -> Log.e(TAG, "memory collector failed", e) }
                .collect { list -> initial.value = list }
        }
        initial.asStateFlow()
    }

    override fun add(kind: MemoryKind, content: String, source: String) {
        scope.launch {
            dao.upsert(
                MemoryEntity(
                    // D-H2: full UUID (122 bits of entropy). The previous
                    // UUID.take(8) yielded only 32-bit IDs whose birthday bound
                    // (~65k entries) collided for long-term users; combined with
                    // @Insert(onConflict = REPLACE) a collision silently
                    // overwrote an existing memory row.
                    id = UUID.randomUUID().toString(),
                    kind = kind.name,
                    content = content,
                    createdAt = System.currentTimeMillis(),
                    source = source,
                    pinned = false,
                )
            )
        }
    }

    override fun pin(id: String, pinned: Boolean) {
        scope.launch { dao.setPinned(id, pinned) }
    }

    override fun forget(id: String) {
        scope.launch { dao.delete(id) }
    }

    override fun clearWorking() {
        scope.launch { dao.deleteByKind(MemoryKind.WORKING.name) }
    }

    private fun MemoryEntity.toDomain(): MemoryEntry = MemoryEntry(
        id = id,
        kind = runCatching { MemoryKind.valueOf(kind) }.getOrDefault(MemoryKind.FACT),
        content = content,
        createdAt = createdAt,
        source = source,
        pinned = pinned,
    )
}
