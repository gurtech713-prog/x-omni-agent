package com.omniclaw.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val lastActiveAt: Long,
    val status: SessionStatus,
    val stepCount: Int,
    val tokenUsage: Long,
    val messages: List<ChatMessage> = emptyList(),
)

@Serializable
enum class SessionStatus { RUNNING, IDLE, STOPPED, FAILED, DONE }

@Serializable
data class ChatMessage(
    val id: String,
    val role: Role,
    val content: String,
    val timestamp: Long,
    val toolCalls: List<ToolCall> = emptyList(),
    val thoughts: List<String> = emptyList(),
    val toolCallId: String? = null,  // ID of the primary tool call for this message
) {
    @Serializable
    enum class Role { USER, ASSISTANT, TOOL, SYSTEM }
}

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val args: String,
    val result: String? = null,
    val ok: Boolean = true,
    val durationMs: Long = 0L,
)

@Serializable
data class Skill(
    val id: String,
    val name: String,
    val category: SkillCategory,
    val description: String,
    val enabled: Boolean,
    val examples: List<String>,
    val path: String,
)

@Serializable
enum class SkillCategory { SEARCH_APPS, GALLERY_MEDIA, CONFIG, SKILL_MGMT, AUTOMATION }

@Serializable
data class MemoryEntry(
    val id: String,
    val kind: MemoryKind,
    val content: String,
    val createdAt: Long,
    val source: String,
    val pinned: Boolean = false,
) {
    @Serializable
    enum class MemoryKind { WORKING, LONG_TERM, FACT, PREFERENCE, EPISODE }
}

@Serializable
data class ScheduledTask(
    val id: String,
    val title: String,
    val scheduleKind: ScheduleKind,
    val cron: String? = null,
    val intervalMinutes: Int? = null,
    val weekdays: Set<Int> = emptySet(),
    val timeOfDay: String,  // HH:mm
    val enabled: Boolean,
    val prompt: String,
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null,
    val runCount: Int = 0,
    // Self-learning / safety guards:
    //   - onlyWhenScreenOn: defer the task if the screen is off (driving, sleeping)
    //   - quietHoursStart / quietHoursEnd: defer if the current time falls inside
    //     the quiet window (e.g. 23:00-07:00). Format "HH:mm".
    val onlyWhenScreenOn: Boolean = false,
    val quietHoursStart: String = "",
    val quietHoursEnd: String = "",
) {
    @Serializable
    enum class ScheduleKind { INTERVAL, WEEKLY, WEEKDAY }
}

@Serializable
data class LlmUsage(
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
)

/**
 * A learned lesson — the agent's cross-session memory of what worked and what
 * didn't. This is the core of the self-learning loop:
 *
 *   1. EpisodeRecorder logs each (screen, action, outcome) tuple during a session.
 *   2. LearningEngine analyzes the episode on session end and extracts lessons.
 *   3. Before each LLM call, relevant lessons are injected into the system prompt
 *      so the agent avoids known-bad actions and repeats known-good ones.
 *
 * Lessons are keyed by [screenFingerprint] (a coarse hash of the accessibility
 * tree) so the agent can match "similar" screens across sessions without
 * requiring an exact match. [confidence] grows each time the same pattern is
 * observed, so frequently-reinforced lessons float to the top.
 */
@Serializable
data class Lesson(
    val id: String,
    val screenFingerprint: String,
    val actionSignature: String,
    val outcome: LessonOutcome,
    val lessonText: String,
    val confidence: Int = 1,
    val createdAt: Long,
    val lastSeenAt: Long,
    val sourceSessionId: String? = null,
) {
    @Serializable
    enum class LessonOutcome { SUCCESS, FAILURE, LOOP, NEUTRAL }
}
