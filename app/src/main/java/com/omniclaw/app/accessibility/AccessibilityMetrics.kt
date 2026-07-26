package com.omniclaw.app.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects performance metrics from the accessibility subsystem.
 *
 * Every accessibility operation (snapshot, tap, swipe, type, launch, back,
 * home, screenshot) is timed and counted. The metrics are aggregated into
 * a [Snapshot] exposed via [snapshot] for the diagnostics UI.
 *
 * Thread safety: all counters use [AtomicLong]; the snapshot is a
 * consistent point-in-time read of all counters.
 */
@Singleton
class AccessibilityMetrics @Inject constructor() {

    private val snapshotCount = AtomicLong(0)
    private val snapshotTotalMs = AtomicLong(0)
    private val snapshotMaxMs = AtomicLong(0)

    private val tapCount = AtomicLong(0)
    private val tapSuccessCount = AtomicLong(0)
    private val tapTotalMs = AtomicLong(0)

    private val swipeCount = AtomicLong(0)
    private val swipeSuccessCount = AtomicLong(0)
    private val swipeTotalMs = AtomicLong(0)

    private val typeCount = AtomicLong(0)
    private val typeSuccessCount = AtomicLong(0)

    private val launchCount = AtomicLong(0)
    private val launchSuccessCount = AtomicLong(0)

    private val backCount = AtomicLong(0)
    private val homeCount = AtomicLong(0)

    private val screenshotCount = AtomicLong(0)
    private val screenshotTotalMs = AtomicLong(0)

    private val _lastSnapshot = MutableStateFlow(Snapshot())
    val lastSnapshot: StateFlow<Snapshot> = _lastSnapshot.asStateFlow()

    data class Snapshot(
        val snapshotCount: Long = 0,
        val snapshotAvgMs: Long = 0,
        val snapshotMaxMs: Long = 0,
        val tapCount: Long = 0,
        val tapSuccessRate: Float = 0f,
        val tapAvgMs: Long = 0,
        val swipeCount: Long = 0,
        val swipeSuccessRate: Float = 0f,
        val swipeAvgMs: Long = 0,
        val typeCount: Long = 0,
        val typeSuccessRate: Float = 0f,
        val launchCount: Long = 0,
        val launchSuccessRate: Float = 0f,
        val backCount: Long = 0,
        val homeCount: Long = 0,
        val screenshotCount: Long = 0,
        val screenshotAvgMs: Long = 0,
    )

    fun recordSnapshot(durationMs: Long) {
        snapshotCount.incrementAndGet()
        snapshotTotalMs.addAndGet(durationMs)
        snapshotMaxMs.accumulateAndGet(durationMs) { a, b -> maxOf(a, b) }
        publishSnapshot()
    }

    fun recordTap(durationMs: Long, success: Boolean) {
        tapCount.incrementAndGet()
        if (success) tapSuccessCount.incrementAndGet()
        tapTotalMs.addAndGet(durationMs)
        publishSnapshot()
    }

    fun recordSwipe(durationMs: Long, success: Boolean) {
        swipeCount.incrementAndGet()
        if (success) swipeSuccessCount.incrementAndGet()
        swipeTotalMs.addAndGet(durationMs)
        publishSnapshot()
    }

    fun recordType(success: Boolean) {
        typeCount.incrementAndGet()
        if (success) typeSuccessCount.incrementAndGet()
        publishSnapshot()
    }

    fun recordLaunch(success: Boolean) {
        launchCount.incrementAndGet()
        if (success) launchSuccessCount.incrementAndGet()
        publishSnapshot()
    }

    fun recordBack() { backCount.incrementAndGet(); publishSnapshot() }
    fun recordHome() { homeCount.incrementAndGet(); publishSnapshot() }

    fun recordScreenshot(durationMs: Long) {
        screenshotCount.incrementAndGet()
        screenshotTotalMs.addAndGet(durationMs)
        publishSnapshot()
    }

    private fun publishSnapshot() {
        _lastSnapshot.value = Snapshot(
            snapshotCount = snapshotCount.get(),
            snapshotAvgMs = if (snapshotCount.get() == 0L) 0 else snapshotTotalMs.get() / snapshotCount.get(),
            snapshotMaxMs = snapshotMaxMs.get(),
            tapCount = tapCount.get(),
            tapSuccessRate = if (tapCount.get() == 0L) 0f else tapSuccessCount.get().toFloat() / tapCount.get(),
            tapAvgMs = if (tapCount.get() == 0L) 0 else tapTotalMs.get() / tapCount.get(),
            swipeCount = swipeCount.get(),
            swipeSuccessRate = if (swipeCount.get() == 0L) 0f else swipeSuccessCount.get().toFloat() / swipeCount.get(),
            swipeAvgMs = if (swipeCount.get() == 0L) 0 else swipeTotalMs.get() / swipeCount.get(),
            typeCount = typeCount.get(),
            typeSuccessRate = if (typeCount.get() == 0L) 0f else typeSuccessCount.get().toFloat() / typeCount.get(),
            launchCount = launchCount.get(),
            launchSuccessRate = if (launchCount.get() == 0L) 0f else launchSuccessCount.get().toFloat() / launchCount.get(),
            backCount = backCount.get(),
            homeCount = homeCount.get(),
            screenshotCount = screenshotCount.get(),
            screenshotAvgMs = if (screenshotCount.get() == 0L) 0 else screenshotTotalMs.get() / screenshotCount.get(),
        )
    }

    fun reset() {
        snapshotCount.set(0); snapshotTotalMs.set(0); snapshotMaxMs.set(0)
        tapCount.set(0); tapSuccessCount.set(0); tapTotalMs.set(0)
        swipeCount.set(0); swipeSuccessCount.set(0); swipeTotalMs.set(0)
        typeCount.set(0); typeSuccessCount.set(0)
        launchCount.set(0); launchSuccessCount.set(0)
        backCount.set(0); homeCount.set(0)
        screenshotCount.set(0); screenshotTotalMs.set(0)
        _lastSnapshot.value = Snapshot()
    }
}
