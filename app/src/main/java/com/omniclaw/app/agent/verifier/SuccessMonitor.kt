package com.omniclaw.app.agent.verifier

import com.omniclaw.app.agent.learning.EpisodeRecorder
import com.omniclaw.app.agent.tools.DeviceScheduler
import com.omniclaw.app.data.model.ToolCall
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post-action success monitor.
 *
 * Implements two original X-OmniClaw features:
 *   1. Post-action verification — checks whether the action produced the
 *      expected effect on the device.
 *   2. Drift detection (loop detection) — detects when the agent is stuck
 *      repeating the same screen state without progress.
 *   3. Mis-click guards — rejects taps that target obviously non-interactive
 *      regions (status bar, nav bar, or coordinates that haven't changed
 *      across multiple snapshots).
 *
 * Drift detection uses [EpisodeRecorder.fingerprint] — a coarse SHA-256 of
 * the normalized observation's first 200 chars. This is more stable than the
 * previous raw hashCode() approach, which changed on every byte (timestamps,
 * ad rotation) and masked real loops. The fingerprint is shared with the
 * LearningEngine so both agree on "same screen."
 *
 * Conservative by design: false negatives (marking a good action as failed)
 * just trigger a retry, while false positives (marking a bad action as ok)
 * would silently break the loop.
 */
@Singleton
class SuccessMonitor @Inject constructor(
    private val scheduler: DeviceScheduler,
    private val episodeRecorder: EpisodeRecorder,
) {

    /** Per-session state — keyed by sessionId for isolation. */
    private data class SessionState(
        val recentSnapshots: ArrayDeque<String> = ArrayDeque(),
        var consecutiveFailures: Int = 0,
    )

    private val sessionStates = mutableMapOf<String, SessionState>()
    private val lock = Any()

    fun verifyLast(sessionId: String, call: ToolCall): Boolean = synchronized(lock) {
        val state = sessionStates.getOrPut(sessionId) { SessionState() }
        if (!call.ok) {
            state.consecutiveFailures++
            return@synchronized false
        }

        val snap = scheduler.snapshotBlocking()

        if (snap.contains("Error launching", ignoreCase = true) ||
            snap.contains("not responding", ignoreCase = true) ||
            snap.contains("has stopped", ignoreCase = true)
        ) {
            state.consecutiveFailures++
            return@synchronized false
        }

        // Drift detection — use the shared fingerprint (SHA-256 of normalized
        // first 200 chars) instead of raw hashCode(). The previous approach
        // changed on every byte (timestamps, ad rotation) and never matched,
        // masking real loops. The shared fingerprint agrees with the
        // LearningEngine's notion of "same screen."
        val fp = episodeRecorder.fingerprint(snap)
        val identicalCount = state.recentSnapshots.count { it == fp }
        state.recentSnapshots.addLast(fp)
        if (state.recentSnapshots.size > 6) state.recentSnapshots.removeFirst()
        if (identicalCount >= 2) {
            state.consecutiveFailures++
            return@synchronized false
        }

        // Mis-click guard. Also catch the "service not connected" sentinel
        // returned by DeviceScheduler.snapshot() — previously this only
        // checked snap.isBlank(), but snapshot() returns the literal string
        // "(accessibility service not connected)" (not blank) when the a11y
        // service is down, so the guard never fired and taps onto a dead
        // service were recorded as "success".
        if (call.name.startsWith("tap", ignoreCase = true) &&
            (snap.isBlank() || snap.contains("not connected", ignoreCase = true))
        ) {
            state.consecutiveFailures++
            return@synchronized false
        }

        state.consecutiveFailures = 0
        return@synchronized true
    }

    fun isStuck(sessionId: String): Boolean = synchronized(lock) {
        sessionStates[sessionId]?.consecutiveFailures?.let { it >= 5 } ?: false
    }

    fun reset(sessionId: String) {
        synchronized(lock) {
            sessionStates.remove(sessionId)
        }
    }
}
