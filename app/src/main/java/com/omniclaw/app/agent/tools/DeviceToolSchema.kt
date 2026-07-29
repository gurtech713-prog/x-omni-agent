package com.omniclaw.app.agent.tools

import com.omniclaw.app.data.model.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

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
            "action": {"type": "string", "enum": ["tap","click","swipe","scroll","drag","type","launch","back","home","screenshot","done"], "description": "The single device action to perform. 'click' is an alias for 'tap'."},
            "x":  {"type": "integer", "description": "Tap / swipe-start x in screen pixels."},
            "y":  {"type": "integer", "description": "Tap / swipe-start y in screen pixels."},
            "x2": {"type": "integer", "description": "Swipe-end x in screen pixels."},
            "y2": {"type": "integer", "description": "Swipe-end y in screen pixels."},
            "direction": {"type": "string", "enum": ["up","down","left","right"], "description": "Scroll direction (action=scroll). The executor computes the swipe path from the real screen size."},
            "amount": {"type": "number", "description": "Scroll distance as a fraction of the screen (0.1-0.5). Default 0.35."},
            "text": {"type": "string", "description": "Text to enter (action=type)."},
            "package": {"type": "string", "description": "App package to open (action=launch)."}
          },
          "required": ["action"]
        }
    """.trimIndent()

    /** The tool declaration handed to the LLM clients. */
    val SPEC = ToolSpec(
        name = TOOL_NAME,
        description = "Perform exactly one Android UI action. Use tap/swipe with pixel coordinates taken from the screen observation, scroll with a direction (up/down/left/right) to scroll without needing exact coordinates, type to enter text into the focused field, launch to open an app by package, back/home/screenshot for navigation, or done when the user's request is satisfied.",
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
        /** Human-readable error message when [valid] is false. Tells the LLM
         *  exactly what was wrong so it can correct its next tool call. */
        val error: String? = null,
    )

    /**
     * Fail-closed parse of an [com.omniclaw.app.data.model.LlmToolCall.arguments]
     * JSON object. Callers MUST treat `valid == false` as an error to report back
     * to the model, never as a reason to dispatch a default gesture.
     *
     * CRITICAL FIX (tap/launch not executing): the parser now accepts ALTERNATE
     * field names for the `package` parameter. The JSON schema declares the
     * field as `"package"`, but many LLMs (especially smaller models) send
     * `"packageName"` instead (matching the Kotlin/Java convention). The
     * previous parser only checked `obj["package"]` — if the LLM sent
     * `{"action":"launch","packageName":"camera"}`, the parse returned null,
     * the action became "ACTION: invalid", and launch was never dispatched.
     * Now we check both field names.
     *
     * Same fix applied to coordinates: some LLMs send `"x"` as a float
     * (e.g. `540.0`) instead of an integer. The `intOrNull` extension on
     * `JsonPrimitive` returns null for `"540.0"` — so we now also try
     * `contentOrNull?.toFloatOrNull()?.toInt()` as a fallback.
     */
    fun parse(argumentsJson: String): Parsed {
        val obj: JsonObject =
            runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
                ?: return Parsed("", null, done = false, valid = false, error = "Could not parse JSON: ${argumentsJson.take(200)}")

        val thought = (obj["thought"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val action = (obj["action"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.trim()
            ?: return Parsed(thought, null, done = false, valid = false, error = "Missing 'action' field. Required: action (string).")

        // LENIENT integer reader: accepts int, float, AND string-encoded numbers.
        // Also checks nested objects for common alternate layouts:
        //   {x: 540, y: 1200}                        — standard
        //   {x: "540", y: "1200"}                    — string-encoded
        //   {coordinates: {x: 540, y: 1200}}         — nested "coordinates"
        //   {position: {x: 540, y: 1200}}            — nested "position"
        //   {point: {x: 540, y: 1200}}               — nested "point"
        //   {location: {x: 540, y: 1200}}            — nested "location"
        // This fixes the "parameter formatting problems" error that many LLMs
        // trigger by using non-standard argument shapes.
        fun int(key: String): Int? {
            // Top-level
            (obj[key] as? JsonPrimitive)?.let { prim ->
                prim.intOrNull?.let { return it }
                return prim.contentOrNull?.toFloatOrNull()?.toInt()
            }
            // Nested in common sub-objects
            for (nestedKey in listOf("coordinates", "position", "point", "location", "coords")) {
                val nested = obj[nestedKey] as? JsonObject ?: continue
                (nested[key] as? JsonPrimitive)?.let { prim ->
                    prim.intOrNull?.let { return it }
                    return prim.contentOrNull?.toFloatOrNull()?.toInt()
                }
            }
            return null
        }

        if (action == "done") return Parsed(thought, null, done = true, valid = true)

        val deviceAction: DeviceAction? = when (action) {
            "tap", "click" -> {
                val x = int("x"); val y = int("y")
                if (x == null || y == null) {
                    return Parsed(thought, null, done = false, valid = false,
                        error = "tap/click requires 'x' and 'y' integer fields. Got: x=$x, y=$y. Send like: {\"action\":\"tap\",\"x\":540,\"y\":1200}")
                }
                DeviceAction.Tap(x, y)
            }
            "swipe" -> {
                val x = int("x"); val y = int("y"); val x2 = int("x2"); val y2 = int("y2")
                if (x == null || y == null || x2 == null || y2 == null) {
                    return Parsed(thought, null, done = false, valid = false,
                        error = "swipe requires 'x','y','x2','y2' integer fields. Got: x=$x, y=$y, x2=$x2, y2=$y2.")
                }
                DeviceAction.Swipe(x, y, x2, y2)
            }
            "drag" -> {
                val x = int("x"); val y = int("y"); val x2 = int("x2"); val y2 = int("y2")
                if (x == null || y == null || x2 == null || y2 == null) {
                    return Parsed(thought, null, done = false, valid = false,
                        error = "drag requires 'x','y','x2','y2' integer fields. Got: x=$x, y=$y, x2=$x2, y2=$y2.")
                }
                DeviceAction.Drag(x, y, x2, y2)
            }
            "scroll" -> {
                val dir = (obj["direction"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.trim()
                if (dir == null || dir !in listOf("up", "down", "left", "right")) {
                    return Parsed(thought, null, done = false, valid = false,
                        error = "scroll requires 'direction' field (up/down/left/right). Got: $dir")
                }
                val amt = (obj["amount"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()
                DeviceAction.Scroll(dir, amt ?: 0.35f)
            }
            "type" -> {
                val text = (obj["text"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["value"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["input"] as? JsonPrimitive)?.contentOrNull
                if (text == null) {
                    return Parsed(thought, null, done = false, valid = false,
                        error = "type requires 'text' string field. Got: null.")
                }
                DeviceAction.Type(text)
            }
            "launch" -> {
                val pkg = (obj["package"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["packageName"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["app"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["appName"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["application"] as? JsonPrimitive)?.contentOrNull
                if (pkg == null) {
                    return Parsed(thought, null, done = false, valid = false,
                        error = "launch requires 'package' string field (e.g. com.android.camera). Got: null.")
                }
                DeviceAction.Launch(pkg)
            }
            "back" -> DeviceAction.Back
            "home" -> DeviceAction.Home
            "screenshot" -> DeviceAction.Screenshot
            else -> return Parsed(thought, null, done = false, valid = false,
                error = "Unknown action '$action'. Valid: tap, click, swipe, scroll, type, launch, back, home, screenshot, done.")
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
     *
     * CRITICAL FIX (agentic tasks not working): `type(...)` now WRAPS the
     * text in double quotes. Previously it emitted `type(hello, world)`
     * (unquoted), but `parseDeviceAction`'s primary regex requires quotes:
     * `type\s*\(\s*"(.*?)"\s*\)`. The unquoted form fell through to the
     * non-greedy fallback regex which mis-extracted text containing commas
     * or closing parens — `type(a,b)` became `a`, `type(a)b)` became `a`.
     * This corrupted text input whenever the text contained `,` or `)`,
     * silently dispatching the wrong text. Quoting round-trips correctly
     * through both the primary regex and the structured tool_call path.
     */
    fun toActionLine(action: DeviceAction?): String? = when (action) {
        null -> null
        DeviceAction.NoOp -> null
        is DeviceAction.Tap -> "tap(${action.x},${action.y})"
        is DeviceAction.Swipe -> "swipe(${action.x1},${action.y1},${action.x2},${action.y2})"
        is DeviceAction.Drag -> "drag(${action.x1},${action.y1},${action.x2},${action.y2})"
        is DeviceAction.Scroll -> "scroll(${action.direction},${action.amount})"
        is DeviceAction.Type -> "type(\"${action.text}\")"
        is DeviceAction.Launch -> "launch(${action.packageName})"
        DeviceAction.Back -> "back"
        DeviceAction.Home -> "home"
        DeviceAction.Screenshot -> "screenshot"
    }
}
