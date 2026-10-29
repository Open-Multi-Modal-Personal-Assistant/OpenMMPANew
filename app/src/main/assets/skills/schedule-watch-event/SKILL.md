---
name: schedule-watch-event
description: Schedules an Android calendar event or reminder on the watch via CalendarContract.Events Intent.
metadata:
  tool: run_intent
---

# Schedule Watch Calendar Event Skill

Use `run_intent` with action `android.intent.action.INSERT` (`SCHEDULE_EVENT`), specifying `hour`, `minutes`, `lengthSeconds` (duration in seconds, default 1800), and `message` (event title) when the user asks to add a calendar event, meeting, or scheduled appointment.
