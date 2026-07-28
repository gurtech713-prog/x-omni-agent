# scheduled-automation

Set up interval, weekday, or weekly scheduled automation tasks.

- Every Wednesday 10:00 open Reddit, search budget travel.
- Weekdays 9:00 morning briefing.
- Every 30 minutes take a battery + storage snapshot.

## Usage
`skill:scheduled-automation(<schedule-spec>|<prompt>)`

The argument is `<schedule-spec>|<prompt>` — a pipe-separated pair where
the first part is the schedule and the second is the prompt to run.

Schedule spec formats:
- Interval: `<minutes>` (e.g. `60` for hourly, `30` for every 30 min)
- Weekly:   `weekly:<Day>:<HH:mm>` (e.g. `weekly:Wed:10:00`)

Minimum interval is 15 minutes (WorkManager limit).

## Behavior
1. Parse the schedule spec + prompt
2. Enqueue a WorkManager periodic task
3. On fire: start a new agent session with the prompt
4. The session runs in a foreground service so it survives backgrounding

## Notes
- Works screen-on or screen-off (uses WorkManager + foreground service)
- Each task has: title, prompt, timeOfDay, weekdays, intervalMinutes
- Tasks persist across app restarts (WorkManager durable)
- The user can view/cancel tasks from the Schedule screen
