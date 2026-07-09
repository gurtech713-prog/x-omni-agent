package com.omniclaw.app.ui.schedule

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omniclaw.app.cron.ScheduledTaskWorker
import com.omniclaw.app.data.model.ScheduledTask
import com.omniclaw.app.data.model.ScheduledTask.ScheduleKind
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(ScheduledTask.serializer())
    private val storeFile: File by lazy { File(ctx.filesDir, "scheduled_tasks.json") }

    private val _tasks = MutableStateFlow(loadOrSeed())
    val tasks: StateFlow<List<ScheduledTask>> = _tasks.asStateFlow()

    fun create(task: ScheduledTask) {
        _tasks.value = listOf(task) + _tasks.value
        persist()
        if (task.enabled) schedule(task)
    }

    /** Update an existing task in-place (title, prompt, schedule, enabled). */
    fun update(updated: ScheduledTask) {
        _tasks.value = _tasks.value.map { if (it.id == updated.id) updated else it }
        persist()
        // Reschedule if enabled, cancel if newly disabled.
        if (updated.enabled) schedule(updated)
        else ScheduledTaskWorker.cancel(ctx, updated.id)
    }

    fun toggle(id: String) {
        _tasks.value = _tasks.value.map {
            if (it.id == id) {
                val updated = it.copy(enabled = !it.enabled)
                if (updated.enabled) schedule(updated) else ScheduledTaskWorker.cancel(ctx, updated.id)
                updated
            } else it
        }
        persist()
    }

    fun delete(id: String) {
        ScheduledTaskWorker.cancel(ctx, id)
        _tasks.value = _tasks.value.filterNot { it.id == id }
        persist()
    }

    /**
     * Update run stats (lastRunAt, nextRunAt, runCount) after a task fires.
     * Called by [ScheduledTaskWorker] via a future hook. Persisted so the
     * stats survive app restarts.
     */
    fun recordRun(id: String, nextRunAt: Long?) {
        _tasks.value = _tasks.value.map {
            if (it.id == id) it.copy(
                lastRunAt = System.currentTimeMillis(),
                nextRunAt = nextRunAt,
                runCount = it.runCount + 1,
            ) else it
        }
        persist()
    }

    private fun schedule(t: ScheduledTask) {
        when (t.scheduleKind) {
            ScheduleKind.INTERVAL -> {
                val minutes = (t.intervalMinutes ?: 60).toLong().coerceAtLeast(15)
                ScheduledTaskWorker.scheduleInterval(ctx, t.id, t.title, t.prompt, minutes)
            }
            ScheduleKind.WEEKLY -> {
                ScheduledTaskWorker.scheduleWeekly(ctx, t.id, t.title, t.prompt, t.weekdays, t.timeOfDay)
            }
            ScheduleKind.WEEKDAY -> {
                // Mon-Fri (2..6) at the specified time
                ScheduledTaskWorker.scheduleWeekly(ctx, t.id, t.title, t.prompt, setOf(2, 3, 4, 5, 6), t.timeOfDay)
            }
        }
    }

    // ---- Persistence ----
    // Tasks are serialized to filesDir/scheduled_tasks.json so they survive
    // app restarts. Previously the list was in-memory only (seeded fresh on
    // every launch), so any task the user created/edited/deleted was lost on
    // restart and the seed list reappeared.

    private fun loadOrSeed(): List<ScheduledTask> {
        val file = storeFile
        if (!file.exists()) return seed()
        return runCatching {
            json.decodeFromString(serializer, file.readText())
        }.getOrDefault(seed())
    }

    private fun persist() {
        val file = storeFile
        // Offload the disk write to IO so we never block the UI thread on
        // an edit. The StateFlow is already updated synchronously, so the
        // UI is immediate; the file is just the durable backup.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                file.writeText(json.encodeToString(serializer, _tasks.value))
            }
        }
    }

    private fun seed(): List<ScheduledTask> {
        val now = System.currentTimeMillis()
        return listOf(
            ScheduledTask(
                id = UUID.randomUUID().toString().take(8),
                title = "Wednesday Reddit budget travel digest",
                scheduleKind = ScheduleKind.WEEKLY,
                weekdays = setOf(4),  // Calendar.WEDNESDAY = 4 (Sun=1)
                timeOfDay = "10:00",
                enabled = true,
                prompt = "Open Reddit, search 'budget travel tips', summarize the top 3 posts.",
                nextRunAt = now + 12 * 3600_000L,
                runCount = 4,
            ),
            ScheduledTask(
                id = UUID.randomUUID().toString().take(8),
                title = "Every weekday 9:00 morning briefing",
                scheduleKind = ScheduleKind.WEEKDAY,
                timeOfDay = "09:00",
                enabled = false,
                prompt = "Read the previous day's notifications and summarize the 5 most important items.",
                runCount = 0,
            ),
            ScheduledTask(
                id = UUID.randomUUID().toString().take(8),
                title = "Hourly battery + storage snapshot",
                scheduleKind = ScheduleKind.INTERVAL,
                intervalMinutes = 60,
                timeOfDay = "",
                enabled = true,
                prompt = "Take a screenshot of the home screen and log the current battery + storage usage to memory.",
                lastRunAt = now - 30 * 60_000L,
                nextRunAt = now + 30 * 60_000L,
                runCount = 38,
            ),
        )
    }
}
