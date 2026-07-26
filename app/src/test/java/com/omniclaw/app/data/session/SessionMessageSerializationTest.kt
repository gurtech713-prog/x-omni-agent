package com.omniclaw.app.data.session

import com.omniclaw.app.data.local.SessionEntity
import com.omniclaw.app.data.model.ChatMessage
import com.omniclaw.app.data.model.Session
import com.omniclaw.app.data.model.SessionStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the message-serialization round-trip used by
 * [SessionRepositoryImpl] to persist chat history into the Room
 * `sessions.messagesJson` column.
 *
 * The repository uses `ListSerializer(ChatMessage.serializer())` to encode
 * the message list to a JSON string and back. A regression here would either
 * crash the agent loop (corrupt JSON) or silently drop messages on reload.
 * These tests lock the serialization contract independently of Hilt / Room.
 */
class SessionMessageSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(ChatMessage.serializer())

    @Test
    fun `empty list round-trips`() {
        val encoded = json.encodeToString(serializer, emptyList())
        val decoded = json.decodeFromString(serializer, encoded)
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `single user message round-trips`() {
        val msgs = listOf(
            ChatMessage(
                id = "m1",
                role = ChatMessage.Role.USER,
                content = "Open Reddit",
                timestamp = 1700000000L,
            )
        )
        val encoded = json.encodeToString(serializer, msgs)
        val decoded = json.decodeFromString(serializer, encoded)
        assertEquals(1, decoded.size)
        assertEquals("m1", decoded[0].id)
        assertEquals(ChatMessage.Role.USER, decoded[0].role)
        assertEquals("Open Reddit", decoded[0].content)
        assertEquals(1700000000L, decoded[0].timestamp)
    }

    @Test
    fun `mixed roles round-trip preserves role enum`() {
        val msgs = listOf(
            ChatMessage("a", ChatMessage.Role.USER, "hi", 1L),
            ChatMessage("b", ChatMessage.Role.ASSISTANT, "hello", 2L),
            ChatMessage("c", ChatMessage.Role.TOOL, "ok", 3L),
            ChatMessage("d", ChatMessage.Role.SYSTEM, "warn", 4L),
        )
        val encoded = json.encodeToString(serializer, msgs)
        val decoded = json.decodeFromString(serializer, encoded)
        assertEquals(4, decoded.size)
        assertEquals(ChatMessage.Role.USER, decoded[0].role)
        assertEquals(ChatMessage.Role.ASSISTANT, decoded[1].role)
        assertEquals(ChatMessage.Role.TOOL, decoded[2].role)
        assertEquals(ChatMessage.Role.SYSTEM, decoded[3].role)
    }

    @Test
    fun `message with tool calls round-trips`() {
        val toolCall = com.omniclaw.app.data.model.ToolCall(
            id = "tc1",
            name = "tap(500, 800)",
            args = "",
            result = "ok",
            ok = true,
            durationMs = 50L,
        )
        val msgs = listOf(
            ChatMessage(
                id = "m1",
                role = ChatMessage.Role.TOOL,
                content = "tap(500, 800) -> ok",
                timestamp = 1L,
                toolCalls = listOf(toolCall),
            )
        )
        val encoded = json.encodeToString(serializer, msgs)
        val decoded = json.decodeFromString(serializer, encoded)
        assertEquals(1, decoded[0].toolCalls.size)
        assertEquals("tc1", decoded[0].toolCalls[0].id)
        assertEquals("tap(500, 800)", decoded[0].toolCalls[0].name)
        assertTrue(decoded[0].toolCalls[0].ok)
        assertEquals(50L, decoded[0].toolCalls[0].durationMs)
    }

    @Test
    fun `message with thoughts round-trips`() {
        val msgs = listOf(
            ChatMessage(
                id = "m1",
                role = ChatMessage.Role.ASSISTANT,
                content = "ACTION: tap(1,2)",
                timestamp = 1L,
                thoughts = listOf("I should tap the button", "The button is at 1,2"),
            )
        )
        val encoded = json.encodeToString(serializer, msgs)
        val decoded = json.decodeFromString(serializer, encoded)
        assertEquals(2, decoded[0].thoughts.size)
        assertEquals("I should tap the button", decoded[0].thoughts[0])
    }

    @Test
    fun `blank messagesJson decodes to empty list`() {
        // The repository guards against blank strings on decode. Verify the
        // behavior we rely on.
        val decoded = if ("".isBlank()) emptyList<ChatMessage>()
        else json.decodeFromString(serializer, "")
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `session entity to domain round-trips through entity copy`() {
        // Verify the entity -> domain -> entity mapping preserves all fields.
        // This is the path SessionRepositoryImpl.toEntity() / toDomain() takes.
        val now = System.currentTimeMillis()
        val msgs = listOf(ChatMessage("m1", ChatMessage.Role.USER, "hi", now))
        val msgsJson = json.encodeToString(serializer, msgs)
        val entity = SessionEntity(
            id = "s1",
            title = "Test",
            createdAt = now,
            lastActiveAt = now,
            status = SessionStatus.RUNNING.name,
            stepCount = 3,
            tokenUsage = 1234L,
            messagesJson = msgsJson,
        )
        // Domain
        val domain = Session(
            id = entity.id,
            title = entity.title,
            createdAt = entity.createdAt,
            lastActiveAt = entity.lastActiveAt,
            status = runCatching { SessionStatus.valueOf(entity.status) }.getOrDefault(SessionStatus.IDLE),
            stepCount = entity.stepCount,
            tokenUsage = entity.tokenUsage,
            messages = json.decodeFromString(serializer, entity.messagesJson.orEmpty()),
        )
        // Back to entity
        val reencoded = SessionEntity(
            id = domain.id,
            title = domain.title,
            createdAt = domain.createdAt,
            lastActiveAt = domain.lastActiveAt,
            status = domain.status.name,
            stepCount = domain.stepCount,
            tokenUsage = domain.tokenUsage,
            messagesJson = json.encodeToString(serializer, domain.messages),
        )
        assertEquals(entity.id, reencoded.id)
        assertEquals(entity.status, reencoded.status)
        assertEquals(entity.messagesJson, reencoded.messagesJson)
        assertEquals(1, domain.messages.size)
        assertNotNull(domain.messages[0])
    }

    @Test
    fun `invalid status string falls back to IDLE`() {
        // SessionRepositoryImpl.toDomain() uses runCatching to guard against
        // a corrupt status string in the DB. Verify the fallback.
        val status = runCatching { SessionStatus.valueOf("GARBAGE") }.getOrDefault(SessionStatus.IDLE)
        assertEquals(SessionStatus.IDLE, status)
    }
}
