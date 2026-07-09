package com.omniclaw.app.agent.learning

import android.content.Context
import android.util.Log
import com.omniclaw.app.data.local.LessonDao
import com.omniclaw.app.data.local.LessonEntity
import com.omniclaw.app.data.llm.LlmClient
import com.omniclaw.app.data.llm.UnifiedLlmClient
import com.omniclaw.app.data.model.Lesson
import com.omniclaw.app.data.model.Lesson.LessonOutcome
import com.omniclaw.app.data.prefs.SettingsRepository
import com.omniclaw.app.logging.AgentLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The self-learning engine — the "Hermes" brain of the agent.
 *
 * Three responsibilities:
 *
 *  1. **Lesson injection** — before each LLM call, [lessonsForPrompt] queries
 *     the lesson store for lessons matching the current screen fingerprint,
 *     and returns them as a formatted string for injection into the system
 *     prompt. This lets the agent learn from past mistakes without re-deriving
 *     them each session.
 *
 *  2. **Episode reflection** — on session end, [reflectOnEpisode] feeds the
 *     full trajectory to the LLM and asks it to extract 1-3 concise lessons.
 *     The LLM writes lessons in natural language (e.g. "tap(500,800) on the
 *     Reddit home feed opens the search bar — use it to type queries"), which
 *     are persisted to the lesson store.
 *
 *  3. **Lesson reinforcement** — [recordDirectLesson] is called for each step
 *     immediately after verification, so we capture concrete (fingerprint,
 *     action, outcome) tuples even before the LLM reflection runs. If the same
 *     tuple is seen again, its confidence is incremented instead of creating
 *     a duplicate.
 *
 * The reflection step (2) is what makes this "self-learning" rather than just
 * "logging": the agent actively reasons about its experience and writes its own
 * guidance for future runs.
 */
@Singleton
class LearningEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val lessonDao: LessonDao,
    private val llm: UnifiedLlmClient,
    private val settings: SettingsRepository,
    private val logger: AgentLogger,
    private val recorder: EpisodeRecorder,
) {

    /**
     * Query lessons relevant to the current screen and format them for
     * injection into the system prompt.
     *
     * Returns null if no lessons match (first run, or no failures/successes
     * recorded for this screen yet). The AgentLoop appends the result to the
     * system prompt's "Constraints" section.
     */
    suspend fun lessonsForPrompt(screenFingerprint: String): String? = withContext(Dispatchers.IO) {
        val lessons = runCatching { lessonDao.forScreen(screenFingerprint, limit = 5) }
            .getOrDefault(emptyList())
        if (lessons.isEmpty()) return@withContext null

        buildString {
            appendLine()
            appendLine("Learned lessons for this screen (from past sessions):")
            lessons.forEach { l ->
                val icon = when (l.outcome) {
                    "FAILURE" -> "AVOID"
                    "SUCCESS" -> "USE"
                    "LOOP" -> "LOOP"
                    else -> "NOTE"
                }
                appendLine("- [$icon conf=${l.confidence}] ${l.lessonText}")
            }
            appendLine("Apply these lessons: avoid AVOID actions, prefer USE actions.")
        }
    }

    /**
     * Record a direct (fingerprint, action, outcome) lesson immediately after
     * a step is verified. This captures concrete experience without waiting
     * for LLM reflection. If the same tuple already exists, increment its
     * confidence instead of duplicating.
     */
    suspend fun recordDirectLesson(
        sessionId: String,
        screenFingerprint: String,
        actionSignature: String,
        outcome: LessonOutcome,
        observation: String,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = runCatching {
            lessonDao.findExisting(screenFingerprint, actionSignature, outcome.name)
        }.getOrNull()
        if (existing != null) {
            runCatching { lessonDao.reinforce(existing.id, now) }
            return@withContext
        }
        val lessonText = when (outcome) {
            LessonOutcome.FAILURE ->
                "Action '$actionSignature' failed on this screen. Try a different approach."
            LessonOutcome.SUCCESS ->
                "Action '$actionSignature' succeeded on this screen."
            LessonOutcome.LOOP ->
                "Action '$actionSignature' caused a loop on this screen. Don't repeat it."
            LessonOutcome.NEUTRAL ->
                "Action '$actionSignature' was tried on this screen."
        }
        val entity = LessonEntity(
            id = UUID.randomUUID().toString().take(12),
            screenFingerprint = screenFingerprint,
            actionSignature = actionSignature,
            outcome = outcome.name,
            lessonText = lessonText,
            confidence = 1,
            createdAt = now,
            lastSeenAt = now,
            sourceSessionId = sessionId,
        )
        runCatching { lessonDao.upsert(entity) }
    }

    /**
     * Reflect on a completed episode and extract lessons using the LLM.
     *
     * Called by AgentLoop when a session reaches DONE / FAILED / max-steps.
     * Feeds the trajectory (observations + actions + outcomes) to the LLM
     * with a reflection prompt, parses the returned lessons, and persists them.
     *
     * This is the "Hermes" step — the agent reasons about its own experience
     * and writes natural-language guidance for future runs.
     */
    suspend fun reflectOnEpisode(sessionId: String) = withContext(Dispatchers.IO) {
        val steps = recorder.getEpisode(sessionId) ?: return@withContext
        if (steps.size < 2) return@withContext  // nothing to learn from a 1-step session
        val userPrompt = recorder.getUserPrompt(sessionId) ?: ""
        val finalStatus = recorder.getFinalStatus(sessionId) ?: "UNKNOWN"

        val cfg = runCatching { settings.modelConfig.first() }.getOrNull() ?: return@withContext
        // Skip reflection if no API key is configured (e.g. first run, or LiteRT
        // without a working model — reflection needs a capable LLM).
        if (cfg.provider != com.omniclaw.app.data.prefs.LlmProvider.LITERT &&
            cfg.apiKey.isBlank()) return@withContext

        val trajectory = formatTrajectoryForReflection(steps, userPrompt, finalStatus)
        val reflectionPrompt = buildReflectionPrompt(trajectory, finalStatus)

        runCatching {
            val result = llm.complete(
                provider = cfg.provider,
                baseUrl = cfg.baseUrl,
                apiKey = cfg.apiKey,
                model = cfg.model,
                messages = listOf(
                    LlmClient.Message(
                        "system",
                        "You are a reflection engine. Analyze the agent's episode and extract " +
                            "concise, actionable lessons. Output one lesson per line, prefixed " +
                            "with [AVOID] or [USE]. Be specific about screen context and actions. " +
                            "Maximum 3 lessons. No preamble."
                    ),
                    LlmClient.Message("user", reflectionPrompt),
                ),
                temperature = 0.1f,
                maxTokens = 300,
            )
            parseAndPersistLessons(result.text, sessionId, steps)
            logger.logInfo(sessionId, 0, "reflection: extracted lessons from ${steps.size}-step episode ($finalStatus)")
        }.onFailure { e ->
            Log.w(TAG, "Reflection failed for session $sessionId: ${e.message}")
            // Even if LLM reflection fails, the direct lessons recorded during
            // the session are already persisted — the agent still learns.
        }

        // Clean up the in-memory episode after reflection.
        recorder.clear(sessionId)

        // Periodically prune stale low-confidence lessons to prevent unbounded growth.
        runCatching {
            val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000  // 30 days ago
            lessonDao.pruneStale(minConfidence = 2, before = cutoff)
        }
    }

    /**
     * Auto-create a SKILL.md from a successful multi-step episode.
     *
     * If the session reached DONE with >3 steps, the trajectory is likely
     * reusable. We ask the LLM to summarize it as a SKILL.md and persist it
     * under filesDir/skills/auto-<hash>/SKILL.md. The skill becomes available
     * on the next Skills screen refresh.
     */
    suspend fun maybeAutoCreateSkill(sessionId: String) = withContext(Dispatchers.IO) {
        val steps = recorder.getEpisode(sessionId) ?: return@withContext
        val finalStatus = recorder.getFinalStatus(sessionId) ?: return@withContext
        if (finalStatus != "DONE") return@withContext
        if (steps.size < 3) return@withContext  // too short to be a reusable skill

        val userPrompt = recorder.getUserPrompt(sessionId) ?: ""
        val cfg = runCatching { settings.modelConfig.first() }.getOrNull() ?: return@withContext
        if (cfg.provider != com.omniclaw.app.data.prefs.LlmProvider.LITERT &&
            cfg.apiKey.isBlank()) return@withContext

        val trajectory = formatTrajectoryForSkill(steps, userPrompt)
        runCatching {
            val result = llm.complete(
                provider = cfg.provider,
                baseUrl = cfg.baseUrl,
                apiKey = cfg.apiKey,
                model = cfg.model,
                messages = listOf(
                    LlmClient.Message(
                        "system",
                        "Draft a SKILL.md for an Android agent skill based on a successful trajectory. " +
                            "Format: # Name\\n\\nDescription.\\n- Example utterance.\\n\\n## Steps\\n- numbered steps. " +
                            "Be concise. The skill name should be 2-4 words derived from the user's request."
                    ),
                    LlmClient.Message("user", trajectory),
                ),
                temperature = 0.2f,
                maxTokens = 400,
            )
            val content = result.text
            val hash = userPrompt.hashCode().toString(16).take(6)
            val id = "auto-$hash"
            val dir = java.io.File(ctx.filesDir, "skills/$id").apply { mkdirs() }
            java.io.File(dir, "SKILL.md").writeText(content)
            logger.logInfo(sessionId, 0, "auto-skill: created $id from ${steps.size}-step trajectory")
        }.onFailure { e ->
            Log.w(TAG, "Auto-skill creation failed for session $sessionId: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private fun formatTrajectoryForReflection(
        steps: List<EpisodeRecorder.EpisodeStep>,
        userPrompt: String,
        finalStatus: String,
    ): String = buildString {
        appendLine("User request: $userPrompt")
        appendLine("Final status: $finalStatus")
        appendLine("Steps (${steps.size}):")
        steps.forEach { s ->
            appendLine("  ${s.stepIndex}. [${s.outcome}] ${s.action}")
            appendLine("     screen: ${s.observation.take(100)}")
        }
    }

    private fun formatTrajectoryForSkill(
        steps: List<EpisodeRecorder.EpisodeStep>,
        userPrompt: String,
    ): String = buildString {
        appendLine("User request: \"$userPrompt\"")
        appendLine("Successful trajectory:")
        steps.filter { it.outcome == LessonOutcome.SUCCESS }.forEachIndexed { i, s ->
            appendLine("${i + 1}. ${s.action}")
        }
    }

    private fun buildReflectionPrompt(trajectory: String, finalStatus: String): String = buildString {
        appendLine("Analyze this agent episode and extract lessons.")
        appendLine("For each lesson, specify the screen context, the action, and whether to AVOID or USE it.")
        appendLine()
        appendLine(trajectory)
        appendLine()
        if (finalStatus == "FAILED") {
            appendLine("The episode FAILED. Focus on what went wrong and what to avoid next time.")
        } else {
            appendLine("The episode SUCCEEDED. Focus on what worked and what to repeat.")
        }
        appendLine()
        appendLine("Output format (one per line):")
        appendLine("[AVOID] <action> on <screen context> because <reason>")
        appendLine("[USE] <action> on <screen context> because <reason>")
    }

    /**
     * Parse the LLM's reflection output and persist each lesson.
     *
     * Expected format (one per line):
     *   [AVOID] tap(500,800) on Reddit home feed because it opens an ad
     *   [USE] swipe(540,1500,540,500) on Reddit home feed to scroll to next posts
     *
     * Lines that don't match are skipped. The action signature is extracted
     * from the lesson text; the screen fingerprint is approximated from the
     * first step's fingerprint in the episode.
     */
    private suspend fun parseAndPersistLessons(
        llmOutput: String,
        sessionId: String,
        steps: List<EpisodeRecorder.EpisodeStep>,
    ) {
        val defaultFingerprint = steps.firstOrNull()?.screenFingerprint ?: "unknown"
        val lessonRegex = Regex("\\[(AVOID|USE|LOOP|NOTE)\\]\\s*(.+)", RegexOption.IGNORE_CASE)
        llmOutput.lines().forEach { line ->
            val m = lessonRegex.find(line.trim()) ?: return@forEach
            val tag = m.groupValues[1].uppercase()
            val text = m.groupValues[2].trim()
            if (text.isBlank()) return@forEach

            // Try to extract an action signature from the lesson text.
            // Look for patterns like "tap(x,y)", "swipe(...)", "launch(...)", "skill:...".
            val actionSig = extractActionSignature(text) ?: "general"

            val outcome = when (tag) {
                "AVOID" -> LessonOutcome.FAILURE
                "USE" -> LessonOutcome.SUCCESS
                "LOOP" -> LessonOutcome.LOOP
                else -> LessonOutcome.NEUTRAL
            }
            val now = System.currentTimeMillis()
            // Check if a lesson with the same fingerprint + action + outcome exists.
            val existing = runCatching {
                lessonDao.findExisting(defaultFingerprint, actionSig, outcome.name)
            }.getOrNull()
            if (existing != null) {
                // Update the lesson text with the LLM's (richer) description and reinforce.
                runCatching {
                    lessonDao.upsert(existing.copy(
                        lessonText = text,
                        confidence = existing.confidence + 1,
                        lastSeenAt = now,
                    ))
                }
            } else {
                val entity = LessonEntity(
                    id = UUID.randomUUID().toString().take(12),
                    screenFingerprint = defaultFingerprint,
                    actionSignature = actionSig,
                    outcome = outcome.name,
                    lessonText = text,
                    confidence = 1,
                    createdAt = now,
                    lastSeenAt = now,
                    sourceSessionId = sessionId,
                )
                runCatching { lessonDao.upsert(entity) }
            }
        }
    }

    /** Extract a normalized action signature from a lesson text, or null. */
    private fun extractActionSignature(text: String): String? {
        // Match tap(x,y), swipe(x1,y1,x2,y2), type("..."), launch(...), back, home, skill:...
        val patterns = listOf(
            Regex("(?i)tap\\s*\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\)"),
            Regex("(?i)swipe\\s*\\(\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*\\)"),
            Regex("(?i)launch\\s*\\([^)]+\\)"),
            Regex("(?i)skill:[a-z\\-]+"),
            Regex("(?i)\\b(back|home|screenshot)\\b"),
        )
        for (p in patterns) {
            val match = p.find(text)
            if (match != null) return match.value.trim().lowercase().replace(Regex("\\s+"), "")
        }
        return null
    }

    companion object {
        private const val TAG = "LearningEngine"
    }
}
