package com.omniclaw.app.ui.schedule

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Serializes durable IO writes so a rapid sequence of create/update/delete
    // can't interleave on the IO dispatcher (U-H6). The StateFlow is already
    // updated synchronously, so the UI is immediate; this only guards the
    // file backup.
    private val persistMutex = Mutex()

    private val _tasks = MutableStateFlow<List<ScheduledTask>>(emptyList())
    val tasks: StateFlow<List<ScheduledTask>> = _tasks.asStateFlow()

    init {
        // Load off the Main thread: the VM constructor must not do blocking
        // file I/O + JSON decoding (audit H-30).
        viewModelScope.launch(Dispatchers.IO) {
            _tasks.value = loadOrSeed()
        }
    }

    fun create(task: ScheduledTask) {
        Log.i(TAG, "Creating new scheduled task: ID=${task.id}, title='${task.title}'")
        _tasks.value = listOf(task) + _tasks.value
        persist()
        if (task.enabled) {
            Log.d(TAG, "Task ${task.id} is enabled; scheduling now")
            viewModelScope.launch(Dispatchers.IO) { schedule(task) }
        }
    }

    /** Update an existing task in-place (title, prompt, schedule, enabled). */
    fun update(updated: ScheduledTask) {
        Log.i(TAG, "Updating scheduled task: ID=${updated.id}, title='${updated.title}'")
        _tasks.value = _tasks.value.map { if (it.id == updated.id) updated else it }
        persist()
        // Reschedule if enabled, cancel if newly disabled.
        if (updated.enabled) {
            Log.d(TAG, "Task ${updated.id} is enabled; scheduling/updating schedule")
            viewModelScope.launch(Dispatchers.IO) { schedule(updated) }
        } else {
            Log.d(TAG, "Task ${updated.id} is disabled; cancelling schedule")
            ScheduledTaskWorker.cancel(ctx, updated.id)
        }
    }

    fun toggle(id: String) {
        Log.i(TAG, "Toggling scheduled task: ID=$id")
        _tasks.value = _tasks.value.map {
            if (it.id == id) {
                val updated = it.copy(enabled = !it.enabled)
                Log.d(TAG, "Task $id toggled state. New enabled: ${updated.enabled}")
                if (updated.enabled) {
                    viewModelScope.launch(Dispatchers.IO) { schedule(updated) }
                } else {
                    ScheduledTaskWorker.cancel(ctx, updated.id)
                }
                updated
            } else it
        }
        persist()
    }

    fun delete(id: String) {
        Log.i(TAG, "Deleting scheduled task: ID=$id")
        ScheduledTaskWorker.cancel(ctx, id)
        _tasks.value = _tasks.value.filterNot { it.id == id }
        persist()
    }

    /**
     * Update run stats (lastRunAt, nextRunAt, runCount) after a task fires.
     *
     * `suspend` so the caller (`ScheduledTaskWorker`, wired by Task 1) can
     * invoke it from its own coroutine scope without forcing a nested
     * viewModelScope.launch on every fire (U-C4). Persisted so the stats
     * survive app restarts.
     */
    suspend fun recordRun(id: String, nextRunAt: Long?) {
        Log.i(TAG, "Recording execution run for task ID=$id. Next run scheduled at $nextRunAt")
        _tasks.update { tasks ->
            tasks.map { if (it.id == id) it.copy(lastRunAt = System.currentTimeMillis(), nextRunAt = nextRunAt, runCount = it.runCount + 1) else it }
        }
        persist()
    }

    private fun schedule(t: ScheduledTask) {
        Log.d(TAG, "Scheduling task ${t.id} (kind: ${t.scheduleKind})")
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
        if (!file.exists()) {
            Log.d(TAG, "Durable task storage file not found. Seeding initial scheduled tasks.")
            return seed()
        }
        return runCatching {
            val content = file.readText()
            val tasks = json.decodeFromString(serializer, content)
            Log.d(TAG, "Successfully loaded ${tasks.size} tasks from storage file.")
            tasks
        }.getOrElse {
            Log.e(TAG, "Error loading tasks from file, seeding defaults instead: ${it.message}", it)
            seed()
        }
    }

    private fun persist() {
        // Capture the list snapshot BEFORE launching the IO write — otherwise
        // a rapid sequence of edits (create, update, delete) could each read
        // a different _tasks.value by the time the IO dispatcher runs the
        // write, and the last writer would win with a stale view. The
        // StateFlow is already updated synchronously, so the UI is immediate;
        // this file is just the durable backup. The persistMutex (U-H6)
        // serializes the writes so two concurrent updates can't interleave.
        val snapshot = _tasks.value
        val file = storeFile
        viewModelScope.launch(Dispatchers.IO) {
            persistMutex.withLock {
                runCatching {
                    file.writeText(json.encodeToString(serializer, snapshot))
                    Log.d(TAG, "Persisted ${snapshot.size} tasks to storage file.")
                }.onFailure {
                    Log.e(TAG, "Failed to persist tasks to storage file: ${it.message}", it)
                }
            }
        }
    }

    private fun seed(): List<ScheduledTask> {
        // Seed all tasks with enabled = false (U-M12) so the very first launch
        // doesn't immediately start scheduling background work the user didn't
        // opt into. The user can flip the toggle per task after reviewing the
        // prompt directive.
        val now = System.currentTimeMillis()
        return listOf(
            ScheduledTask(
                id = UUID.randomUUID().toString().take(8),
                title = "Wednesday Reddit budget travel digest",
                scheduleKind = ScheduleKind.WEEKLY,
                weekdays = setOf(4),  // Calendar.WEDNESDAY = 4 (Sun=1)
                timeOfDay = "10:00",
                enabled = false,
                prompt = "Open Reddit, search 'budget travel tips', summarize the top 3 posts.",
                runCount = 0,
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
                enabled = false,
                prompt = "Take a screenshot of the home screen and log the current battery + storage usage to memory.",
                runCount = 0,
            ),
        )
    }

    companion object {
        private const val TAG = "ScheduleViewModel"
    }
}
