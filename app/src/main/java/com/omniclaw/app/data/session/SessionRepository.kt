package com.omniclaw.app.data.session

import com.omniclaw.app.data.local.SessionDao
import com.omniclaw.app.data.local.SessionEntity
import com.omniclaw.app.data.model.ChatMessage
import com.omniclaw.app.data.model.Session
import com.omniclaw.app.data.model.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

interface SessionRepository {
    val sessions: StateFlow<List<Session>>

    /** Suspend DB-backed lookup. Use this from coroutine contexts (AgentLoop, workers). */
    suspend fun getById(id: String): Session?

    /** Best-effort snapshot read from the in-memory StateFlow. Use only when
     *  a suspend call is impossible (e.g. from a Composable event handler). */
    fun getByIdSnapshot(id: String): Session?

    fun create(title: String): Session
    fun appendMessage(id: String, message: ChatMessage)
    fun setStatus(id: String, status: SessionStatus)
    fun incSteps(id: String, by: Int = 1)
    fun addTokens(id: String, n: Long)
    fun stop(id: String)
    fun delete(id: String)
    fun clearAll()
}

@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val dao: SessionDao,
    private val json: Json,
) : SessionRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val msgSerializer = ListSerializer(ChatMessage.serializer())

    /**
     * Per-session mutex map — serializes appendMessage calls so two rapid
     * appends don't race on read-modify-write and silently drop messages.
     */
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(id: String): Mutex =
        sessionMutexes.computeIfAbsent(id) { Mutex() }

    // Private mutable StateFlow that we own and can update immediately (create/delete).
    // Room's observeAll() also feeds into it, but for operations like create() we
    // pre-populate it synchronously so getByIdSnapshot() works right after create().
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())

    // Bridge Room's Flow to StateFlow for backward compat with existing ViewModels.
    override val sessions: StateFlow<List<Session>> get() = _sessions

    init {
        scope.launch {
            dao.observeAll().map { entities -> entities.map { it.toDomain() } }
                .collect { list -> _sessions.value = list }
        }
    }

    override suspend fun getById(id: String): Session? {
        val entity = dao.getById(id) ?: return null
        return entity.toDomain()
    }

    override fun getByIdSnapshot(id: String): Session? =
        _sessions.value.firstOrNull { it.id == id }

    override fun create(title: String): Session {
        val now = System.currentTimeMillis()
        val session = Session(
            id = UUID.randomUUID().toString().take(8),
            title = title.ifBlank { "Untitled session" },
            createdAt = now,
            lastActiveAt = now,
            status = SessionStatus.IDLE,
            stepCount = 0,
            tokenUsage = 0L,
            messages = emptyList(),
        )
        // Immediately add to the in-memory StateFlow so getByIdSnapshot() works right after.
        _sessions.value = _sessions.value + session
        scope.launch { dao.upsert(session.toEntity()) }
        return session
    }

    /**
     * Append a message to session [id]. Serialized per-session via [mutexFor]
     * to prevent the read-modify-write race that previously dropped messages
     * when multiple appends landed in quick succession (e.g. assistant message
     * immediately followed by tool-call message in the same step).
     */
    override fun appendMessage(id: String, message: ChatMessage) {
        _sessions.value = _sessions.value.map { s ->
            if (s.id == id) s.copy(messages = s.messages + message, lastActiveAt = System.currentTimeMillis()) else s
        }
        scope.launch {
            mutexFor(id).withLock {
                val entity = dao.getById(id) ?: return@withLock
                val msgs = deserializeMessages(entity.messagesJson) + message
                dao.upsert(entity.copy(
                    messagesJson = serializeMessages(msgs),
                    lastActiveAt = System.currentTimeMillis(),
                ))
            }
        }
    }

    override fun setStatus(id: String, status: SessionStatus) {
        _sessions.value = _sessions.value.map { s ->
            if (s.id == id) s.copy(status = status, lastActiveAt = System.currentTimeMillis()) else s
        }
        scope.launch {
            dao.updateStatus(id, status.name, System.currentTimeMillis())
        }
    }

    override fun incSteps(id: String, by: Int) {
        _sessions.value = _sessions.value.map { s ->
            if (s.id == id) s.copy(stepCount = s.stepCount + by) else s
        }
        scope.launch { dao.incSteps(id, by) }
    }

    override fun addTokens(id: String, n: Long) {
        _sessions.value = _sessions.value.map { s ->
            if (s.id == id) s.copy(tokenUsage = s.tokenUsage + n) else s
        }
        scope.launch { dao.addTokens(id, n) }
    }

    override fun stop(id: String) = setStatus(id, SessionStatus.STOPPED)

    override fun delete(id: String) {
        sessionMutexes.remove(id)
        _sessions.value = _sessions.value.filter { it.id != id }
        scope.launch { dao.delete(id) }
    }

    override fun clearAll() {
        sessionMutexes.clear()
        _sessions.value = emptyList()
        scope.launch { dao.clearAll() }
    }

    private fun serializeMessages(msgs: List<ChatMessage>): String =
        json.encodeToString(msgSerializer, msgs)

    private fun deserializeMessages(s: String): List<ChatMessage> =
        if (s.isBlank()) emptyList()
        else runCatching { json.decodeFromString(msgSerializer, s) }.getOrDefault(emptyList())

    private fun SessionEntity.toDomain(): Session = Session(
        id = id,
        title = title,
        createdAt = createdAt,
        lastActiveAt = lastActiveAt,
        status = runCatching { SessionStatus.valueOf(status) }.getOrDefault(SessionStatus.IDLE),
        stepCount = stepCount,
        tokenUsage = tokenUsage,
        messages = deserializeMessages(messagesJson),
    )

    private fun Session.toEntity(): SessionEntity = SessionEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        lastActiveAt = lastActiveAt,
        status = status.name,
        stepCount = stepCount,
        tokenUsage = tokenUsage,
        messagesJson = serializeMessages(messages),
    )
}
