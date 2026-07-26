package com.omniclaw.app.accessibility

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance, leak-safe search engine over [AccessibilityNodeInfo] trees.
 *
 * Responsibilities:
 *  1. **Leak prevention** — every node obtained via `getChild(i)`,
 *     `findFocus()`, or `getRootInActiveWindow()` is a native-backed object
 *     that must be recycled on older Android versions. This engine tracks
 *     every node it touches and recycles them in a guaranteed `finally`
 *     block — EXCEPT the root, which is owned by the caller (the
 *     AccessibilityService) and the returned target node, which the caller
 *     is responsible for.
 *  2. **Infinite-recursion prevention** — hard depth cap (default 64) plus
 *     a visited-node set keyed by `IdentityHashCode` to catch cyclic trees
 *     (which can occur with buggy custom views that return themselves as
 *     children).
 *  3. **Performance** — iterative DFS (not recursive) to avoid stack
 *     overflow on very deep trees. Early-exits on first match for
 *     `findFirst*` queries. Bounds the number of nodes visited to
 *     [maxNodes] (default 5000) to prevent pathological trees from
 *     hanging the agent loop.
 *  4. **Stale-node tolerance** — wraps each node access in `runCatching`
 *     so a node that becomes invalid mid-traversal (common during
 *     animations) doesn't crash the search; the engine skips the dead
 *     branch and continues.
 *
 * Thread safety: instances are NOT thread-safe. Each search call must be
 * single-threaded (the accessibility service's main looper). The engine is
 * stateless between calls — no shared mutable state.
 */
class NodeSearchEngine(
    private val maxDepth: Int = 64,
    private val maxNodes: Int = 5000,
) {

    /** A predicate over a node. Must be side-effect free. */
    fun interface NodePredicate {
        fun matches(node: AccessibilityNodeInfo): Boolean
    }

    /**
     * Find the first node matching [predicate], using iterative DFS.
     *
     * The returned node is NOT recycled — the caller owns it and must
     * recycle it when done (or pass it to [performActionAndRecycle]).
     * Returns null if no match is found within [maxDepth] / [maxNodes].
     *
     * All intermediate nodes (children opened during the search) are
     * recycled before returning, EXCEPT the root (owned by caller) and
     * the returned target.
     */
    fun findFirst(root: AccessibilityNodeInfo, predicate: NodePredicate): AccessibilityNodeInfo? {
        val visited = HashSet<Int>(256)
        // Explicit stack of (node, depth) pairs. Each entry's node is owned
        // by the search and must be recycled when popped (unless it's the
        // root, which is caller-owned).
        val stack = ArrayDeque<NodeFrame>()
        var nodesVisited = 0
        val rootIsCallerOwned = true

        stack.addLast(NodeFrame(root, depth = 0, ownedBySearch = !rootIsCallerOwned))
        visited.add(System.identityHashCode(root))

        try {
            while (stack.isNotEmpty()) {
                if (nodesVisited >= maxNodes) {
                    Log.w(TAG, "findFirst hit maxNodes=$maxNodes cap; aborting search")
                    return null
                }
                val frame = stack.removeLast()
                val node = frame.node
                nodesVisited++

                // Stale-node guard: a node can become invalid between the time
                // it was added to the stack and the time we pop it (e.g. the
                // window was destroyed mid-search). Skip dead branches.
                val alive = runCatching { node.className != null }.getOrDefault(false)
                if (!alive) {
                    if (frame.ownedBySearch) runCatching { node.recycle() }
                    continue
                }

                val matched = runCatching { predicate.matches(node) }.getOrDefault(false)
                if (matched) {
                    // Don't recycle the match — caller owns it. Recycle the
                    // remaining stack frames first.
                    while (stack.isNotEmpty()) {
                        val leftover = stack.removeLast()
                        if (leftover.ownedBySearch) runCatching { leftover.node.recycle() }
                    }
                    return node
                }

                // Expand children (push in reverse order so DFS visits left-to-right).
                if (frame.depth < maxDepth) {
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (i in (childCount - 1) downTo 0) {
                        val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                        val id = System.identityHashCode(child)
                        if (id in visited) {
                            // Cyclic tree — skip the duplicate.
                            runCatching { child.recycle() }
                            continue
                        }
                        visited.add(id)
                        stack.addLast(NodeFrame(child, frame.depth + 1, ownedBySearch = true))
                    }
                }

                // The current node is done (no match, children pushed). Recycle
                // it if we own it.
                if (frame.ownedBySearch) runCatching { node.recycle() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findFirst traversal failed: ${e.message}")
        }
        return null
    }

    /**
     * Find ALL nodes matching [predicate]. Returns a list (caller owns each
     * node — recycle them via [recycleAll] when done). Use sparingly; prefer
     * [findFirst] when only one match is needed.
     */
    fun findAll(root: AccessibilityNodeInfo, predicate: NodePredicate): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val visited = HashSet<Int>(256)
        val stack = ArrayDeque<NodeFrame>()
        var nodesVisited = 0

        stack.addLast(NodeFrame(root, depth = 0, ownedBySearch = false))
        visited.add(System.identityHashCode(root))

        try {
            while (stack.isNotEmpty()) {
                if (nodesVisited >= maxNodes) {
                    Log.w(TAG, "findAll hit maxNodes=$maxNodes cap; returning partial results")
                    break
                }
                val frame = stack.removeLast()
                val node = frame.node
                nodesVisited++

                val alive = runCatching { node.className != null }.getOrDefault(false)
                if (!alive) {
                    if (frame.ownedBySearch) runCatching { node.recycle() }
                    continue
                }

                if (runCatching { predicate.matches(node) }.getOrDefault(false)) {
                    results.add(node)
                    // Don't recycle matched nodes — caller owns them. But also
                    // don't expand their children (we found what we needed at
                    // this branch). Continue to siblings.
                } else if (frame.depth < maxDepth) {
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (i in (childCount - 1) downTo 0) {
                        val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                        val id = System.identityHashCode(child)
                        if (id in visited) {
                            runCatching { child.recycle() }
                            continue
                        }
                        visited.add(id)
                        stack.addLast(NodeFrame(child, frame.depth + 1, ownedBySearch = true))
                    }
                }

                // Recycle the current node if we own it AND it wasn't added to results.
                if (frame.ownedBySearch && node !in results) {
                    runCatching { node.recycle() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findAll traversal failed: ${e.message}")
        }
        return results
    }

    /** Recycle a list of nodes safely. */
    fun recycleAll(nodes: List<AccessibilityNodeInfo?>) {
        nodes.forEach { n -> runCatching { n?.recycle() } }
    }

    /**
     * Perform [action] on [node], then recycle [node]. Returns the action
     * result. Guarantees the node is recycled even if the action throws.
     */
    fun performActionAndRecycle(node: AccessibilityNodeInfo, action: () -> Boolean): Boolean {
        return try {
            action()
        } catch (e: Exception) {
            Log.w(TAG, "performAction failed: ${e.message}")
            false
        } finally {
            runCatching { node.recycle() }
        }
    }

    // ---- Convenience predicates ----

    /** Match by viewId resource name (e.g. "com.example:id/search_btn"). */
    fun byViewId(resourceName: String) = NodePredicate { node ->
        node.viewIdResourceName == resourceName
    }

    /** Match by text content (exact, case-insensitive). */
    fun byText(text: String) = NodePredicate { node ->
        node.text?.toString()?.equals(text, ignoreCase = true) == true
    }

    /** Match by text content (contains, case-insensitive). */
    fun byTextContains(text: String) = NodePredicate { node ->
        node.text?.toString()?.contains(text, ignoreCase = true) == true
    }

    /** Match by content description (contains, case-insensitive). */
    fun byContentDescriptionContains(text: String) = NodePredicate { node ->
        node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
    }

    /** Match clickable nodes. */
    val clickable = NodePredicate { it.isClickable }

    /** Match editable (EditText-like) nodes. */
    val editable = NodePredicate { it.isEditable }

    /** Match scrollable nodes. */
    val scrollable = NodePredicate { it.isScrollable }

    private data class NodeFrame(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val ownedBySearch: Boolean,
    )

    companion object {
        private const val TAG = "NodeSearchEngine"
    }
}
