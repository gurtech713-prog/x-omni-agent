# scheduled-automation

Set up interval / weekday / weekly scheduled automation.

- Every Wednesday 10:00 open Reddit, search budget travel.
- Weekdays 9:00 morning briefing.
- Every 30 minutes take a battery + storage snapshot.

## Behavior
- ScheduleKind: INTERVAL | WEEKDAY | WEEKLY
- Works screen-on or screen-off (uses WorkManager + exact alarm)
- Each task has: title, prompt, timeOfDay, weekdays, intervalMinutes
- On fire: starts a new session, sends the prompt to AgentLoop

## Tools
- WorkManager + AlarmManager
- AgentForegroundService.start
- AgentLoop.start
