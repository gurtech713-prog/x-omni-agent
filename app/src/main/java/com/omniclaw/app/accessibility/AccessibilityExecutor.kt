package com.omniclaw.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.omniclaw.app.agent.tools.DeviceAction
import com.omniclaw.app.agent.tools.DeviceScheduler
import com.omniclaw.app.logging.AgentLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level executor for all accessibility operations.
 *
 * This is the production-grade replacement for the ad-hoc tap/swipe/type
 * methods that lived directly on [OmniAccessibilityService]. It coordinates:
 *
 *   - [NodeSearchEngine] for leak-safe, depth-bounded tree traversal.
 *   - [GestureManager] for reliable, retryable gesture dispatch.
 *   - [WindowTracker] for dialog/keyboard/shade awareness.
 *   - [AccessibilityDiagnostics] + [AccessibilityMetrics] for observability.
 *
 * The executor is a [Singleton] injected into [DeviceScheduler], which
 * delegates to it when the a11y service is connected. When the service is
 * NOT connected, the executor returns graceful failures so the agent loop
 * can fall back to vision.
 *
 * Every method:
 *   1. Checks window state (dismisses dialogs/keyboard if they'd block the action).
 *   2. Retries on transient failure per [AccessibilityRetryPolicy].
 *   3. Times the operation and records it in [AccessibilityMetrics].
 *   4. Logs diagnostic events to [AccessibilityDiagnostics].
 *   5. Never leaks [AccessibilityNodeInfo] objects — the [NodeSearchEngine]
 *      recycles every node it touches (except roots owned by the caller and
 *      returned targets owned by the caller).
 */
@Singleton
class AccessibilityExecutor @Inject constructor(
    private val diagnostics: AccessibilityDiagnostics,
    private val metrics: AccessibilityMetrics,
    private val windowTracker: WindowTracker,
    private val logger: AgentLogger,
) {

    private val searchEngine = NodeSearchEngine()
    /** The bound accessibility service, set by [DeviceScheduler] on connect. */
    @Volatile
    var service: AccessibilityService? = null

    /** The bound scheduler, used to read the `boundService` field. */
    @Volatile
    var deviceScheduler: DeviceScheduler? = null

    private fun svc(): AccessibilityService? = service ?: deviceScheduler?.boundService

    // S-L4: reuse a single GestureManager per bound service instance so the
    // totalGestures / successfulGestures / cancelledGestures / timedOutGestures
    // counters accumulate across dispatches instead of resetting to zero on
    // every tap/swipe. Re-creates the manager only when the underlying service
    // reference changes (connect/disconnect cycle).
    @Volatile private var gmForService: AccessibilityService? = null
    @Volatile private var gmCache: GestureManager? = null

    private fun gestureManager(svc: AccessibilityService): GestureManager {
        val cached = gmCache
        if (cached != null && gmForService === svc) return cached
        val gm = GestureManager(svc, policy = AccessibilityRetryPolicy.Default)
        gmForService = svc
        gmCache = gm
        return gm
    }

    /**
     * Check if a UI element matching [query] exists on the current screen.
     * Does NOT click it — just returns true if found. Uses the same 5-pass
     * matching strategy as [tapElementByText].
     */
    suspend fun elementExists(query: String): Boolean {
        val svc = svc() as? AccessibilityService ?: return false
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return false
        try {
            return findNodeByText(root, query) != null
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * Find ALL UI elements matching [query] and return their metadata as a
     * list of [ElementInfo]. Used by the find_elements tool so the LLM can
     * see all matching elements and choose which one to tap.
     *
     * Each [ElementInfo] includes: text, content-description, view ID,
     * bounds, center coordinates (TAP target), and whether it's clickable.
     */
    suspend fun findAllElements(query: String): List<ElementInfo> {
        val svc = svc() as? AccessibilityService ?: return emptyList()
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return emptyList()
        try {
            val q = query.trim().lowercase()
            if (q.isBlank()) return emptyList()
            val results = mutableListOf<ElementInfo>()
            val allNodes = searchEngine.findAll(root) { node ->
                runCatching {
                    val text = node.text?.toString().orEmpty()
                    val desc = node.contentDescription?.toString().orEmpty()
                    val id = node.viewIdResourceName.orEmpty()
                    text.lowercase().contains(q) ||
                        desc.lowercase().contains(q) ||
                        id.lowercase().contains(q)
                }.getOrDefault(false)
            }
            for (node in allNodes) {
                val rect = android.graphics.Rect()
                runCatching { node.getBoundsInScreen(rect) }
                val info = ElementInfo(
                    text = runCatching { node.text?.toString().orEmpty() }.getOrDefault(""),
                    contentDescription = runCatching { node.contentDescription?.toString().orEmpty() }.getOrDefault(""),
                    viewId = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault(""),
                    bounds = intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
                    centerX = (rect.left + rect.right) / 2,
                    centerY = (rect.top + rect.bottom) / 2,
                    isClickable = runCatching { node.isClickable }.getOrDefault(false),
                    isEditable = runCatching { node.isEditable }.getOrDefault(false),
                    isScrollable = runCatching { node.isScrollable }.getOrDefault(false),
                )
                results.add(info)
                runCatching { node.recycle() }
            }
            return results
        } finally {
            runCatching { root.recycle() }
        }
    }

    /** Metadata about a UI element, returned by [findAllElements]. */
    data class ElementInfo(
        val text: String,
        val contentDescription: String,
        val viewId: String,
        val bounds: IntArray,
        val centerX: Int,
        val centerY: Int,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
    )

    /**
     * Get the current foreground package name. Used by app-specific skill
     * profiles to look up known view IDs for the active app.
     */
    fun foregroundPackage(): String? {
        val svc = svc() as? AccessibilityService ?: return null
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return null
        try {
            return root.packageName?.toString()
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * Select text in the focused editable field. Uses ACTION_SET_SELECTION
     * with the given character range.
     */
    suspend fun selectText(start: Int, end: Int): Boolean {
        val svc = svc() as? AccessibilityService ?: return false
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return false
        var target: AccessibilityNodeInfo? = null
        try {
            target = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            if (target == null) {
                target = searchEngine.findFirst(root, searchEngine.editable)
            }
            if (target == null) return false
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }
            return runCatching {
                target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            }.getOrDefault(false)
        } finally {
            runCatching { target?.recycle() }
            runCatching { root.recycle() }
        }
    }

    /**
     * Copy the current selection to the clipboard via ACTION_COPY on the
     * focused field.
     */
    suspend fun copySelection(): Boolean {
        val svc = svc() as? AccessibilityService ?: return false
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return false
        var target: AccessibilityNodeInfo? = null
        try {
            target = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            if (target == null) {
                target = searchEngine.findFirst(root, searchEngine.editable)
            }
            if (target == null) return false
            // Select all first, then copy
            val selectAllArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
            }
            runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs) }
            return runCatching {
                target.performAction(AccessibilityNodeInfo.ACTION_COPY)
            }.getOrDefault(false)
        } finally {
            runCatching { target?.recycle() }
            runCatching { root.recycle() }
        }
    }

    /**
     * Find a node by text/content-description/view-ID match. 5-pass matching:
     *   1. Exact text match (case-insensitive)
     *   2. Exact content-description match
     *   3. Text contains query
     *   4. Content-description contains query
     *   5. View ID resource name contains query (e.g. "shutter_btn" matches "shutter")
     * Returns the node (caller owns it) or null.
     */
    private fun findNodeByText(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val q = query.trim().lowercase()
        if (q.isBlank()) return null
        // Pass 1: exact text
        searchEngine.findFirst(root) { node ->
            runCatching { node.text?.toString()?.equals(query, ignoreCase = true) == true }.getOrDefault(false)
        }?.let { return it }
        // Pass 2: exact content-description
        searchEngine.findFirst(root) { node ->
            runCatching { node.contentDescription?.toString()?.equals(query, ignoreCase = true) == true }.getOrDefault(false)
        }?.let { return it }
        // Pass 3: text contains
        searchEngine.findFirst(root) { node ->
            runCatching { node.text?.toString()?.lowercase()?.contains(q) == true }.getOrDefault(false)
        }?.let { return it }
        // Pass 4: content-description contains
        searchEngine.findFirst(root) { node ->
            runCatching { node.contentDescription?.toString()?.lowercase()?.contains(q) == true }.getOrDefault(false)
        }?.let { return it }
        // Pass 5: view ID resource name contains (multi-language fallback)
        searchEngine.findFirst(root) { node ->
            runCatching {
                node.viewIdResourceName?.lowercase()?.contains(q) == true
            }.getOrDefault(false)
        }?.let { return it }
        return null
    }

    /**
     * Tap a UI element by its text or content description — NO coordinates
     * required. This is far more reliable than tap(x,y) because it searches
     * the accessibility tree for a node matching the given text/description
     * and dispatches ACTION_CLICK on it directly.
     *
     * Matching strategy (in priority order):
     *   1. Exact text match (case-insensitive)
     *   2. Exact content-description match (case-insensitive)
     *   3. Text contains the query (case-insensitive)
     *   4. Content-description contains the query (case-insensitive)
     *
     * If a non-clickable node matches, we walk UP to the nearest clickable
     * ancestor and click that (common pattern: a TextView inside a clickable
     * FrameLayout).
     *
     * Returns true if a matching node was found AND clicked.
     */
    suspend fun tapElementByText(query: String): Boolean {
        val svc = svc() as? AccessibilityService ?: return false
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return false
        try {
            if (query.isBlank()) return false
            // Use the unified 5-pass matcher (includes view ID resource name
            // matching for multi-language support).
            val match = findNodeByText(root, query) ?: run {
                Log.i(TAG, "tapElementByText('$query'): no matching node found")
                return false
            }

            // If the matched node isn't clickable, walk up to the nearest
            // clickable ancestor (common pattern: TextView inside FrameLayout).
            var target = match
            var needsRecycle = false
            if (!runCatching { target.isClickable }.getOrDefault(false)) {
                var ancestor = runCatching { target.parent }.getOrNull()
                var hops = 0
                while (ancestor != null && hops < 5) {
                    if (runCatching { ancestor.isClickable }.getOrDefault(false)) break
                    val next = runCatching { ancestor.parent }.getOrNull()
                    if (next != null) runCatching { ancestor.recycle() }
                    ancestor = next
                    hops++
                }
                if (ancestor != null && runCatching { ancestor.isClickable }.getOrDefault(false)) {
                    runCatching { match.recycle() }
                    target = ancestor
                    needsRecycle = true
                }
            }

            val ok = performClickWithFocus(target)
            if (needsRecycle) runCatching { target.recycle() }
            else runCatching { target.recycle() }
            if (ok) {
                Log.i(TAG, "tapElementByText('$query'): clicked successfully")
                waitForStabilization()
            }
            return ok
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * Check if the screen changed after the last action. Used by the agent
     * loop's verification step to give the LLM feedback on whether its action
     * had an effect.
     *
     * Returns a [VerifyResult] with the before/after fingerprints and whether
     * the screen changed.
     */
    suspend fun verifyScreenChanged(beforeFingerprint: String): VerifyResult {
        val after = quickFingerprint()
        val changed = after != beforeFingerprint && after.isNotBlank()
        return VerifyResult(changed = changed, beforeFingerprint = beforeFingerprint, afterFingerprint = after)
    }

    /** Result of a post-action verification check. */
    data class VerifyResult(
        val changed: Boolean,
        val beforeFingerprint: String,
        val afterFingerprint: String,
    )

    /**
     * Capture a flat text snapshot of the current accessibility tree.
     *
     * Recovers gracefully from null roots (waits up to 800ms with 3 retries,
     * re-querying `rootInActiveWindow` each time). Returns a sentinel string
     * if the service is disconnected or the root remains null after retries.
     *
     * SNAPSHOT FORMAT: each clickable/scrollable node includes an explicit
     * "tap here: (x,y)" hint with its screen-center coordinates, so the LLM
     * can read the exact tap target directly from the observation without
     * guessing from a screenshot. This dramatically improves tap accuracy.
     */
    suspend fun snapshot(): String {
        val s = svc()
        if (s == null) {
            metrics.recordSnapshot(0)
            return "(accessibility service not connected)"
        }
        // S-L5: removed the redundant `s !is AccessibilityService` check —
        // svc() already returns AccessibilityService?, so a non-null s is
        // guaranteed to be an AccessibilityService. The check was dead code.
        val start = System.currentTimeMillis()
        val result = snapshotWithRootRecovery(s)
        metrics.recordSnapshot(System.currentTimeMillis() - start)
        return result
    }

    private suspend fun snapshotWithRootRecovery(svc: AccessibilityService): String {
        val policy = AccessibilityRetryPolicy.Default
        for (attempt in 0 until policy.maxAttempts) {
            val root = runCatching { svc.rootInActiveWindow }.getOrNull()
            if (root != null) {
                try {
                    val sb = StringBuilder()
                    appendNode(sb, root, 0)
                    return sb.toString()
                } finally {
                    runCatching { root.recycle() }
                }
            }
            diagnostics.recordNullRoot()
            // S-M6: honor the retry policy's backoff schedule instead of a
            // flat 50ms — matches GestureManager's behavior and lets the
            // root recover from a transient null (common mid-animation).
            delay(policy.delayForAttempt(attempt))
        }
        diagnostics.recordRootRecovery()
        return "(empty tree — root null after ${policy.maxAttempts} retries)"
    }

    /**
     * Dispatch a [DeviceAction]. Returns true on success.
     *
     * Before dispatching, this clears any blocking overlay (dialog, keyboard)
     * that would intercept the action. After dispatching, it waits for the
     * screen to stabilize so the next snapshot reflects the post-action state.
     */
    suspend fun dispatch(action: DeviceAction): Boolean {
        val svc = svc() as? AccessibilityService ?: return false
        // Pre-action: dismiss dialogs / keyboard that would intercept — but
        // only when they would actually block this specific action (M-02).
        clearBlockingOverlays(svc, action)

        return when (action) {
            is DeviceAction.Tap -> {
                val start = System.currentTimeMillis()
                // S-L4: reuse the cached GestureManager instead of building a
                // fresh one per dispatch — otherwise the per-instance gesture
                // stats reset to zero on every tap/swipe.
                val gm = gestureManager(svc)
                val ok = gm.tap(action.x, action.y)
                // NODE-CLICK FALLBACK: if the gesture tap was cancelled on all
                // retries (common during activity transitions, IME show/hide,
                // or on OEMs with aggressive gesture interception), try to
                // find the clickable node at (x,y) and dispatch ACTION_CLICK
                // on it directly. This is the same mechanism a TalkBack user
                // triggers when they "click" a focusable element — it bypasses
                // the gesture pipeline entirely and works even when
                // dispatchGesture is being cancelled. Without this fallback the
                // agent would report "tap failed" and the LLM would have no
                // recourse except retrying the exact same (doomed) gesture.
                val finalOk = if (ok) true else tapNodeAt(svc, action.x, action.y)
                metrics.recordTap(System.currentTimeMillis() - start, finalOk)
                if (finalOk) waitForStabilization()
                finalOk
            }
            is DeviceAction.Swipe -> {
                val start = System.currentTimeMillis()
                val gm = gestureManager(svc)
                val ok = gm.swipe(action.x1, action.y1, action.x2, action.y2)
                metrics.recordSwipe(System.currentTimeMillis() - start, ok)
                if (ok) waitForStabilization()
                ok
            }
            is DeviceAction.Drag -> {
                val start = System.currentTimeMillis()
                val gm = gestureManager(svc)
                val ok = gm.drag(action.x1, action.y1, action.x2, action.y2)
                metrics.recordSwipe(System.currentTimeMillis() - start, ok)
                if (ok) waitForStabilization()
                ok
            }
            // SCROLL: direction-based, screen-aware. Delegates to
            // GestureManager.scroll() which reads the real viewport size and
            // computes a swipe path that lands on scrollable content. This
            // replaces the old approach where the LLM had to emit exact pixel
            // coordinates for swipe() — which failed constantly because the
            // model either guessed wrong coordinates or used hardcoded values
            // that didn't match the actual screen size.
            is DeviceAction.Scroll -> {
                val start = System.currentTimeMillis()
                val gm = gestureManager(svc)
                val dir = parseScrollDirection(action.direction)
                val ok = if (dir != null) gm.scroll(dir, action.amount) else false
                metrics.recordSwipe(System.currentTimeMillis() - start, ok)
                if (ok) waitForStabilization()
                ok
            }
            is DeviceAction.Type -> {
                val ok = typeText(svc, action.text)
                metrics.recordType(ok)
                // Typing into a field usually opens the IME + triggers layout
                // shifts (suggestions bar appears, field scrolls into view).
                // Wait for stabilization so the next snapshot reflects the
                // post-type state — same rationale as tap/swipe/launch.
                if (ok) waitForStabilization()
                ok
            }
            is DeviceAction.Launch -> {
                val ok = launchPackage(svc, action.packageName)
                metrics.recordLaunch(ok)
                if (ok) waitForStabilization()
                ok
            }
            DeviceAction.Back -> {
                val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                metrics.recordBack()
                windowTracker.clearKeyboard()
                windowTracker.clearDialog()
                if (ok) waitForStabilization()
                ok
            }
            DeviceAction.Home -> {
                val ok = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                metrics.recordHome()
                if (ok) waitForStabilization()
                ok
            }
            DeviceAction.Screenshot -> true  // handled by the agent loop's vision path
            DeviceAction.NoOp -> true
        }
    }

    /**
     * Map a direction string ("up"/"down"/"left"/"right", case-insensitive)
     * to a [GestureManager.ScrollDirection]. Returns null for unrecognized
     * strings so the caller can surface a parse error instead of dispatching
     * a bogus scroll.
     */
    private fun parseScrollDirection(s: String): GestureManager.ScrollDirection? =
        when (s.lowercase().trim()) {
            "up" -> GestureManager.ScrollDirection.UP
            "down" -> GestureManager.ScrollDirection.DOWN
            "left" -> GestureManager.ScrollDirection.LEFT
            "right" -> GestureManager.ScrollDirection.RIGHT
            else -> null
        }

    /**
     * Fallback tap mechanism: find the clickable (or focusable) accessibility
     * node whose screen bounds contain (x, y) and dispatch [ACTION_CLICK] on
     * it directly, bypassing the gesture pipeline.
     *
     * This is used when [GestureManager.tap] fails (all retries cancelled) —
     * a common situation on OEMs with aggressive gesture interception, during
     * activity transitions, or when a system overlay briefly steals focus.
     * The node-click path uses the same accessibility action that TalkBack
     * uses for "activate" gestures, so it works even when dispatchGesture is
     * being cancelled.
     *
     * Walks the tree from the active root, finds the deepest node whose
     * bounds contain (x, y) and which is clickable (or, failing that, the
     * nearest focusable ancestor). Clicks it, then recycles every node it
     * touched. Returns true if a node was found AND the click action returned
     * true; false otherwise.
     *
     * If no clickable node is at the exact point, we also try the nearest
     * clickable node within a small tolerance radius (±40px) — handles cases
     * where the LLM's coordinates are slightly off-center on a small button.
     */
    private suspend fun tapNodeAt(svc: AccessibilityService, x: Int, y: Int): Boolean {
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return false
        try {
            // First pass: find a clickable node whose bounds contain (x, y).
            // Prefer the DEEPEST such node (closest to the leaf) so we click
            // the actual button, not its container.
            val exact = findDeepestNodeContaining(root, x, y, requireClickable = true)
            if (exact != null) {
                val clicked = performClickWithFocus(exact)
                runCatching { exact.recycle() }
                if (clicked) {
                    Log.i(TAG, "tapNodeAt($x,$y) succeeded via ACTION_CLICK (gesture fallback)")
                    return true
                }
            }
            // Second pass: tolerate a small offset — find the nearest clickable
            // node within ±40px of (x, y). The LLM's coordinates are often
            // derived from a screenshot and can be a few pixels off-center on
            // small targets (icons, checkboxes).
            val near = findNearestClickableNear(root, x, y, tolerance = 40)
            if (near != null) {
                val clicked = performClickWithFocus(near)
                runCatching { near.recycle() }
                if (clicked) {
                    Log.i(TAG, "tapNodeAt($x,$y) succeeded via nearest-clickable fallback")
                    return true
                }
            }
            return false
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * Perform a click on [node] with a focus-first strategy.
     *
     * Some views (EditText, CheckBox, custom views) require focus before
     * ACTION_CLICK works. We try:
     *   1. ACTION_FOCUS (best-effort — ignored if the node isn't focusable)
     *   2. ACTION_CLICK
     *   3. If ACTION_CLICK fails, try ACTION_LONG_CLICK (some views only
     *      respond to long-click)
     *   4. If all fail, try dispatching ACTION_CLICK on the node's parent
     *      (some click handlers are on the parent, not the leaf)
     *
     * Returns true if any action succeeded.
     */
    private fun performClickWithFocus(node: AccessibilityNodeInfo): Boolean {
        // Step 1: focus the node (best-effort).
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        // Step 2: try ACTION_CLICK.
        val clicked = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        if (clicked) return true
        // Step 3: try ACTION_LONG_CLICK (rare, but some views only respond to it).
        val longClicked = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }.getOrDefault(false)
        if (longClicked) return true
        // Step 4: try clicking the parent (some click handlers are on the
        // parent container, not the leaf node).
        val parent = runCatching { node.parent }.getOrNull()
        if (parent != null) {
            val parentClicked = runCatching {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            runCatching { parent.recycle() }
            if (parentClicked) return true
        }
        return false
    }

    /**
     * Find the deepest node in [root]'s subtree whose screen bounds contain
     * (x, y). When [requireClickable] is true, only returns nodes that report
     * [AccessibilityNodeInfo.isClickable] — so we don't accidentally "click"
     * a plain TextView (which has no click handler and the action is a no-op).
     *
     * Iterative DFS with explicit (node, depth) frames, depth-capped at 50,
     * node-count-capped at 2000 to avoid pathological trees. Collects every
     * node it opens so they can be recycled in a single pass at the end
     * (except the returned match, which the caller owns, and the root, which
     * the caller also owns).
     */
    private fun findDeepestNodeContaining(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        requireClickable: Boolean,
    ): AccessibilityNodeInfo? {
        data class Frame(val node: AccessibilityNodeInfo, val depth: Int)
        val stack = ArrayDeque<Frame>()
        val visited = HashSet<Int>(256)
        // Every node we obtain via getChild() is owned by this search and must
        // be recycled. The root is caller-owned (NOT in this list).
        val opened = ArrayList<AccessibilityNodeInfo>(128)
        var best: AccessibilityNodeInfo? = null
        var bestDepth = -1
        var nodesVisited = 0
        stack.addLast(Frame(root, 0))
        visited.add(System.identityHashCode(root))
        try {
            while (stack.isNotEmpty()) {
                if (nodesVisited >= 2000) break
                val (node, depth) = stack.removeLast()
                nodesVisited++
                val alive = runCatching { node.className != null }.getOrDefault(false)
                if (!alive) continue
                val rect = android.graphics.Rect()
                val hasBounds = runCatching { node.getBoundsInScreen(rect); true }.getOrDefault(false)
                if (hasBounds && rect.contains(x, y)) {
                    val clickable = runCatching { node.isClickable }.getOrDefault(false)
                    if ((!requireClickable || clickable) && depth > bestDepth) {
                        best = node
                        bestDepth = depth
                    }
                }
                // Expand children — deepest match wins, so always descend.
                if (depth < 50) {
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (i in 0 until childCount) {
                        val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                        val id = System.identityHashCode(child)
                        if (id in visited) { runCatching { child.recycle() }; continue }
                        visited.add(id)
                        opened.add(child)
                        stack.addLast(Frame(child, depth + 1))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findDeepestNodeContaining failed: ${e.message}")
        } finally {
            // Recycle every opened node EXCEPT the one we're returning.
            for (n in opened) {
                if (n !== best) runCatching { n.recycle() }
            }
        }
        return best
    }

    /**
     * Find the nearest clickable node to (x, y) within [tolerance] pixels.
     * Used as a second-pass fallback when no clickable node exactly contains
     * the target point — common when the LLM's coordinates are a few pixels
     * off-center on small targets.
     *
     * Returns the node (caller owns it; recycle when done) or null.
     */
    private fun findNearestClickableNear(
        root: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        tolerance: Int,
    ): AccessibilityNodeInfo? {
        data class Frame(val node: AccessibilityNodeInfo, val depth: Int)
        val stack = ArrayDeque<Frame>()
        val visited = HashSet<Int>(256)
        val opened = ArrayList<AccessibilityNodeInfo>(128)
        var best: AccessibilityNodeInfo? = null
        var bestDist = Int.MAX_VALUE
        var nodesVisited = 0
        val tolSq = tolerance * tolerance
        stack.addLast(Frame(root, 0))
        visited.add(System.identityHashCode(root))
        try {
            while (stack.isNotEmpty()) {
                if (nodesVisited >= 2000) break
                val (node, depth) = stack.removeLast()
                nodesVisited++
                val alive = runCatching { node.className != null }.getOrDefault(false)
                if (!alive) continue
                val clickable = runCatching { node.isClickable }.getOrDefault(false)
                if (clickable) {
                    val rect = android.graphics.Rect()
                    val hasBounds = runCatching { node.getBoundsInScreen(rect); true }.getOrDefault(false)
                    if (hasBounds) {
                        // Squared distance from (x,y) to the nearest edge of
                        // the rect (0 if inside). Cheaper than sqrt and fine
                        // for comparison.
                        val dx = maxOf(0, maxOf(rect.left - x, x - rect.right))
                        val dy = maxOf(0, maxOf(rect.top - y, y - rect.bottom))
                        val dist = dx * dx + dy * dy
                        if (dist < bestDist && dist <= tolSq) {
                            best = node
                            bestDist = dist
                        }
                    }
                }
                if (depth < 50) {
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (i in 0 until childCount) {
                        val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                        val id = System.identityHashCode(child)
                        if (id in visited) { runCatching { child.recycle() }; continue }
                        visited.add(id)
                        opened.add(child)
                        stack.addLast(Frame(child, depth + 1))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findNearestClickableNear failed: ${e.message}")
        } finally {
            for (n in opened) {
                if (n !== best) runCatching { n.recycle() }
            }
        }
        return best
    }

    /** Capture a screenshot as compressed bytes (WebP/PNG). */
    suspend fun screenshot(): ByteArray? {
        val svc = svc() as? OmniA11yLike ?: return null
        val start = System.currentTimeMillis()
        val bytes = runCatching { svc.screenshot() }.getOrNull()
        metrics.recordScreenshot(System.currentTimeMillis() - start)
        return bytes
    }

    // ---- Internal helpers ----

    /**
     * Dismiss dialogs and the soft keyboard before dispatching an action.
     *
     * Rationale: a tap at (x, y) that lands on a dialog's dimmed background
     * is silently swallowed. A tap that lands on the keyboard hits a key
     * instead of the field behind it. Pressing BACK clears both, and the
     * subsequent action lands on the intended target.
     *
     * We only press BACK when a dialog/keyboard is actually visible —
     * never speculatively — so we don't dismiss the user's legitimate
     * foreground activity.
     *
     * KEYBOARD-EDITABLE FIX: when a Tap targets an editable (EditText-like)
     * node, we do NOT dismiss the keyboard. The agent is almost certainly
     * trying to focus the field to type into it — dismissing the keyboard
     * first would close the IME, then the tap re-opens it, causing a visible
     * flicker AND a race where the tap lands during the IME's hide animation
     * and gets cancelled. By keeping the keyboard visible when the target is
     * editable, the tap cleanly focuses the field and the IME stays open.
     */
    private fun clearBlockingOverlays(svc: AccessibilityService, action: DeviceAction) {
        // Type actions target the focused field, which is often inside a
        // dialog. Pressing BACK first would dismiss the dialog the agent is
        // typing into, so never clear overlays for Type (M-02).
        if (action is DeviceAction.Type) return

        val state = windowTracker.current

        // For a tap/swipe aimed AT a visible dialog (e.g. "OK"/"Delete"),
        // dismissing the dialog first makes the gesture land on the app
        // behind it. When a dialog is showing it owns the active window, so
        // the active root's screen bounds are the dialog's bounds; only
        // dismiss when the gesture lands outside them.
        if (state.isDialogVisible && gestureInsideActiveWindow(svc, action)) {
            return
        }

        // KEYBOARD-EDITABLE FIX: if the keyboard is visible AND the action is
        // a Tap whose coordinates fall on an editable node, keep the keyboard.
        // The agent is trying to focus a text field — dismissing the IME here
        // would force a close+reopen cycle that often cancels the tap.
        if (state.isKeyboardVisible && action is DeviceAction.Tap) {
            if (tapTargetsEditable(svc, action.x, action.y)) {
                Log.d(TAG, "clearBlockingOverlays: keeping keyboard — tap targets editable node at (${action.x},${action.y})")
                // Still clear a dialog if one is visible (the keyboard + dialog
                // combo is rare but possible — e.g. a search dialog).
                if (!state.isDialogVisible) return
            }
        }

        if (state.isDialogVisible || state.isKeyboardVisible) {
            val ok = runCatching {
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }.getOrDefault(false)
            if (ok) {
                // S-H7: a single BACK press almost always dismisses only the
                // topmost layer (keyboard first, then dialog underneath).
                // Clearing both flags here desyncs the tracker from reality —
                // the next snapshot would still see the dialog and re-trigger
                // dismissal. Clear only the topmost layer; if both are visible
                // the next dispatch() call will handle the dialog.
                if (state.isKeyboardVisible) {
                    diagnostics.recordKeyboardDismissal()
                    windowTracker.clearKeyboard()
                    // Don't clear dialog — it may still be visible underneath.
                } else if (state.isDialogVisible) {
                    diagnostics.recordDialogDismissal()
                    windowTracker.clearDialog()
                }
            }
        }
    }

    /**
     * True if the tap target at (x, y) is an editable (EditText-like) node.
     * Used by [clearBlockingOverlays] to decide whether to keep the keyboard
     * visible — tapping a text field to focus it shouldn't close the IME.
     *
     * Does a lightweight single-point hit test via the accessibility tree:
     * finds the deepest node whose bounds contain (x, y) and checks
     * [AccessibilityNodeInfo.isEditable]. Recycles every node it touches.
     */
    private fun tapTargetsEditable(svc: AccessibilityService, x: Int, y: Int): Boolean {
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return false
        try {
            val node = findDeepestNodeContaining(root, x, y, requireClickable = false)
            if (node != null) {
                val editable = runCatching { node.isEditable }.getOrDefault(false)
                runCatching { node.recycle() }
                return editable
            }
            return false
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * True if [action] is a tap/swipe whose target coordinates fall inside the
     * active window's bounds. When a dialog is visible it owns the active
     * window, so this effectively tests whether the gesture targets the dialog
     * (and therefore must NOT be dismissed first). See M-02.
     */
    private fun gestureInsideActiveWindow(svc: AccessibilityService, action: DeviceAction): Boolean {
        val (x, y) = when (action) {
            is DeviceAction.Tap -> action.x to action.y
            is DeviceAction.Swipe -> action.x1 to action.y1
            else -> return false
        }
        return runCatching {
            val root = svc.rootInActiveWindow ?: return@runCatching false
            try {
                val bounds = android.graphics.Rect()
                root.getBoundsInScreen(bounds)
                bounds.contains(x, y)
            } finally {
                runCatching { root.recycle() }
            }
        }.getOrDefault(false)
    }

    /**
     * Wait for the screen to stabilize after an action.
     *
     * Polls the accessibility tree fingerprint every 100ms; once two
     * consecutive reads match (or after 1.5s), returns. This prevents the
     * next snapshot from capturing a mid-animation state.
     */
    private suspend fun waitForStabilization() {
        // S-M5: cap at 1500ms to match the docstring above (was 600ms).
        val cap = 1500L
        val start = System.currentTimeMillis()
        var prev = quickFingerprint()
        delay(50)
        val firstCheck = quickFingerprint()
        if (firstCheck == prev && firstCheck.isNotBlank()) return
        prev = firstCheck
        while (System.currentTimeMillis() - start < cap) {
            delay(50)
            val cur = quickFingerprint()
            if (cur == prev) return
            prev = cur
        }
    }

    private fun quickFingerprint(): String {
        val svc = svc() as? AccessibilityService ?: return ""
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return ""
        try {
            return "${root.packageName}:${root.childCount}"
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * Type text into the focused (or first editable) field.
     *
     * Uses [NodeSearchEngine] to find an editable node if none is focused.
     * Recycles all intermediate nodes. The target node is recycled after
     * the action.
     */
    private suspend fun typeText(svc: AccessibilityService, text: String): Boolean {
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return false
        var target: AccessibilityNodeInfo? = null
        try {
            // Try the focused node first (free — no search needed).
            target = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            if (target == null) {
                // No focus — find the first editable node.
                target = searchEngine.findFirst(root, searchEngine.editable)
            }
            if (target == null) {
                Log.w(TAG, "type(\"$text\") failed: no editable node found")
                return false
            }
            // Focus the node before setting text (some fields require focus).
            if (!target.isFocused) {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return runCatching {
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)
        } finally {
            runCatching { target?.recycle() }
            runCatching { root.recycle() }
        }
    }

    /**
     * Process-level cache of launchable app label -> package name, built lazily
     * with a single [android.content.pm.PackageManager.queryIntentActivities]
     * call (ACTION_MAIN + CATEGORY_LAUNCHER). Avoids 150+ per-app PackageManager
     * IPCs on the launch fallback path (H-05).
     */
    @Volatile
    private var launchableAppsCache: Map<String, String>? = null

    private fun launchableApps(svc: AccessibilityService): Map<String, String> {
        launchableAppsCache?.let { return it }
        val pm = svc.packageManager
        val map = runCatching {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    launcherIntent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
            val result = HashMap<String, String>(resolveInfos.size)
            for (info in resolveInfos) {
                val label = runCatching { info.loadLabel(pm).toString().lowercase() }.getOrNull() ?: continue
                val pkgName = info.activityInfo?.packageName ?: continue
                result.putIfAbsent(label, pkgName)
            }
            result
        }.getOrDefault(emptyMap())
        launchableAppsCache = map
        return map
    }

    private fun launchPackage(svc: AccessibilityService, packageNameOrAppName: String): Boolean {
        return try {
            val pm = svc.packageManager
            val cleanedInput = packageNameOrAppName.trim().trim('"', '\'').lowercase()

            // Map common app names to popular package names if passed by friendly name.
            // CRITICAL FIX (agent not performing tasks): expanded the knownMap with
            // multiple camera package names (different OEMs use different ones) and
            // more app aliases. The previous map only had "com.android.camera" for
            // camera — but Samsung uses "com.sec.android.app.camera", Pixel uses
            // "com.google.android.GoogleCamera", MIUI uses "com.android.camera",
            // etc. If the package wasn't found, launch() returned false and the
            // agent reported "error" with no diagnostic. Now we try multiple
            // package names for camera and fall back to launchableApps search.
            val knownMap = mapOf(
                "whatsapp" to "com.whatsapp",
                "youtube" to "com.google.android.youtube",
                "chrome" to "com.android.chrome",
                "browser" to "com.android.chrome",
                "settings" to "com.android.settings",
                "instagram" to "com.instagram.android",
                "facebook" to "com.facebook.katana",
                "twitter" to "com.twitter.android",
                "x" to "com.twitter.android",
                "telegram" to "org.telegram.messenger",
                "spotify" to "com.spotify.music",
                "maps" to "com.google.android.apps.maps",
                "gmail" to "com.google.android.gm",
                "calculator" to "com.google.android.calculator",
                "clock" to "com.google.android.deskclock",
                "photos" to "com.google.android.apps.photos",
                "gallery" to "com.google.android.apps.photos",
                // Camera — multiple OEM package names. The first one that
                // resolves via getLaunchIntentForPackage wins.
                "camera" to "com.android.camera",
                "google camera" to "com.google.android.GoogleCamera",
                // Note: Samsung's camera package is handled below via the
                // multi-try list since "com.sec.android.app.camera" won't
                // resolve on non-Samsung devices.
            )

            // Camera has multiple possible package names across OEMs. Try them
            // all when the user asks for "camera".
            val cameraPackages = listOf(
                "com.android.camera",
                "com.google.android.GoogleCamera",
                "com.sec.android.app.camera",          // Samsung
                "com.miui.camera",                      // Xiaomi MIUI
                "org.lineageos.camera",                 // LineageOS
                "com.android.camera2",                  // AOSP camera2
                "com.oppo.camera",                      // OPPO
                "com.coloros.camera",                   // ColorOS (Realme/OPPO)
            )

            val pkg = knownMap[cleanedInput] ?: packageNameOrAppName.trim().trim('"', '\'')

            // Try the resolved package first.
            var intent = pm.getLaunchIntentForPackage(pkg)

            // If camera and the first package didn't resolve, try the other
            // OEM camera packages.
            if (intent == null && cleanedInput == "camera") {
                for (cameraPkg in cameraPackages) {
                    intent = pm.getLaunchIntentForPackage(cameraPkg)
                    if (intent != null) break
                }
            }

            // If getLaunchIntentForPackage returned null and input looks like an
            // app label, search launchable apps via a single cached query
            // (ACTION_MAIN + CATEGORY_LAUNCHER) instead of iterating every
            // installed app with a per-app IPC (H-05).
            if (intent == null && !cleanedInput.contains('.')) {
                val apps = launchableApps(svc)
                val matchPkg = apps[cleanedInput]
                    ?: apps.entries.firstOrNull { (label, _) -> label.contains(cleanedInput) }?.value
                if (matchPkg != null) {
                    intent = pm.getLaunchIntentForPackage(matchPkg)
                }
            }

            if (intent == null) {
                Log.w(TAG, "launch($packageNameOrAppName) failed: no launch intent found (tried pkg='$pkg')")
                return false
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            svc.startActivity(intent)
            val prev = windowTracker.current.foregroundPackage
            if (prev != pkg) {
                diagnostics.recordPackageSwitch(prev, pkg)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "launch($packageNameOrAppName) failed: ${e.message}")
            false
        }
    }

    /**
     * Append a node and its children to [sb] as a flat text tree.
     *
     * Recycles every child node it obtains. The root [node] is NOT recycled
     * here (the caller owns it).
     */
    private fun appendNode(sb: StringBuilder, node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 50) return
        val pad = "  ".repeat(depth.coerceAtMost(8))
        val cls = runCatching { node.className?.toString()?.substringAfterLast('.') }.getOrNull() ?: "?"
        val text = runCatching { node.text?.toString().orEmpty().take(80) }.getOrDefault("")
        val contentDesc = runCatching { node.contentDescription?.toString().orEmpty().take(60) }.getOrDefault("")
        val rawId = runCatching { node.viewIdResourceName }.getOrNull()
        val pkg = runCatching { node.packageName?.toString() }.getOrNull().orEmpty()
        val id = if (rawId != null) logger.rebindRef(rawId, pkg) ?: rawId else ""
        sb.append("$pad- $cls")
        if (id.isNotBlank()) sb.append(" id=$id")
        if (text.isNotBlank()) sb.append(" text=\"$text\"")
        if (contentDesc.isNotBlank()) sb.append(" desc=\"$contentDesc\"")
        val clickable = runCatching { node.isClickable }.getOrDefault(false)
        val scrollable = runCatching { node.isScrollable }.getOrDefault(false)
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        if (clickable) sb.append(" [clickable]")
        if (scrollable) sb.append(" [scrollable]")
        if (editable) sb.append(" [editable]")
        // SNAPSHOT HINT: for clickable/scrollable/editable nodes, compute the
        // screen-center coordinates and emit an explicit "TAP:(cx,cy)" hint.
        // This lets the LLM read the exact tap target directly from the
        // observation without guessing from a screenshot — eliminating the
        // coordinate-accuracy problem.
        if (clickable || scrollable || editable) {
            val rect = android.graphics.Rect()
            runCatching { node.getBoundsInScreen(rect) }
            sb.append(" bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
            val cx = (rect.left + rect.right) / 2
            val cy = (rect.top + rect.bottom) / 2
            sb.append(" TAP:($cx,$cy)")
        }
        sb.appendLine()
        // S-M8: cap the number of children expanded per node to prevent
        // pathological trees (e.g. a RecyclerView with 10k items) from
        // blowing up the snapshot. Matches OmniAccessibilityService's caps.
        val childCap = if (depth < 10) 200 else if (depth < 20) 100 else 50
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        val limit = minOf(childCount, childCap)
        val children = ArrayList<AccessibilityNodeInfo>(limit)
        for (i in 0 until limit) {
            runCatching { node.getChild(i) }.getOrNull()?.let { children.add(it) }
        }
        children.forEach { appendNode(sb, it, depth + 1) }
        children.forEach { runCatching { it.recycle() } }
    }

    /** Typealias for the duck-typed screenshot capability of the service. */
    interface OmniA11yLike {
        suspend fun screenshot(): ByteArray?
    }

    companion object {
        private const val TAG = "A11yExecutor"
    }
}
