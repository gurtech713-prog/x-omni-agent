package com.omniclaw.app.data.memory

import com.omniclaw.app.data.local.MemoryDao
import com.omniclaw.app.data.local.MemoryEntity
import com.omniclaw.app.data.model.MemoryEntry
import com.omniclaw.app.data.model.MemoryEntry.MemoryKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val entries: StateFlow<List<MemoryEntry>> = run {
        val initial = MutableStateFlow<List<MemoryEntry>>(emptyList())
        scope.launch {
            dao.observeAll().map { entities -> entities.map { it.toDomain() } }
                .collect { list -> initial.value = list }
        }
        initial.asStateFlow()
    }

    override fun add(kind: MemoryKind, content: String, source: String) {
        scope.launch {
            dao.upsert(
                MemoryEntity(
                    id = UUID.randomUUID().toString().take(8),
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
