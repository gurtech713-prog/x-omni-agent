package com.omniclaw.app.cron

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.omniclaw.app.agent.AgentLoop
import com.omniclaw.app.data.session.SessionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Executes a scheduled automation task. Mirrors the original X-OmniClaw
 * scheduled-automation feature: fires the task prompt into a fresh agent
 * session via the shared AgentLoop, then waits for the session to reach a
 * terminal state (DONE / FAILED / STOPPED) before returning Result.success().
 *
 * Works screen-on or screen-off — WorkManager handles deferred execution
 * under Doze if needed.
 *
 * @HiltWorker annotation tells Hilt's WorkerFactory how to construct this
 * worker via the @AssistedFactory interface. Without it, WorkManager falls
 * back to the default WorkerFactory which requires a zero-arg constructor
 * (this class has none) and crashes with WorkerCreationException.
 */
@HiltWorker
class ScheduledTaskWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val agentLoop: AgentLoop,
    private val sessions: SessionRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TASK_TITLE) ?: "Scheduled task"
        val prompt = inputData.getString(KEY_PROMPT) ?: return Result.failure()
        if (prompt.isBlank()) return Result.failure()

        // For weekly/weekday tasks: check if today is a target weekday before firing.
        // This guards against the 24h periodic re-firing on non-target days.
        val weekdaysStr = inputData.getString(KEY_WEEKDAYS)
        if (!weekdaysStr.isNullOrBlank()) {
            val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            val targetDays = weekdaysStr.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
            if (today !in targetDays) {
                // Not a target weekday — skip this run, WorkManager will re-fire tomorrow.
                return Result.success(workDataOf(KEY_TASK_ID to taskId, "skipped" to true))
            }
        }

        // ---- Safety guards: screen-on-only + quiet hours ----
        // If the task is marked onlyWhenScreenOn, defer if the screen is off
        // (user is driving, sleeping, or the phone is in their pocket).
        val onlyWhenScreenOn = inputData.getBoolean(KEY_ONLY_WHEN_SCREEN_ON, false)
        if (onlyWhenScreenOn && !isScreenOn(applicationContext)) {
            // Result.retry() takes no arguments in WorkManager — the deferred
            // reason is logged but not passed to the result. WorkManager will
            // retry with exponential backoff.
            Log.i("ScheduledTaskWorker", "Task $taskId deferred: screen off")
            return Result.retry()
        }
        // Quiet hours: if the current time falls inside the configured window,
        // defer until the window ends. Format "HH:mm".
        val quietStart = inputData.getString(KEY_QUIET_START) ?: ""
        val quietEnd = inputData.getString(KEY_QUIET_END) ?: ""
        if (quietStart.isNotBlank() && quietEnd.isNotBlank() && isInQuietHours(quietStart, quietEnd)) {
            Log.i("ScheduledTaskWorker", "Task $taskId deferred: quiet hours ($quietStart-$quietEnd)")
            return Result.retry()
        }

        // Create a fresh session and dispatch the prompt to the shared execution core.
        val session = sessions.create("[Scheduled] $title")
        agentLoop.start(session, prompt)

        // Wait up to 10 minutes for the session to reach a terminal state.
        val sessionId = session.id
        val finished = withTimeoutOrNull(10 * 60 * 1000L) {
            while (true) {
                val s = sessions.getById(sessionId)
                val status = s?.status
                if (status == com.omniclaw.app.data.model.SessionStatus.DONE ||
                    status == com.omniclaw.app.data.model.SessionStatus.FAILED ||
                    status == com.omniclaw.app.data.model.SessionStatus.STOPPED
                ) break
                kotlinx.coroutines.delay(2000)
            }
            true
        }
        return Result.success(workDataOf(
            KEY_TASK_ID to taskId,
            "session_id" to sessionId,
            "completed" to (finished == true),
        ))
    }

    companion object {
        const val KEY_TASK_ID = "task.id"
        const val KEY_TASK_TITLE = "task.title"
        const val KEY_PROMPT = "task.prompt"
        const val KEY_WEEKDAYS = "task.weekdays"
        const val KEY_ONLY_WHEN_SCREEN_ON = "task.only_screen_on"
        const val KEY_QUIET_START = "task.quiet_start"
        const val KEY_QUIET_END = "task.quiet_end"
        const val WORK_PREFIX = "omni_scheduled_"

        /**
         * Check if the screen is currently on. Uses the power manager —
         * reliable across Android versions. Used by the onlyWhenScreenOn guard.
         */
        private fun isScreenOn(ctx: Context): Boolean {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return pm.isInteractive
        }

        /**
         * Check if the current time falls inside the quiet-hours window.
         * Handles windows that span midnight (e.g. 23:00-07:00).
         */
        private fun isInQuietHours(start: String, end: String): Boolean {
            val now = java.time.LocalTime.now()
            val s = runCatching { java.time.LocalTime.parse(start) }.getOrNull() ?: return false
            val e = runCatching { java.time.LocalTime.parse(end) }.getOrNull() ?: return false
            return if (s <= e) {
                now in s..e
            } else {
                // Window spans midnight (e.g. 23:00-07:00) — true if now >= s OR now <= e.
                now >= s || now <= e
            }
        }

        /**
         * Schedule (or replace) a periodic task.
         * @param intervalMinutes must be >= 15 (WorkManager minimum).
         */
        fun scheduleInterval(ctx: Context, taskId: String, title: String, prompt: String, intervalMinutes: Long) {
            val data = workDataOf(
                KEY_TASK_ID to taskId,
                KEY_TASK_TITLE to title,
                KEY_PROMPT to prompt,
            )
            val req = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(
                intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES,
            )
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "$WORK_PREFIX$taskId",
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }

        /**
         * Schedule a one-shot task to fire at a specific time-of-day on specific weekdays.
         * Computes the delay to the next matching fire time.
         *
         * @param weekdays Set of Calendar.DAY_OF_WEEK values (1=Sun ... 7=Sat). Empty = every day.
         * @param timeOfDay "HH:mm" 24-hour format.
         */
        fun scheduleWeekly(
            ctx: Context,
            taskId: String,
            title: String,
            prompt: String,
            weekdays: Set<Int>,
            timeOfDay: String,
        ) {
            val delayMs = computeDelayToNext(weekdays, timeOfDay)
            val delayMinutes = (delayMs / 60_000L).coerceAtLeast(1)
            val weekdaysStr = weekdays.joinToString(",")
            val data = workDataOf(
                KEY_TASK_ID to taskId,
                KEY_TASK_TITLE to title,
                KEY_PROMPT to prompt,
                KEY_WEEKDAYS to weekdaysStr,
            )
            // First fire as one-shot to hit the exact target time.
            val oneShot = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
                .setInputData(data)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "$WORK_PREFIX${taskId}_oneshot",
                androidx.work.ExistingWorkPolicy.REPLACE,
                oneShot,
            )
            // 24h periodic re-fires daily. IMPORTANT: the periodic work must
            // ALSO carry KEY_WEEKDAYS so doWork()'s weekday filter (line ~54)
            // can skip non-target days. Previously this called scheduleInterval()
            // which omitted KEY_WEEKDAYS, so weekly tasks fired every single
            // day after the first one-shot.
            val periodicData = workDataOf(
                KEY_TASK_ID to taskId,
                KEY_TASK_TITLE to title,
                KEY_PROMPT to prompt,
                KEY_WEEKDAYS to weekdaysStr,
            )
            val periodic = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(
                24 * 60, TimeUnit.MINUTES,
            )
                .setInputData(periodicData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "$WORK_PREFIX$taskId",
                ExistingPeriodicWorkPolicy.UPDATE,
                periodic,
            )
        }

        fun scheduleOneShot(ctx: Context, taskId: String, title: String, prompt: String, delayMinutes: Long) {
            val data = workDataOf(
                KEY_TASK_ID to taskId,
                KEY_TASK_TITLE to title,
                KEY_PROMPT to prompt,
            )
            val req = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
                .setInputData(data)
                .setInitialDelay(delayMinutes.coerceAtLeast(1), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }

        fun cancel(ctx: Context, taskId: String) {
            WorkManager.getInstance(ctx).cancelUniqueWork("$WORK_PREFIX$taskId")
            WorkManager.getInstance(ctx).cancelUniqueWork("$WORK_PREFIX${taskId}_oneshot")
        }

        /** Compute the delay (ms) from now to the next matching weekday + time-of-day. */
        private fun computeDelayToNext(weekdays: Set<Int>, timeOfDay: String): Long {
            val parts = timeOfDay.split(":")
            val targetHour = parts.getOrNull(0)?.toIntOrNull() ?: 9
            val targetMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val now = Calendar.getInstance()
            val target = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, targetHour)
                set(Calendar.MINUTE, targetMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // If today's target time already passed, start from tomorrow.
            if (target.timeInMillis <= now.timeInMillis) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
            // Walk forward day-by-day until we hit a target weekday (or empty = any day).
            val days = if (weekdays.isEmpty()) setOf(1, 2, 3, 4, 5, 6, 7) else weekdays
            var iterations = 0
            while (target.get(Calendar.DAY_OF_WEEK) !in days && iterations < 8) {
                target.add(Calendar.DAY_OF_YEAR, 1)
                iterations++
            }
            return target.timeInMillis - now.timeInMillis
        }
    }
}

@dagger.assisted.AssistedFactory
interface ScheduledTaskWorkerFactory {
    fun create(appContext: Context, params: WorkerParameters): ScheduledTaskWorker
}

