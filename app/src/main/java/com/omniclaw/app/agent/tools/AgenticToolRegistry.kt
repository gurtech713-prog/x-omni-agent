package com.omniclaw.app.agent.tools

import android.content.Context
import android.util.Log
import com.omniclaw.app.data.model.ToolSpec
import com.omniclaw.app.data.skill.SkillRepository
import com.omniclaw.app.deeplink.DeepLinkManager
import com.omniclaw.app.gallery.GalleryScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified agentic tool registry — exposes ALL agent capabilities as first-class
 * structured tools that the LLM can call via the OpenAI function-calling API.
 *
 * This replaces the old dual-path architecture where:
 *   - Device actions (tap/swipe/launch) went through a single `device_action` tool
 *   - Skills (gallery, clipboard, etc.) were dispatched via free-text `skill:<id>(<arg>)`
 *     lines that the agent loop had to regex-parse out of the LLM's response
 *
 * The old approach was broken because:
 *   1. Skills were invisible to the LLM's tool-calling mechanism — the model had
 *      to emit a text string like `skill:gallery-qa(20)` and hope the regex
 *      caught it. If the model forgot the exact syntax, the skill silently
 *      didn't fire.
 *   2. Skill results were thin strings ("Gallery search found 5 results") with
 *      no actual data — the LLM couldn't reason over what was found.
 *   3. Skills couldn't chain — the result of one skill couldn't inform the
 *      next tool call because the result was opaque text.
 *
 * In the new architecture, every capability is a [Tool] with:
 *   - A JSON Schema describing its parameters (so the LLM knows exactly how to call it)
 *   - A suspend [dispatch] function that returns a rich [ToolResult] with
 *     structured data the LLM can reason over in the next step.
 *
 * The agent loop simply:
 *   1. Builds the tool list from the registry (filtered by enabled skills)
 *   2. Calls the LLM with `tools=[...]`
 *   3. If the LLM returns a tool_call, dispatches it via [dispatch]
 *   4. Feeds the structured result back as a `tool` message
 *   5. Repeats until the LLM stops calling tools (finish_reason="stop")
 *
 * This is a standard ReAct (Reason+Act) loop with structured tool-calling,
 * which is how production agent frameworks (OpenAI Assistants, LangChain,
 * CrewAI) work.
 */
