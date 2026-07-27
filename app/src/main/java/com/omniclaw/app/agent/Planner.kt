package com.omniclaw.app.agent

import com.omniclaw.app.data.llm.LlmClient
import com.omniclaw.app.data.llm.UnifiedLlmClient
import com.omniclaw.app.data.prefs.ModelConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hermes-style plan-then-act planner.
 *
 * The base agent loop is purely reactive (ReAct): it picks one action per step
 * with no explicit goal decomposition. This component adds an OPTIONAL planning
 * layer — before acting, the model emits a short ordered plan; the loop checks
 * steps off as they succeed and REPLANS when it gets stuck or loops. Planning is
 * best-effort: any failure to produce or parse a plan returns null and the agent
 * simply falls back to reactive behaviour, so this never blocks a session.
 */
@Singleton
class Planner @Inject constructor(
    private val llm: UnifiedLlmClient,
) {
    data class PlanStep(val id: Int, val intent: String, val done: Boolean = false) {
        val isDone: Boolean get() = done
        // A-L3 FIX: `done` is now a `val` (was `private var`) and `markDone()`
        // returns a NEW copy with `done = true` instead of mutating `this`.
        // Mutating a data-class field from inside a `fun` was a hidden side-
        // effect that broke `copy()` semantics, made PlanStep instances non-
        // safely-shareable across coroutines, and surprised the planner's
        // prompt-renderer which cached references. Callers MUST capture the
        // returned copy (see e.g. AgentLoop's `plan.markNextStepDone()`).
        fun markDone(): PlanStep = copy(done = true)
    }

    data class Plan(val goal: String, val steps: MutableList<PlanStep>) {
        val nextStep: PlanStep? get() = steps.firstOrNull { !it.isDone }
        val isComplete: Boolean get() = steps.isNotEmpty() && steps.all { it.isDone }

        // A-L3 FIX: helper that replaces the next-undone step in-place with
        // its `markDone()` copy. The previous caller pattern
        // `plan?.nextStep?.let { it.markDone() }` discarded the returned copy
        // — which was a no-op once markDone stopped mutating `this`. Routing
        // through this helper ensures the new copy is actually stored back
        // into the steps list so the next `nextStep` lookup sees the update.
        fun markNextStepDone() {
            val idx = steps.indexOfFirst { !it.isDone }
            if (idx >= 0) steps[idx] = steps[idx].markDone()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Produce an initial plan for [goal] given the current screen [observation]. */
    suspend fun makePlan(cfg: ModelConfig, goal: String, observation: String): Plan? =
        request(cfg, goal, observation, prior = null, failureReason = null)

    /** Revise [prior] after a failure, telling the model WHY it failed. */
    suspend fun replan(cfg: ModelConfig, goal: String, observation: String, prior: Plan, failureReason: String): Plan? =
        request(cfg, goal, observation, prior, failureReason)

    private suspend fun request(
        cfg: ModelConfig,
        goal: String,
        observation: String,
        prior: Plan?,
        failureReason: String?,
    ): Plan? = runCatching {
        val system = buildString {
            appendLine("You are a planning module for an Android automation agent.")
            appendLine("Given the user's goal and the current screen, output a SHORT ordered plan as a")
            appendLine("JSON object: {\"steps\": [\"<step 1 intent>\", \"<step 2 intent>\", ...]}")
            appendLine("Use 2-6 concrete steps. No commentary, no markdown — JSON only.")
            if (prior != null) {
                appendLine("The previous plan was: " + prior.steps.joinToString("; ") { it.intent })
                appendLine("It failed because: $failureReason. Produce a REVISED plan that avoids that failure.")
            }
        }
        val messages = listOf(
            LlmClient.Message(role = "system", content = system),
            LlmClient.Message(role = "user", content = "GOAL: $goal\n\nCURRENT SCREEN:\n${observation.take(4000)}"),
        )
        val result = llm.complete(
            provider = cfg.provider,
            baseUrl = cfg.baseUrl,
            apiKey = cfg.apiKey,
            model = cfg.model,
            messages = messages,
            temperature = 0.2f,
            maxTokens = 512,
        )
        parsePlan(goal, result.text)
    }.getOrNull()

    private fun parsePlan(goal: String, raw: String): Plan? {
        // Tolerant extraction: drop code fences, then take the first {...} block.
        val cleaned = raw.substringAfter("```").substringBefore("```").ifBlank { raw }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj: JsonObject =
            runCatching { json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject }.getOrNull()
                ?: return null
        val arr: JsonArray = obj["steps"]?.jsonArray ?: return null
        val steps = arr.mapIndexedNotNull { i, el ->
            val intent = (el as? JsonPrimitive)?.content?.trim().orEmpty()
            if (intent.isEmpty()) null else PlanStep(id = i + 1, intent = intent)
        }
        return if (steps.isEmpty()) null else Plan(goal, steps.toMutableList())
    }

    /** Render the plan for injection into the agent's system prompt. */
    fun renderForPrompt(plan: Plan?): String? {
        if (plan == null || plan.steps.isEmpty()) return null
        return buildString {
            appendLine("CURRENT PLAN (goal: ${plan.goal}):")
            plan.steps.forEach { s ->
                appendLine("  ${if (s.isDone) "[x]" else "[ ]"} ${s.id}. ${s.intent}")
            }
            plan.nextStep?.let { appendLine("Focus on step ${it.id} next.") }
        }.trimEnd()
    }
}
