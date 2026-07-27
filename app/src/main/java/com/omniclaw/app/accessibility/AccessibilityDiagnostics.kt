package com.omniclaw.app.accessibility

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects runtime diagnostics from the accessibility subsystem: service
 * connection state, root-node recovery attempts, stale-node detections,
 * and a rolling event log for debugging.
 *
 * Exposed as a [StateFlow] so the Settings screen can show live diagnostics
 * ("Service: Connected, 3 root recoveries in last 100 steps, 0 stale nodes").
 *
 * Thread safety: all counters are atomic; the event deque is concurrent.
 */
@Singleton
class AccessibilityDiagnostics @Inject constructor() {

    enum class ServiceState { DISCONNECTED, CONNECTING, CONNECTED, RESTARTING, FAILED }

    private val _state = MutableStateFlow(ServiceState.DISCONNECTED)
    val serviceState: StateFlow<ServiceState> = _state.asStateFlow()

    private val rootRecoveries = AtomicLong(0)
    private val staleNodeDetections = AtomicLong(0)
    private val nullRootCount = AtomicLong(0)
    private val dialogDismissals = AtomicLong(0)
    private val keyboardDismissals = AtomicLong(0)
    private val packageSwitches = AtomicLong(0)

    private val eventLog = ConcurrentLinkedDeque<DiagnosticEvent>()
    private val maxLogSize = 200
    private val eventLogSize = AtomicInteger(0)

    data class DiagnosticEvent(
        val timestamp: Long,
        val category: String,
        val message: String,
        val severity: Severity,
    ) {
        enum class Severity { INFO, WARN, ERROR }
    }

    data class Snapshot(
        val serviceState: ServiceState,
        val rootRecoveries: Long,
        val staleNodeDetections: Long,
        val nullRootCount: Long,
        val dialogDismissals: Long,
        val keyboardDismissals: Long,
        val packageSwitches: Long,
        val recentEvents: List<DiagnosticEvent>,
    )

    fun setState(state: ServiceState) {
        _state.value = state
        log("service", "State -> $state", DiagnosticEvent.Severity.INFO)
    }

    fun recordRootRecovery() {
        rootRecoveries.incrementAndGet()
        log("root", "Root node recovered after null/stale", DiagnosticEvent.Severity.WARN)
    }

    fun recordStaleNode() {
        staleNodeDetections.incrementAndGet()
    }

    fun recordNullRoot() {
        nullRootCount.incrementAndGet()
        log("root", "rootInActiveWindow returned null", DiagnosticEvent.Severity.WARN)
    }

    fun recordDialogDismissal() {
        dialogDismissals.incrementAndGet()
        log("window", "Dialog dismissed via BACK", DiagnosticEvent.Severity.INFO)
    }

    fun recordKeyboardDismissal() {
        keyboardDismissals.incrementAndGet()
        log("window", "Keyboard dismissed via BACK", DiagnosticEvent.Severity.INFO)
    }

    fun recordPackageSwitch(from: String?, to: String?) {
        packageSwitches.incrementAndGet()
        log("window", "Package switch: $from -> $to", DiagnosticEvent.Severity.INFO)
    }

    fun log(category: String, message: String, severity: DiagnosticEvent.Severity = DiagnosticEvent.Severity.INFO) {
        val event = DiagnosticEvent(System.currentTimeMillis(), category, message, severity)
        eventLog.addFirst(event)
        eventLogSize.incrementAndGet()
        while (eventLogSize.get() > maxLogSize) { eventLog.pollLast(); eventLogSize.decrementAndGet() }
        val priority = when (severity) {
            DiagnosticEvent.Severity.INFO -> Log.INFO
            DiagnosticEvent.Severity.WARN -> Log.WARN
            DiagnosticEvent.Severity.ERROR -> Log.ERROR
        }
        Log.println(priority, TAG, "[$category] $message")
    }

    fun snapshot(): Snapshot = Snapshot(
        serviceState = _state.value,
        rootRecoveries = rootRecoveries.get(),
        staleNodeDetections = staleNodeDetections.get(),
        nullRootCount = nullRootCount.get(),
        dialogDismissals = dialogDismissals.get(),
        keyboardDismissals = keyboardDismissals.get(),
        packageSwitches = packageSwitches.get(),
        recentEvents = eventLog.toList(),
    )

    /** Reset all counters (for tests). */
    fun reset() {
        rootRecoveries.set(0)
        staleNodeDetections.set(0)
        nullRootCount.set(0)
        dialogDismissals.set(0)
        keyboardDismissals.set(0)
        packageSwitches.set(0)
        eventLog.clear()
        eventLogSize.set(0)
        _state.value = ServiceState.DISCONNECTED
    }

    companion object {
        private const val TAG = "A11yDiagnostics"
    }
}
