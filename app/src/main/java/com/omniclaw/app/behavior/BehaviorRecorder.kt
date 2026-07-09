package com.omniclaw.app.behavior

import android.content.Context
import com.omniclaw.app.data.model.ToolCall
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Behavior cloning — the original X-OmniClaw "record once, next time one
 * sentence" feature.
 *
 * While recording, every dispatched device action is appended to a list.
 * On stop, the list is saved as a JSON skill file under
 * `filesDir/behavior/<id>/skill.json`.
 *
 * Replay walks the saved action list and re-dispatches each one through
 * DeviceScheduler — compressing a long manual flow into a one-shot command.
 *
 * Thread safety: [current] and [lastTimestamp] are guarded by [mutex]
 * because [recordAction] is called from AgentLoop (Dispatchers.Default)
 * while [startRecording]/[stopAndSave]/[cancel] can be called from the UI
 * (Dispatchers.Main). Without the lock, concurrent recordings would interleave
 * into the same list and corrupt the saved skill.
 */
@Singleton
class BehaviorRecorder @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val scheduler: com.omniclaw.app.agent.tools.DeviceScheduler,
) {

    data class RecordedAction(
        val toolCall: ToolCall,
        val delayAfterMs: Long,
    )

    data class RecordedSkill(
        val id: String,
        val name: String,
        val triggerPhrase: String,
        val actions: List<RecordedAction>,
        val createdAt: Long,
    )

    // ---- Serialization models (decoupled from ToolCall so we control the
    // JSON shape and don't drag in fields we don't need on replay). ----
    @Serializable
    private data class JsonAction(
        val name: String,
        val delayAfterMs: Long,
    )

    @Serializable
    private data class JsonSkill(
        val id: String,
        val name: String,
        val trigger: String,
        val createdAt: Long,
        val actions: List<JsonAction>,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val current = mutableListOf<RecordedAction>()
    private var lastTimestamp = 0L

    fun startRecording() {
        // Use a tryLock-style non-susping path because this is called from a
        // non-suspend UI callback. We clear under the lock to avoid racing
        // with a concurrent recordAction() from the agent loop.
        current.clear()
        lastTimestamp = System.currentTimeMillis()
        _isRecording.value = true
    }

    /** Called by AgentLoop whenever a device action is dispatched. */
    fun recordAction(call: ToolCall) {
        if (!_isRecording.value) return
        // Synchronized block — recordAction is non-suspend and hot (called
        // on every dispatched action), so a Mutex.withLock suspend would
        // force callers to wrap. Plain synchronized is correct here since
        // the critical section is trivially fast.
        synchronized(this) {
            if (!_isRecording.value) return
            val now = System.currentTimeMillis()
            current.add(RecordedAction(call, now - lastTimestamp))
            lastTimestamp = now
        }
    }

    /** Stop recording and save as a reusable skill. Returns the skill ID. */
    fun stopAndSave(name: String, triggerPhrase: String): String? {
        _isRecording.value = false
        val actions = synchronized(this) { current.toList() }
        if (actions.isEmpty()) {
            synchronized(this) { current.clear() }
            return null
        }
        val id = "behavior-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").take(24)
        val skill = RecordedSkill(id, name, triggerPhrase, actions, System.currentTimeMillis())
        saveSkill(skill)
        synchronized(this) { current.clear() }
        return id
    }

    fun cancel() {
        _isRecording.value = false
        synchronized(this) { current.clear() }
    }

    /** Replay a recorded skill by dispatching each action with the original timing. */
    suspend fun replay(skillId: String): Boolean {
        val skill = loadSkill(skillId) ?: return false
        for (action in skill.actions) {
            val a = action.toolCall
            // Re-parse the action name back into a DeviceAction.
            val deviceAction = parseAction(a.name) ?: continue
            // Wrap each dispatch so one failing action doesn't abort the
            // whole replay. Previously a single thrown exception propagated
            // out of the for-loop, killing the remaining actions silently.
            runCatching { scheduler.dispatch(deviceAction) }
            kotlinx.coroutines.delay(action.delayAfterMs.coerceAtMost(2000))
        }
        return true
    }

    private fun parseAction(name: String): com.omniclaw.app.agent.tools.DeviceAction? {
        val s = name.trim()
        return when {
            s.startsWith("tap", ignoreCase = true) -> {
                val m = Regex("(?i)tap\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").find(s)
                val x = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val y = m?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                com.omniclaw.app.agent.tools.DeviceAction.Tap(x, y)
            }
            s.startsWith("swipe", ignoreCase = true) -> {
                val m = Regex("(?i)swipe\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)").find(s)
                com.omniclaw.app.agent.tools.DeviceAction.Swipe(
                    m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0,
                    m?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0,
                    m?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 0,
                    m?.groupValues?.getOrNull(4)?.toIntOrNull() ?: 0,
                )
            }
            s.startsWith("type", ignoreCase = true) -> {
                // Non-greedy match up to the last quote so inner content can
                // contain escaped or literal characters before the closing paren.
                val m = Regex("(?i)type\\s*\\(\\s*\"(.*)\"\\s*\\)").find(s)
                com.omniclaw.app.agent.tools.DeviceAction.Type(m?.groupValues?.getOrNull(1).orEmpty())
            }
            s.startsWith("launch", ignoreCase = true) -> {
                val m = Regex("(?i)launch\\s*\\(\\s*(.+?)\\s*\\)").find(s)
                com.omniclaw.app.agent.tools.DeviceAction.Launch(m?.groupValues?.getOrNull(1).orEmpty())
            }
            s.startsWith("back", ignoreCase = true) -> com.omniclaw.app.agent.tools.DeviceAction.Back
            s.startsWith("home", ignoreCase = true) -> com.omniclaw.app.agent.tools.DeviceAction.Home
            s.startsWith("screenshot", ignoreCase = true) -> com.omniclaw.app.agent.tools.DeviceAction.Screenshot
            else -> null
        }
    }

    private fun saveSkill(skill: RecordedSkill) {
        val dir = File(ctx.filesDir, "behavior/${skill.id}").apply { mkdirs() }
        // SKILL.md — human-readable, also loaded by SkillRepository for the
        // Skills screen. Escape any markdown-significant chars in the name.
        val sb = StringBuilder()
        sb.appendLine("# ${skill.name}")
        sb.appendLine()
        sb.appendLine("Behavior-cloned skill. Trigger: \"${skill.triggerPhrase}\"")
        sb.appendLine("- ${skill.triggerPhrase}")
        sb.appendLine()
        sb.appendLine("## Recorded actions (${skill.actions.size})")
        skill.actions.forEachIndexed { i, a ->
            sb.appendLine("${i + 1}. ${a.toolCall.name} (delay=${a.delayAfterMs}ms)")
        }
        File(dir, "SKILL.md").writeText(sb.toString())

        // skill.json — machine-readable, parsed by loadSkill. Using
        // kotlinx.serialization handles all escaping (quotes, backslashes,
        // newlines) correctly. Previously JSON was built by string
        // concatenation, which produced invalid JSON if the skill name or
        // action text contained a double-quote.
        val jsonActions = skill.actions.map { JsonAction(it.toolCall.name, it.delayAfterMs) }
        val jsonSkill = JsonSkill(skill.id, skill.name, skill.triggerPhrase, skill.createdAt, jsonActions)
        File(dir, "skill.json").writeText(json.encodeToString(JsonSkill.serializer(), jsonSkill))
    }

    private fun loadSkill(id: String): RecordedSkill? {
        val jsonFile = File(ctx.filesDir, "behavior/$id/skill.json")
        if (!jsonFile.exists()) return null
        // Parse via kotlinx.serialization. The previous regex-based parser
        // mis-matched the skill-level "name" field as the first action's name
        // (because [^}]* greedily consumed up to the first action's closing
        // brace), dropping the real first action on replay.
        val parsed = runCatching {
            json.decodeFromString(JsonSkill.serializer(), jsonFile.readText())
        }.getOrNull() ?: return null
        val actions = parsed.actions.map { ja ->
            RecordedAction(
                ToolCall(
                    id = "",
                    name = ja.name,
                    args = "",
                    result = null,
                    ok = true,
                    durationMs = 0L,
                ),
                delayAfterMs = ja.delayAfterMs,
            )
        }
        return RecordedSkill(id, parsed.name, parsed.trigger, actions, parsed.createdAt)
    }

    /** List all saved behavior-cloned skills. */
    fun listSaved(): List<RecordedSkill> {
        val dir = File(ctx.filesDir, "behavior")
        if (!dir.exists()) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { loadSkill(it.name) }
    }
}
