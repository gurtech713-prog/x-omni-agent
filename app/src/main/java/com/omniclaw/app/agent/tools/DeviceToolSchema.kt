package com.omniclaw.app.agent.tools

import com.omniclaw.app.data.model.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Hermes-style structured tool schema for device automation.
 *
 * Historically the agent scraped free-text `ACTION: tap(x,y)` lines with regex.
 * That path fails OPEN: a malformed coordinate parse silently defaulted to a
 * real tap at (0,0) (see the old `parseDeviceAction` `?: 0`). With structured
 * function-calling the LLM declares this tool and returns a validated JSON
 * object, and [parse] is FAIL-CLOSED — any missing required coordinate or
 * malformed payload yields a null action, which the agent reports as a grounded
 * error instead of dispatching a bogus gesture.
 */
object DeviceToolSchema {

    const val TOOL_NAME = "device_action"

    /** JSON-Schema for the tool parameters (shared by the OpenAI + Gemini envelopes). */
    private val PARAMETERS_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "thought": {"type": "string", "description": "One-sentence reasoning for this action."},
            "action": {"type": "string", "enum": ["tap","swipe","type","launch","back","home","screenshot","done"], "description": "The single device action to perform."},
            "x":  {"type": "integer", "description": "Tap / swipe-start x in screen pixels."},
            "y":  {"type": "integer", "description": "Tap / swipe-start y in screen pixels."},
            "x2": {"type": "integer", "description": "Swipe-end x in screen pixels."},
            "y2": {"type": "integer", "description": "Swipe-end y in screen pixels."},
            "text": {"type": "string", "description": "Text to enter (action=type)."},
            "package": {"type": "string", "description": "App package to open (action=launch)."}
          },
          "required": ["action"]
        }
    """.trimIndent()

    /** The tool declaration handed to the LLM clients. */
    val SPEC = ToolSpec(
        name = TOOL_NAME,
        description = "Perform exactly one Android UI action. Use tap/swipe with pixel coordinates taken from the screen observation, type to enter text into the focused field, launch to open an app by package, back/home/screenshot for navigation, or done when the user's request is satisfied.",
        parametersSchema = PARAMETERS_SCHEMA,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Result of parsing one structured tool call.
     *  - [done]   = the model signalled task completion (action == "done").
     *  - [action] = the validated [DeviceAction], or null when the payload is
     *               malformed / missing required fields (fail-closed) OR done.
     *  - [valid]  = false ONLY for a malformed payload (distinguishes "model said
     *               done" from "model sent garbage we must reject").
     */
    data class Parsed(
        val thought: String,
        val action: DeviceAction?,
        val done: Boolean,
        val valid: Boolean,
    )

    /**
     * Fail-closed parse of an [com.omniclaw.app.data.model.LlmToolCall.arguments]
     * JSON object. Callers MUST treat `valid == false` as an error to report back
     * to the model, never as a reason to dispatch a default gesture.
     */
    fun parse(argumentsJson: String): Parsed {
        val obj: JsonObject =
            runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
                ?: return Parsed("", null, done = false, valid = false)

        val thought = obj["thought"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val action = obj["action"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim()
            ?: return Parsed(thought, null, done = false, valid = false)

        fun int(key: String): Int? = obj[key]?.jsonPrimitive?.intOrNull

        if (action == "done") return Parsed(thought, null, done = true, valid = true)

        val deviceAction: DeviceAction? = when (action) {
            "tap" -> {
                val x = int("x"); val y = int("y")
                if (x == null || y == null) null else DeviceAction.Tap(x, y)
            }
            "swipe" -> {
                val x = int("x"); val y = int("y"); val x2 = int("x2"); val y2 = int("y2")
                if (x == null || y == null || x2 == null || y2 == null) null
                else DeviceAction.Swipe(x, y, x2, y2)
            }
            "type" -> obj["text"]?.jsonPrimitive?.contentOrNull?.let { DeviceAction.Type(it) }
            "launch" -> obj["package"]?.jsonPrimitive?.contentOrNull?.let { DeviceAction.Launch(it) }
            "back" -> DeviceAction.Back
            "home" -> DeviceAction.Home
            "screenshot" -> DeviceAction.Screenshot
            else -> null
        }

        return Parsed(thought, deviceAction, done = false, valid = deviceAction != null)
    }

    /**
     * Render a validated [DeviceAction] back into the canonical legacy
     * `ACTION: <line>` string (e.g. `tap(540,1200)`). Keeps the structured
     * tool-calling path compatible with the old free-text scraper format that
     * downstream logging / replay still expects. Returns null for a null
     * action or [DeviceAction.NoOp] (nothing to dispatch), which callers
     * surface as an invalid action rather than a bogus gesture. (C-02)
     */
    fun toActionLine(action: DeviceAction?): String? = when (action) {
        null -> null
        DeviceAction.NoOp -> null
        is DeviceAction.Tap -> "tap(${action.x},${action.y})"
        is DeviceAction.Swipe -> "swipe(${action.x1},${action.y1},${action.x2},${action.y2})"
        is DeviceAction.Type -> "type(${action.text})"
        is DeviceAction.Launch -> "launch(${action.packageName})"
        DeviceAction.Back -> "back"
        DeviceAction.Home -> "home"
        DeviceAction.Screenshot -> "screenshot"
    }
}
