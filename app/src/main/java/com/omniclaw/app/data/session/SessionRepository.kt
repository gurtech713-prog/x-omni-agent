package com.omniclaw.app.data.session

import android.util.Log
import com.omniclaw.app.data.local.ChatMessageDao
import com.omniclaw.app.data.local.ChatMessageEntity
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
import kotlinx.coroutines.flow.update
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
    suspend fun appendMessage(id: String, message: ChatMessage)
    fun setStatus(id: String, status: SessionStatus)
    fun incSteps(id: String, by: Int = 1)
    fun addTokens(id: String, n: Long)
    /** Update the session title. Used by AgentLoop to set the title from the
     *  first user prompt, so the Sessions list shows meaningful titles instead
     *  of "New session" for every chat. */
    fun setTitle(id: String, title: String)
    fun stop(id: String)
    fun delete(id: String)
    fun clearAll()
}

@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val sessionDao: SessionDao,
    private val chatMessageDao: ChatMessageDao,
    private val json: Json,
) : SessionRepository {

    companion object {
        private const val TAG = "SessionRepository"
    }

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
            sessionDao.observeAll().collect { entities ->
                val dbSessions = entities.map { entity ->
                    val msgs = chatMessageDao.getBySession(entity.id).map { it.toDomain() }
                    entity.toDomain().copy(messages = msgs)
                }
                _sessions.update { currentList ->
                    dbSessions.map { dbSession ->
                        val currentSession = currentList.firstOrNull { it.id == dbSession.id }
                        if (currentSession != null) {
                            val combined = (currentSession.messages + dbSession.messages).distinctBy { m -> m.id }
                            dbSession.copy(messages = combined)
                        } else {
                            dbSession
                        }
                    }
                }
            }
        }
    }

    override suspend fun getById(id: String): Session? {
        val cached = getByIdSnapshot(id)
        if (cached != null && cached.messages.isNotEmpty()) return cached
        val entity = sessionDao.getById(id) ?: return null
        val msgs = chatMessageDao.getBySession(id).map { it.toDomain() }
        return entity.toDomain().copy(messages = msgs)
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
        // Use the atomic `update` extension (CAS loop) instead of a non-atomic
        // read-then-write — under concurrent mutators for different session
        // IDs the non-atomic form silently clobbered the slower write, dropping
        // a session from the UI-visible state.
        _sessions.update { list -> list + session }
        scope.launch { sessionDao.upsert(session.toEntity()) }
        return session
    }

    /**
     * Append a message to session [id]. Serialized per-session via [mutexFor]
     * to prevent the read-modify-write race that previously dropped messages
     * when multiple appends landed in quick succession (e.g. assistant message
     * immediately followed by tool-call message in the same step).
     *
     * With the new schema, messages are stored in a separate chat_messages table.
     * The in-memory StateFlow is updated immediately for UI responsiveness,
     * while the database write is performed asynchronously.
     *
     * RACE CONDITION DIAGNOSIS:
     * - In-memory update is synchronous and atomic (_sessions.update uses CAS loop)
     * - DB write is asynchronous and may fail silently
     * - If app crashes between UI update and DB write, messages are lost on restart
     * - Multiple rapid appends could have DB writes complete out of order
     */
    override suspend fun appendMessage(id: String, message: ChatMessage) {
        Log.d(TAG, "appendMessage START: sessionId=$id, messageId=${message.id}, role=${message.role}, timestamp=${message.timestamp}")
        
        // Step 1: Update in-memory StateFlow (synchronous, atomic)
        _sessions.update { list ->
            val session = list.firstOrNull { it.id == id }
            if (session == null) {
                Log.w(TAG, "appendMessage WARNING: session $id not found in memory!")
                list
            } else {
                val updated = session.copy(
                    messages = session.messages + message,
                    lastActiveAt = System.currentTimeMillis()
                )
                Log.d(TAG, "appendMessage UI UPDATED: sessionId=$id, messageCount=${updated.messages.size}")
                list.map { if (it.id == id) updated else it }
            }
        }
        
        // Step 2: Update database synchronously within same critical section
        try {
            mutexFor(id).withLock {
                if (sessionDao.getById(id) == null) {
                    val memSession = getByIdSnapshot(id)
                    if (memSession != null) {
                        sessionDao.upsert(memSession.toEntity())
                    }
                }
                val chatMessageEntity = message.toEntity(id)
                chatMessageDao.insert(chatMessageEntity)
                sessionDao.updateTimestamp(id, System.currentTimeMillis())
                Log.d(TAG, "appendMessage DB WRITE COMPLETED: sessionId=$id, messageId=${message.id}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "appendMessage DB WRITE FAILED: sessionId=$id, messageId=${message.id}", e)
            throw e  // Re-throw to let caller handle the failure
        }
    }

    override fun setStatus(id: String, status: SessionStatus) {
        _sessions.update { list ->
            list.map { s ->
                if (s.id == id) s.copy(status = status, lastActiveAt = System.currentTimeMillis()) else s
            }
        }
        scope.launch {
            sessionDao.updateStatus(id, status.name, System.currentTimeMillis())
        }
    }

    override fun incSteps(id: String, by: Int) {
        _sessions.update { list ->
            list.map { s ->
                if (s.id == id) s.copy(stepCount = s.stepCount + by) else s
            }
        }
        scope.launch { sessionDao.incSteps(id, by) }
    }

    override fun addTokens(id: String, n: Long) {
        _sessions.update { list ->
            list.map { s ->
                if (s.id == id) s.copy(tokenUsage = s.tokenUsage + n) else s
            }
        }
        scope.launch { sessionDao.addTokens(id, n) }
    }

    /**
     * Update the session title. Called by [AgentLoop.start] to set the title
     * from the first user prompt (truncated to 60 chars), so the Sessions list
     * shows meaningful titles like "Open Reddit and search budget..." instead
     * of "New session" for every chat.
     *
     * CHAT-9 FIX: previously there was no setTitle — every session created by
     * ChatViewModel.newSession() kept the "New session" placeholder title
     * forever, making the Sessions list unusable for finding past conversations.
     */
    override fun setTitle(id: String, title: String) {
        val truncated = title.trim().take(80).ifBlank { "Untitled session" }
        _sessions.update { list ->
            list.map { s ->
                if (s.id == id) s.copy(title = truncated, lastActiveAt = System.currentTimeMillis()) else s
            }
        }
        scope.launch { sessionDao.updateTitle(id, truncated, System.currentTimeMillis()) }
    }

    override fun stop(id: String) = setStatus(id, SessionStatus.STOPPED)

    override fun delete(id: String) {
        sessionMutexes.remove(id)
        _sessions.update { list -> list.filter { it.id != id } }
        scope.launch {
            sessionDao.delete(id)
            chatMessageDao.deleteBySession(id)
        }
    }

    override fun clearAll() {
        sessionMutexes.clear()
        _sessions.update { emptyList() }
        scope.launch {
            sessionDao.clearAll()
            chatMessageDao.clearAll()
        }
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
        messages = deserializeMessages(messagesJson.orEmpty()),
    )

    private fun Session.toEntity(): SessionEntity = SessionEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        lastActiveAt = lastActiveAt,
        status = status.name,
        stepCount = stepCount,
        tokenUsage = tokenUsage,
        messagesJson = null, // No longer storing messages in session entity
    )

    private fun ChatMessage.toEntity(sessionId: String): ChatMessageEntity = ChatMessageEntity(
        id = id,
        sessionId = sessionId,
        role = role.name,
        content = content,
        timestamp = timestamp,
        toolCallId = toolCalls?.firstOrNull()?.id,
    )

    private fun ChatMessageEntity.toDomain(): ChatMessage = ChatMessage(
        id = id,
        role = runCatching { ChatMessage.Role.valueOf(role) }.getOrDefault(ChatMessage.Role.SYSTEM),
        content = content,
        timestamp = timestamp,
        toolCallId = toolCallId,
    )
}
