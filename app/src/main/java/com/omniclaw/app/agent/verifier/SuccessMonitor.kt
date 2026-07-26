package com.omniclaw.app.agent.verifier

import com.omniclaw.app.agent.learning.EpisodeRecorder
import com.omniclaw.app.agent.tools.DeviceScheduler
import com.omniclaw.app.data.model.ToolCall
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post-action success monitor.
 *
 * Implements three original X-OmniClaw features:
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

    /**
     * Verify the last action result, with optional pre-snapped observation.
     *
     * PERFORMANCE: When [preSnapshot] is provided (non-null), this method
     * reuses the observation the agent loop already captured earlier in the
     * step, avoiding a redundant call to [scheduler.snapshotBlocking()] which
     * traverses the full accessibility tree a second time.
     *
     * Without this cache, every step did TWO full tree traversals:
     *   1. scheduler.snapshot() at step start for the LLM observation
     *   2. scheduler.snapshotBlocking() here for verification
     */
    /**
     * Typed verification outcome. [reason] is a stable machine-readable code the
     * agent feeds back to the LLM as a GROUNDED error observation (Hermes-style
     * self-correction) instead of a generic "previous action failed" string.
     * [postFingerprint] is the screen fingerprint AFTER the action, so the agent
     * can reason about whether / how the screen changed.
     */
    data class VerifyResult(
        val ok: Boolean,
        val reason: String,
        val postFingerprint: String,
    )

    /** Backwards-compatible boolean facade over [verifyLastDetailed]. */
    fun verifyLast(sessionId: String, call: ToolCall, preSnapshot: String? = null): Boolean =
        verifyLastDetailed(sessionId, call, preSnapshot).ok

    /**
     * Verify the last action and return a typed [VerifyResult] with a diagnostic
     * reason code. Reason codes: dispatch_failed, app_error_or_anr,
     * no_screen_drift, misclick_or_dead_service, ok.
     */
    fun verifyLastDetailed(sessionId: String, call: ToolCall, preSnapshot: String? = null): VerifyResult = synchronized(lock) {
        val state = sessionStates.getOrPut(sessionId) { SessionState() }
        val preFp = preSnapshot?.let { episodeRecorder.fingerprint(it) }.orEmpty()
        if (!call.ok) {
            state.consecutiveFailures++
            return@synchronized VerifyResult(false, "dispatch_failed", preFp)
        }

        val snap = preSnapshot ?: scheduler.snapshotBlocking()

        if (snap.contains("Error launching", ignoreCase = true) ||
            snap.contains("not responding", ignoreCase = true) ||
            snap.contains("has stopped", ignoreCase = true)
        ) {
            state.consecutiveFailures++
            return@synchronized VerifyResult(false, "app_error_or_anr", episodeRecorder.fingerprint(snap))
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
            return@synchronized VerifyResult(false, "no_screen_drift", fp)
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
            return@synchronized VerifyResult(false, "misclick_or_dead_service", fp)
        }

        state.consecutiveFailures = 0
        return@synchronized VerifyResult(true, "ok", fp)
    }

    fun isStuck(sessionId: String, threshold: Int = 5): Boolean = synchronized(lock) {
        sessionStates[sessionId]?.consecutiveFailures?.let { it >= threshold } ?: false
    }

    fun reset(sessionId: String) {
        synchronized(lock) {
            sessionStates.remove(sessionId)
        }
    }
}