@Singleton
class AgenticToolRegistry @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val scheduler: DeviceScheduler,
    private val gallery: GalleryScanner,
    private val deepLinks: DeepLinkManager,
    private val skillRepo: SkillRepository,
    private val vlm: com.omniclaw.app.vision.VlmClient,
    private val appProfiles: AppSkillProfiles,
) {

    /**
     * A single tool exposed to the LLM. Each tool has:
     *   - [name]: the function name the LLM calls (e.g. "gallery_search")
     *   - [spec]: the JSON Schema [ToolSpec] handed to the LLM client
     *   - [dispatch]: the suspend function that executes the tool and returns
     *     a [ToolResult] with structured data
     */
    interface Tool {
        val name: String
        val spec: ToolSpec
        suspend fun dispatch(args: JsonObject): ToolResult
    }

    /**
     * Result of a tool dispatch. Contains:
     *   - [success]: whether the tool executed without error
     *   - [content]: the human/LLM-readable result text (always non-empty —
     *     even on failure, this explains what went wrong so the LLM can recover)
     *   - [data]: optional structured JSON data for rich results (e.g. the
     *     list of photos found by gallery_search). The LLM can read this to
     *     make decisions in the next step. Serialized as a JSON string in the
     *     tool message's content alongside [content].
     */
    data class ToolResult(
        val success: Boolean,
        val content: String,
        val data: JsonObject? = null,
    ) {
        /** Serialize to the content string for the tool message. */
        fun toMessageContent(): String = buildString {
            append(content)
            if (data != null) {
                append("\n\n")
                append(JSON.encodeToString(JsonObject.serializer(), data))
            }
        }
    }

    // ---- Public API ----

    /**
     * Build the list of [ToolSpec]s to hand to the LLM, filtered by:
     *   - Always-on tools (device actions, gallery, bookmarks)
     *   - User-enabled skills (scheduled-automation, skill-creator, app-search)
     *
     * Called once per agent step. Cheap (just filters a list).
     */
    fun toolSpecs(): List<ToolSpec> = tools.mapNotNull { (tool, skillId) ->
        // skillId == null means always-on (device actions, gallery, etc.)
        if (skillId == null) tool.spec
        else {
            val skill = skillRepo.skills.value.firstOrNull { it.id == skillId }
            if (skill?.enabled == true) tool.spec else null
        }
    }

    /**
     * Dispatch a tool call from the LLM. Returns the [ToolResult].
     *
     * @param toolName the function name the LLM called
     * @param argsJson the raw arguments JSON string from the LLM
     */
    suspend fun dispatch(toolName: String, argsJson: String): ToolResult {
        val tool = tools.firstOrNull { it.tool.name == toolName }?.tool
            ?: return ToolResult(false, "Unknown tool: $toolName")

        val args: JsonObject = runCatching {
            JSON.parseToJsonElement(argsJson).jsonObject
        }.getOrDefault(JsonObject(emptyMap()))

        return runCatching {
            tool.dispatch(args)
        }.getOrElse { e ->
            Log.w(TAG, "Tool '$toolName' threw: ${e.message}")
            ToolResult(false, "Tool '$toolName' failed: ${e.message ?: e::class.simpleName}")
        }
    }

    // ---- Tool definitions ----

    /**
     * All tools, paired with their owning skill ID (or null for always-on).
     * Order matters — device actions first (most common), then gallery, then
     * skills. The LLM sees them in this order in the tools array.
     */
    private val tools: List<Pair<Tool, String?>> = listOf(
        DeviceActionTool() to null,
        TapElementTool() to null,
        WaitForElementTool() to null,
        TapElementVisualTool() to null,
        FindElementsTool() to null,
        UndoLastActionTool() to null,
        SelectTextTool() to null,
        CopyToClipboardTool() to null,
        ReadClipboardTool() to null,
        GalleryRecentTool() to null,
        GallerySearchTool() to null,
        GallerySyncMemoryTool() to "gallery-qa",
        GalleryStageThemeTool() to "gallery-qa",
        ClipboardReadTool() to null,
        ClipboardSaveBookmarkTool() to "clipboard-to-shortcut",
        BookmarkListTool() to null,
        BookmarkLaunchTool() to null,
        AppSearchTool() to "app-search",
        ScheduledAutomationTool() to "scheduled-automation",
        SkillCreatorTool() to "skill-creator",
    )

    // ---- Device action tool (tap/swipe/scroll/type/launch/back/home/screenshot/done) ----

    /**
     * The unified device_action tool. Reuses [DeviceToolSchema] for the schema
     * and [DeviceScheduler] for dispatch. The LLM calls this for any physical
     * device interaction.
     *
     * Returns a [ToolResult] with success/failure + a diagnostic message that
     * tells the LLM exactly what happened (e.g. "tap(540,1200) completed" or
     * "error: accessibility service not connected — tell the user to enable it").
     */
    private inner class DeviceActionTool : Tool {
        override val name = DeviceToolSchema.TOOL_NAME
        override val spec = DeviceToolSchema.SPEC

        override suspend fun dispatch(args: JsonObject): ToolResult {
            // CRITICAL FIX: serialize the JsonObject to a proper JSON string.
            // Previously, args.toString() produced Kotlin's Map.toString()
            // ({action=tap, x=540}) which is NOT valid JSON — the parser
            // failed with "Could not parse JSON" and EVERY device_action
            // tool call returned "error: invalid tool arguments".
            val argsJson = JSON.encodeToString(JsonObject.serializer(), args)
            val parsed = DeviceToolSchema.parse(argsJson)
            if (!parsed.valid && !parsed.done) {
                // Surface the detailed error message so the LLM can correct its
                // next tool call instead of blindly retrying the same bad format.
                return ToolResult(false, "error: ${parsed.error ?: "invalid tool arguments"}")
            }
            if (parsed.done) {
                return ToolResult(true, "Task marked as complete.", buildJsonObject {
                    put("done", true)
                    put("thought", parsed.thought)
                })
            }
            val action = parsed.action ?: return ToolResult(false, "error: no action parsed from arguments.")
            // CRITICAL FIX: use runCatchingCancellable (not runCatching) so
            // CancellationException propagates — otherwise user-stop and step
            // timeouts are swallowed and the agent hangs.
            val ok = runCatchingCancellable { scheduler.dispatch(action) }.getOrDefault(false)
            val content = when {
                ok -> "ok: ${DeviceToolSchema.toActionLine(action)} executed successfully."
                scheduler.boundService == null -> "error: accessibility service not connected. " +
                    "Tell the user to enable it: Settings → Accessibility → X-OmniClaw → On. " +
                    "Note: launch() works without the service, but tap/swipe/scroll/type require it."
                else -> "error: dispatch failed (service connected but action rejected). " +
                    "The action may have landed during an animation — try again or use a different coordinate."
            }
            return ToolResult(ok, content, buildJsonObject {
                put("action", DeviceToolSchema.toActionLine(action) ?: action.toString())
                put("success", ok)
                put("x", (args["x"] as? JsonPrimitive)?.intOrNull ?: -1)
                put("y", (args["y"] as? JsonPrimitive)?.intOrNull ?: -1)
            })
        }
    }

    // ---- tap_element tool (text/description-based tap — no coordinates needed) ----

    /**
     * Tap a UI element by its text or content description. This is the
     * PREFERRED way to interact with on-screen elements — it's far more
     * reliable than tap(x,y) because it searches the accessibility tree
     * for the matching element and clicks it directly, eliminating the
     * coordinate-accuracy problem entirely.
     *
     * Use this when you can see a button/label/link in the screen observation
     * (e.g. "Search", "OK", "Cancel", "Send", "Shutter"). The executor finds
     * the node by text/content-description match and dispatches ACTION_CLICK.
     *
     * Falls back to the nearest clickable ancestor if the matched text node
     * isn't itself clickable (common pattern: TextView inside FrameLayout).
     */
    private inner class TapElementTool : Tool {
        override val name = "tap_element"
        override val spec = ToolSpec(
            name = name,
            description = "Tap a UI element by its text or description — no coordinates needed. PREFERRED over tap(x,y) for buttons/links/labels. Searches the accessibility tree for the matching element and clicks it. Use the exact text or description you see in the screen observation (e.g. 'Search', 'OK', 'Cancel', 'Send').",
            parametersSchema = """
                {
                  "type": "object",
                  "properties": {
                    "text": {"type": "string", "description": "The text or content description of the element to tap (e.g. 'Search', 'OK', 'Shutter'). Case-insensitive, supports partial matches."}
                  },
                  "required": ["text"]
                }
            """.trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val text = (args["text"] as? JsonPrimitive)?.contentOrNull
                ?: (args["query"] as? JsonPrimitive)?.contentOrNull
                ?: (args["description"] as? JsonPrimitive)?.contentOrNull
                ?: (args["label"] as? JsonPrimitive)?.contentOrNull
            if (text.isNullOrBlank()) {
                return ToolResult(false, "error: 'text' parameter is required (the visible text of the element to tap).")
            }
            // Step 1: try text/content-description match
            var ok = runCatchingCancellable { scheduler.tapElementByText(text) }.getOrDefault(false)
            // Step 2: APP-SPECIFIC SKILL PROFILE fallback — if text match failed
            // and the foreground app has a skill profile (e.g. camera apps),
            // try matching known view IDs for this package.
            if (!ok) {
                val pkg = scheduler.foregroundPackage()
                if (pkg != null && appProfiles.hasProfile(pkg)) {
                    val viewIds = appProfiles.viewIdsFor(pkg, text.lowercase())
                    for (viewId in viewIds) {
                        ok = runCatchingCancellable { scheduler.tapElementByText(viewId) }.getOrDefault(false)
                        if (ok) break
                    }
                }
            }
            val content = if (ok) {
                "ok: tapped element matching '$text'."
            } else {
                if (scheduler.boundService == null) {
                    "error: accessibility service not connected. Tell the user to enable it: Settings → Accessibility → X-OmniClaw → On."
                } else {
                    "error: no element matching '$text' was found on the current screen. Check the screen observation for the exact text, or try tap_element_visual with a description, or tap(x,y) with coordinates from the TAP:(x,y) hints."
                }
            }
            return ToolResult(ok, content, buildJsonObject {
                put("query", text)
                put("success", ok)
            })
        }
    }

    // ---- wait_for_element tool (polls until an element appears) ----

    /**
     * Wait for a UI element matching the given text/description to appear on
     * screen. Polls the accessibility tree every 500ms up to [timeoutSeconds].
     * Essential for slow-loading apps where the element isn't present
     * immediately after a launch or navigation.
     *
     * Returns success if the element was found within the timeout, failure
     * otherwise. The LLM can use this before calling tap_element to ensure
     * the target is present.
     */
    private inner class WaitForElementTool : Tool {
        override val name = "wait_for_element"
        override val spec = ToolSpec(
            name = name,
            description = "Wait for a UI element (by text/description) to appear on screen. Polls every 500ms. Use this after launching an app or navigating, before tapping an element that may not be loaded yet.",
            parametersSchema = """
                {
                  "type": "object",
                  "properties": {
                    "text": {"type": "string", "description": "Text or content description to wait for (case-insensitive, partial match)."},
                    "timeoutSeconds": {"type": "integer", "description": "Max seconds to wait. Default 10, max 30."}
                  },
                  "required": ["text"]
                }
            """.trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val text = (args["text"] as? JsonPrimitive)?.contentOrNull
                ?: return ToolResult(false, "error: 'text' parameter is required.")
            val timeoutSec = ((args["timeoutSeconds"] as? JsonPrimitive)?.intOrNull ?: 10).coerceIn(1, 30)
            val deadline = System.currentTimeMillis() + timeoutSec * 1000L
            var found = false
            while (System.currentTimeMillis() < deadline) {
                // Only CHECK if the element exists — do NOT click it (that's
                // tap_element's job). We just wait for it to appear.
                if (scheduler.elementExists(text)) {
                    found = true
                    break
                }
                kotlinx.coroutines.delay(500)
            }
            return if (found) {
                ToolResult(true, "Element '$text' appeared on screen.", buildJsonObject {
                    put("found", true)
                    put("text", text)
                })
            } else {
                ToolResult(false, "Element '$text' did not appear within ${timeoutSec}s. The app may be slow to load or the text may not match.", buildJsonObject {
                    put("found", false)
                    put("text", text)
                })
            }
        }
    }

    // ---- tap_element_visual tool (VLM-based — for when the a11y tree is empty) ----

    /**
     * VLM-based tap: takes a screenshot, sends it to the vision model, and
     * asks "where is the element described as X? Return x,y coordinates."
     * Used when the accessibility tree is empty (camera apps, games, custom
     * views with no a11y nodes) — the text-based tap_element can't find
     * anything because there are no nodes to search.
     *
     * Requires a VLM API key to be configured. Falls back gracefully with
     * a clear error message if no VLM is available.
     */
    private inner class TapElementVisualTool : Tool {
        override val name = "tap_element_visual"
        override val spec = ToolSpec(
            name = name,
            description = "Tap a UI element using vision (VLM). Takes a screenshot, asks the vision model to locate the element by description, and taps the returned coordinates. Use this when the accessibility tree is empty (camera apps, games, custom views) and tap_element can't find the element.",
            parametersSchema = """
                {
                  "type": "object",
                  "properties": {
                    "description": {"type": "string", "description": "Natural-language description of the element to tap (e.g. 'shutter button', 'red send button', 'search bar at the top')."}
                  },
                  "required": ["description"]
                }
            """.trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val description = (args["description"] as? JsonPrimitive)?.contentOrNull
                ?: return ToolResult(false, "error: 'description' parameter is required.")
            // Delegate to the scheduler's VLM-based tap, passing the injected VLM client.
            val result = runCatchingCancellable {
                scheduler.tapElementVisual(description, vlm)
            }.getOrDefault(false)
            return if (result) {
                ToolResult(true, "Tapped element matching '$description' via vision.")
            } else {
                ToolResult(false, "Could not find or tap '$description' via vision. The VLM may not be configured (check Settings for a VLM API key), or the element wasn't visible on screen.")
            }
        }
    }

    // ---- select_text tool ----

    /**
     * Select text in a focused or specified editable field. Uses the
     * accessibility API's ACTION_SET_SELECTION to select a character range.
     */
    private inner class SelectTextTool : Tool {
        override val name = "select_text"
        override val spec = ToolSpec(
            name = name,
            description = "Select text in the focused editable field. Uses start/end character indices. After selecting, use copy_to_clipboard to copy.",
            parametersSchema = """
                {
                  "type": "object",
                  "properties": {
                    "start": {"type": "integer", "description": "Start character index (0-based)."},
                    "end": {"type": "integer", "description": "End character index (exclusive)."}
                  },
                  "required": ["start", "end"]
                }
            """.trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val start = (args["start"] as? JsonPrimitive)?.intOrNull
                ?: return ToolResult(false, "error: 'start' is required.")
            val end = (args["end"] as? JsonPrimitive)?.intOrNull
                ?: return ToolResult(false, "error: 'end' is required.")
            val ok = scheduler.selectText(start, end)
            return if (ok) {
                ToolResult(true, "Selected text from char $start to $end.")
            } else {
                ToolResult(false, "Failed to select text. Ensure an editable field is focused.")
            }
        }
    }

    // ---- copy_to_clipboard tool ----

    /**
     * Copy the current selection (or all text in the focused field) to the
     * system clipboard via ACTION_COPY.
     */
    private inner class CopyToClipboardTool : Tool {
        override val name = "copy_to_clipboard"
        override val spec = ToolSpec(
            name = name,
            description = "Copy the current text selection to the system clipboard. Select text first with select_text, or if no selection exists, copies all text in the focused field.",
            parametersSchema = """{"type": "object"}""".trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val ok = scheduler.copySelection()
            return if (ok) {
                ToolResult(true, "Copied selection to clipboard.")
            } else {
                ToolResult(false, "Failed to copy. Ensure text is selected in an editable field.")
            }
        }
    }

    // ---- read_clipboard tool ----

    /**
     * Read the current clipboard content. Returns the text and whether it's a URL.
     */
    private inner class ReadClipboardTool : Tool {
        override val name = "read_clipboard"
        override val spec = ToolSpec(
            name = name,
            description = "Read the current system clipboard content. Returns the text and whether it's a URL.",
            parametersSchema = """{"type": "object"}""".trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val text = scheduler.readClipboard()
            return if (text != null) {
                ToolResult(true, "Clipboard contains: $text", buildJsonObject {
                    put("text", text)
                    put("isUrl", text.startsWith("http://") || text.startsWith("https://") || text.contains("://"))
                })
            } else {
                ToolResult(true, "Clipboard is empty or not accessible.", buildJsonObject {
                    put("text", "")
                    put("isUrl", false)
                })
            }
        }
    }

    // ---- find_elements tool (returns all matching elements) ----

    /**
     * Find ALL UI elements matching a text/description query. Returns each
     * element's text, bounds, center coordinates (TAP target), and whether
     * it's clickable/editable/scrollable. Use this when multiple elements
     * match and you need to choose the right one (e.g. multiple "OK" buttons
     * in different dialogs).
     */
    private inner class FindElementsTool : Tool {
        override val name = "find_elements"
        override val spec = ToolSpec(
            name = name,
            description = "Find ALL UI elements matching a text/description query. Returns each element's text, bounds, TAP coordinates, and clickability. Use when multiple elements match and you need to choose the right one.",
            parametersSchema = """
                {
                  "type": "object",
                  "properties": {
                    "text": {"type": "string", "description": "Text or content description to search for (case-insensitive, partial match)."}
                  },
                  "required": ["text"]
                }
            """.trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val text = (args["text"] as? JsonPrimitive)?.contentOrNull
                ?: return ToolResult(false, "error: 'text' parameter is required.")
            val elements = runCatchingCancellable { scheduler.findAllElements(text) }.getOrDefault(emptyList())
            if (elements.isEmpty()) {
                return ToolResult(true, "No elements matching '$text' found.", buildJsonObject {
                    put("count", 0)
                    put("elements", JsonArray(emptyList()))
                })
            }
            val arr = JsonArray(elements.map { e ->
                buildJsonObject {
                    put("text", e.text)
                    put("description", e.contentDescription)
                    put("viewId", e.viewId)
                    put("clickable", e.isClickable)
                    put("editable", e.isEditable)
                    put("scrollable", e.isScrollable)
                    put("centerX", e.centerX)
                    put("centerY", e.centerY)
                    put("bounds", JsonArray(e.bounds.map { JsonPrimitive(it) }))
                    put("tapHint", "TAP:(${e.centerX},${e.centerY})")
                }
            })
            return ToolResult(true, "Found ${elements.size} elements matching '$text'.", buildJsonObject {
                put("count", elements.size)
                put("elements", arr)
            })
        }
    }

    // ---- undo_last_action tool ----

    /**
     * Undo the last action by pressing BACK. Dismisses dialogs, closes
     * keyboards, navigates to the previous screen. Use when the agent made
     * a mistake (wrong tap, wrong navigation) and needs to revert.
     */
    private inner class UndoLastActionTool : Tool {
        override val name = "undo_last_action"
        override val spec = ToolSpec(
            name = name,
            description = "Undo the last action by pressing BACK. Dismisses dialogs, closes keyboards, navigates back. Use when you made a mistake and need to revert to the previous screen state.",
            parametersSchema = """{"type": "object"}""".trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val ok = runCatchingCancellable { scheduler.undoLastAction() }.getOrDefault(false)
            return if (ok) {
                ToolResult(true, "Undo: pressed BACK to revert the last action.")
            } else {
                ToolResult(false, "Undo failed: could not press BACK or HOME. The accessibility service may be disconnected.")
            }
        }
    }

    // ---- Gallery tools ----

    /**
     * Get the N most recent photos. Returns structured data with each photo's
     * name, date, bucket (camera roll / screenshots / etc.), and dimensions —
     * enough for the LLM to answer "what photos did I take today?" without
     * needing the actual image bytes.
     */
    private inner class GalleryRecentTool : Tool {
        override val name = "gallery_recent"
        override val spec = ToolSpec(
            name = name,
            description = "Get the most recent photos from the device gallery. Returns photo metadata (name, date, bucket, dimensions) — not the image bytes. Use this to answer questions about recent photos.",
            parametersSchema = """
                {
                  "type": "object",
                  "properties": {
                    "limit": {"type": "integer", "description": "Max photos to return (1-100). Default 20."}
                  }
                }
            """.trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val limit = (args["limit"] as? JsonPrimitive)?.intOrNull ?: 20
            val clampedLimit = limit.coerceIn(1, 100)
            val result = gallery.recentResult(clampedLimit)
            return when (result) {
                is GalleryScanner.GalleryResult.Ok -> {
                    val photos = result.photos
                    if (photos.isEmpty()) {
                        ToolResult(true, "No photos found in the gallery.", buildJsonObject {
                            put("count", 0)
                            put("photos", JsonArray(emptyList()))
                        })
                    } else {
                        ToolResult(true, "Found ${photos.size} recent photos.", photosToJson(photos))
                    }
                }
                is GalleryScanner.GalleryResult.NoPermission ->
                    ToolResult(false, "Gallery permission not granted. Ask the user to grant media access in Settings.")
                is GalleryScanner.GalleryResult.Error ->
                    ToolResult(false, "Gallery query failed (internal error). Try again or use a different approach.")
            }
        }
    }

    /**
     * Search the gallery by filename or bucket name. Returns matching photos
     * with full metadata so the LLM can reason over the results.
     */
    private inner class GallerySearchTool : Tool {
        override val name = "gallery_search"
        override val spec = ToolSpec(
            name = name,
            description = "Search the device gallery by filename or album name. Returns matching photos with metadata. Use this to find specific photos.",
            parametersSchema = """
                {
                  "type": "object",
                  "properties": {
                    "query": {"type": "string", "description": "Search query (matches filename or album/bucket name)."},
                    "limit": {"type": "integer", "description": "Max results (1-100). Default 50."}
                  },
                  "required": ["query"]
                }
            """.trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val query = (args["query"] as? JsonPrimitive)?.contentOrNull
                ?: return ToolResult(false, "error: 'query' parameter is required.")
            val limit = (args["limit"] as? JsonPrimitive)?.intOrNull ?: 50
            val result = gallery.searchResult(query, limit.coerceIn(1, 100))
            return when (result) {
                is GalleryScanner.GalleryResult.Ok -> {
                    val photos = result.photos
                    ToolResult(
                        true,
                        "Search for '$query' found ${photos.size} photos.",
                        buildJsonObject {
                            put("query", query)
                            put("count", photos.size)
                            put("photos", photosToJsonArray(photos))
                        }
                    )
                }
                is GalleryScanner.GalleryResult.NoPermission ->
                    ToolResult(false, "Gallery permission not granted. Ask the user to grant media access.")
                is GalleryScanner.GalleryResult.Error ->
                    ToolResult(false, "Gallery search failed (internal error).")
            }
        }
    }

    /**
     * Sync recent photos into long-term memory (the gallery-qa skill).
     * Returns the count of photos scanned and the buckets discovered.
     */
    private inner class GallerySyncMemoryTool : Tool {
        override val name = "gallery_sync_memory"
        override val spec = ToolSpec(
            name = name,
            description = "Scan recent photos into long-term memory. Creates memory entries summarizing what photos exist. Use before answering questions about photo history.",
            parametersSchema = """
                {
                  "type": "object",
                  "properties": {
                    "count": {"type": "integer", "description": "Number of recent photos to scan (1-100). Default 20."}
                  }
                }
            """.trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val count = (args["count"] as? JsonPrimitive)?.intOrNull ?: 20
            val scanned = gallery.syncMemory(count.coerceIn(1, 100))
            return ToolResult(true, "Scanned $scanned photos into memory.", buildJsonObject {
                put("scanned", scanned)
            })
        }
    }

    /**
     * Stage photos matching a theme keyword for video creation (capcut skill).
     */
    private inner class GalleryStageThemeTool : Tool {
        override val name = "gallery_stage_theme"
        override val spec = ToolSpec(
            name = name,
            description = "Filter gallery photos by theme keyword and stage them for video creation. Returns the staged photo URIs.",
            parametersSchema = """
                {
                  "type": "object",
                  "properties": {
                    "theme": {"type": "string", "description": "Theme keyword to filter by (e.g. 'beach', 'sunset')."},
                    "limit": {"type": "integer", "description": "Max photos to stage (1-50). Default 30."}
                  },
                  "required": ["theme"]
                }
            """.trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val theme = (args["theme"] as? JsonPrimitive)?.contentOrNull
                ?: return ToolResult(false, "error: 'theme' parameter is required.")
            val limit = (args["limit"] as? JsonPrimitive)?.intOrNull ?: 30
            val uris = gallery.stageForTheme(theme, limit.coerceIn(1, 50))
            return ToolResult(true, "Staged ${uris.size} photos for theme '$theme'.", buildJsonObject {
                put("theme", theme)
                put("staged", uris.size)
                put("uris", JsonArray(uris.map { JsonPrimitive(it.toString()) }))
            })
        }
    }

    // ---- Clipboard & bookmark tools ----

    /**
     * Read the clipboard. Returns the clipboard text and whether it looks like a URL.
     */
    private inner class ClipboardReadTool : Tool {
        override val name = "clipboard_read"
        override val spec = ToolSpec(
            name = name,
            description = "Read the current clipboard content. Returns the text and whether it's a URL.",
            parametersSchema = """{"type": "object"}""".trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val url = deepLinks.readClipboardUrl()
            return if (url != null) {
                ToolResult(true, "Clipboard contains a URL: $url", buildJsonObject {
                    put("text", url)
                    put("isUrl", true)
                })
            } else {
                // readClipboardUrl returns null for non-URLs; we can still report that
                ToolResult(true, "Clipboard does not contain a URL (or is empty).", buildJsonObject {
                    put("isUrl", false)
                })
            }
        }
    }

    /**
     * Save the clipboard URL as a named bookmark.
     */
    private inner class ClipboardSaveBookmarkTool : Tool {
        override val name = "clipboard_save_bookmark"
        override val spec = ToolSpec(
            name = name,
            description = "Save the clipboard URL as a named bookmark for quick launch later. The URL must already be on the clipboard.",
            parametersSchema = """
                {
                  "type": "object",
                  "properties": {
                    "name": {"type": "string", "description": "Bookmark name (e.g. 'Amazon quick link')."}
                  },
                  "required": ["name"]
                }
            """.trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val name = (args["name"] as? JsonPrimitive)?.contentOrNull
                ?: return ToolResult(false, "error: 'name' parameter is required.")
            val url = deepLinks.readClipboardUrl()
                ?: return ToolResult(false, "Clipboard doesn't contain a URL. Ask the user to copy a URL first.")
            val id = deepLinks.saveBookmark(name, url)
            return ToolResult(true, "Saved bookmark '$name' (id: $id) for URL: $url", buildJsonObject {
                put("bookmarkId", id)
                put("name", name)
                put("url", url)
            })
        }
    }

    /**
     * List all saved bookmarks.
     */
    private inner class BookmarkListTool : Tool {
        override val name = "bookmark_list"
        override val spec = ToolSpec(
            name = name,
            description = "List all saved bookmarks. Returns each bookmark's id, name, and URL.",
            parametersSchema = """{"type": "object"}""".trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val bookmarks = deepLinks.listBookmarks()
            return ToolResult(true, "Found ${bookmarks.size} bookmarks.", buildJsonObject {
                put("count", bookmarks.size)
                put("bookmarks", JsonArray(bookmarks.map { b ->
                    buildJsonObject {
                        put("id", b.id)
                        put("name", b.name)
                        put("url", b.uri)
                    }
                }))
            })
        }
    }

    /**
     * Launch a bookmark by name (whole-word match).
     */
    private inner class BookmarkLaunchTool : Tool {
        override val name = "bookmark_launch"
        override val spec = ToolSpec(
            name = name,
            description = "Launch a saved bookmark by name. Matches the bookmark name as a whole word in the phrase.",
            parametersSchema = """
                {
                  "type": "object",
                  "properties": {
                    "name": {"type": "string", "description": "Bookmark name or phrase containing it (e.g. 'Amazon' or 'open Amazon')."}
                  },
                  "required": ["name"]
                }
            """.trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val name = (args["name"] as? JsonPrimitive)?.contentOrNull
                ?: return ToolResult(false, "error: 'name' parameter is required.")
            val ok = deepLinks.launchByPhrase(name)
            return if (ok) {
                ToolResult(true, "Launched bookmark matching '$name'.")
            } else {
                ToolResult(false, "No bookmark matched '$name'. Use bookmark_list to see available bookmarks.")
            }
        }
    }

    // ---- App search tool ----

    /**
     * Launch an app and indicate the next step (tap search bar + type query).
     * This is a composite tool: it launches the app via DeviceScheduler, then
     * returns a hint that the agent should tap the search bar and type.
     */
    private inner class AppSearchTool : Tool {
        override val name = "app_search"
        override val spec = ToolSpec(
            name = name,
            description = "Launch an app to search within it. Launches the app; the agent then taps the search bar and types the query in subsequent steps. Use for any 'search X in app Y' request.",
            parametersSchema = """
                {
                  "type": "object",
                  "properties": {
                    "package": {"type": "string", "description": "App package name (e.g. com.reddit.frontpage, com.google.android.youtube). Use 'com.android.chrome' for browser."},
                    "query": {"type": "string", "description": "Search query to type into the app's search bar."}
                  },
                  "required": ["package", "query"]
                }
            """.trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val pkg = (args["package"] as? JsonPrimitive)?.contentOrNull
                ?: return ToolResult(false, "error: 'package' parameter is required.")
            val query = (args["query"] as? JsonPrimitive)?.contentOrNull
                ?: return ToolResult(false, "error: 'query' parameter is required.")
            val launched = runCatching { scheduler.dispatch(DeviceAction.Launch(pkg)) }.getOrDefault(false)
            return if (launched) {
                ToolResult(true, "Launched $pkg. Next: observe the screen, tap the search bar, and type '$query'.",
                    buildJsonObject {
                        put("package", pkg)
                        put("query", query)
                        put("launched", true)
                        put("nextStep", "tap_search_bar_and_type")
                    })
            } else {
                ToolResult(false, "Failed to launch $pkg. Check the package name or use the browser (com.android.chrome).")
            }
        }
    }

    // ---- Scheduled automation tool ----

    /**
     * Schedule an automation task (interval or weekly).
     */
    private inner class ScheduledAutomationTool : Tool {
        override val name = "scheduled_automation"
        override val spec = ToolSpec(
            name = name,
            description = "Schedule a recurring automation task. Creates a WorkManager periodic task that runs the given prompt on schedule.",
            parametersSchema = """
                {
                  "type": "object",
                  "properties": {
                    "scheduleType": {"type": "string", "enum": ["interval", "weekly"], "description": "Type of schedule."},
                    "intervalMinutes": {"type": "integer", "description": "For interval type: minutes between runs (min 15)."},
                    "weekday": {"type": "string", "enum": ["Sun","Mon","Tue","Wed","Thu","Fri","Sat"], "description": "For weekly type: day of week."},
                    "time": {"type": "string", "description": "For weekly type: time in HH:mm format (24h, e.g. '10:00')."},
                    "prompt": {"type": "string", "description": "The prompt to run at each scheduled time."}
                  },
                  "required": ["scheduleType", "prompt"]
                }
            """.trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val scheduleType = (args["scheduleType"] as? JsonPrimitive)?.contentOrNull
                ?: return ToolResult(false, "error: 'scheduleType' is required (interval or weekly).")
            val prompt = (args["prompt"] as? JsonPrimitive)?.contentOrNull
                ?: return ToolResult(false, "error: 'prompt' is required.")

            val ok = when (scheduleType.lowercase()) {
                "interval" -> {
                    val minutes = (args["intervalMinutes"] as? JsonPrimitive)?.intOrNull?.toLong()
                        ?: return ToolResult(false, "error: 'intervalMinutes' required for interval type.")
                    if (minutes < 15) return ToolResult(false, "error: intervalMinutes must be >= 15.")
                    runCatching {
                        com.omniclaw.app.cron.ScheduledTaskWorker.scheduleInterval(
                            ctx, UUID.randomUUID().toString(),
                            "Agent-created interval", prompt, minutes,
                        )
                        true
                    }.getOrDefault(false)
                }
                "weekly" -> {
                    val dayName = (args["weekday"] as? JsonPrimitive)?.contentOrNull
                        ?: return ToolResult(false, "error: 'weekday' required for weekly type.")
                    val time = (args["time"] as? JsonPrimitive)?.contentOrNull
                        ?: return ToolResult(false, "error: 'time' required for weekly type (HH:mm).")
                    val dayMap = mapOf(
                        "Sun" to 1, "Mon" to 2, "Tue" to 3, "Wed" to 4,
                        "Thu" to 5, "Fri" to 6, "Sat" to 7,
                    )
                    val day = dayMap[dayName]
                        ?: return ToolResult(false, "error: unknown weekday '$dayName'. Use Sun/Mon/Tue/Wed/Thu/Fri/Sat.")
                    runCatching {
                        com.omniclaw.app.cron.ScheduledTaskWorker.scheduleWeekly(
                            ctx, UUID.randomUUID().toString(),
                            "Agent-created weekly", prompt, setOf(day), time,
                        )
                        true
                    }.getOrDefault(false)
                }
                else -> return ToolResult(false, "error: unknown scheduleType '$scheduleType'. Use 'interval' or 'weekly'.")
            }
            return if (ok) {
                ToolResult(true, "Scheduled automation created: $scheduleType → '$prompt'", buildJsonObject {
                    put("scheduleType", scheduleType)
                    put("prompt", prompt)
                    put("created", true)
                })
            } else {
                ToolResult(false, "Failed to create scheduled automation. Check WorkManager logs.")
            }
        }
    }

    // ---- Skill creator tool ----

    /**
     * Create a new skill from a natural-language description. Uses the LLM
     * (via a nested call) to draft a SKILL.md, then persists it.
     *
     * Note: this tool needs access to the LLM client to draft the skill. We
     * inject it lazily via a callback to avoid a circular dependency
     * (AgenticToolRegistry is injected into AgentLoop which holds the LLM).
     * The AgentLoop sets [skillDraftCallback] before the loop starts.
     */
    private inner class SkillCreatorTool : Tool {
        override val name = "skill_creator"
        override val spec = ToolSpec(
            name = name,
            description = "Create a new skill from a natural-language description. Drafts a SKILL.md using the LLM and saves it. The skill becomes available immediately.",
            parametersSchema = """
                {
                  "type": "object",
                  "properties": {
                    "description": {"type": "string", "description": "Natural-language description of what the skill should do."}
                  },
                  "required": ["description"]
                }
            """.trimIndent(),
        )

        override suspend fun dispatch(args: JsonObject): ToolResult {
            val description = (args["description"] as? JsonPrimitive)?.contentOrNull
                ?: return ToolResult(false, "error: 'description' is required.")
            val callback = skillDraftCallback
                ?: return ToolResult(false, "Skill drafting is not available (no LLM callback set).")
            return runCatching {
                withTimeout(30_000L) { callback(description) }
            }.getOrElse { e ->
                ToolResult(false, "Skill creation failed: ${e.message}")
            }
        }
    }

    /**
     * Callback set by [AgentLoop] to let the skill_creator tool draft a SKILL.md
     * via the LLM. Returns a [ToolResult] describing the outcome.
     */
    @Volatile
    var skillDraftCallback: (suspend (String) -> ToolResult)? = null

    // ---- Helpers ----

    /** Convert a list of photos to a JSON object with count + photos array. */
    private fun photosToJson(photos: List<GalleryScanner.Photo>): JsonObject = buildJsonObject {
        put("count", photos.size)
        put("photos", photosToJsonArray(photos))
    }

    /** Convert photos to a JSON array of metadata objects. */
    private fun photosToJsonArray(photos: List<GalleryScanner.Photo>): JsonArray = buildJsonArray {
        photos.forEach { p ->
            add(buildJsonObject {
                put("id", p.id)
                put("name", p.displayName)
                put("uri", p.uri.toString())
                put("dateTaken", p.dateTaken)
                put("dateHuman", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(p.dateTaken)))
                put("width", p.width)
                put("height", p.height)
                put("bucket", p.bucket)
                put("sizeBytes", p.sizeBytes)
            })
        }
    }

    companion object {
        private const val TAG = "AgenticToolRegistry"
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }

        /**
         * [runCatching] that re-throws [CancellationException] instead of
         * swallowing it. Standard `runCatching` catches every Throwable
         * including CancellationException, which breaks structured concurrency:
         * a cancelled coroutine gets converted to `Result.failure` and the
         * cancellation never propagates to the parent scope.
         */
        private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
            Result.success(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
