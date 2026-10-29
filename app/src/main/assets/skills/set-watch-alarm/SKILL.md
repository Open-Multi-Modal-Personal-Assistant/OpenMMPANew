---
name: set-watch-alarm
description: Sets an Android watch alarm clock or countdown timer via native AlarmClock Intents.
metadata:
  tool: run_intent
  requires_confirmation_below: "0.80"
---

# Set Watch Alarm & Timer Skill

Use `run_intent` when the user asks to set an alarm or start a countdown timer on the watch.

## Supported Actions
1. **Alarm (`android.intent.action.SET_ALARM`)**
   - `hour`: Integer (0..23)
   - `minutes`: Integer (0..59)
   - `message`: Short label shown on the watch face
2. **Timer (`android.intent.action.SET_TIMER`)**
   - `lengthSeconds`: Integer duration in seconds
   - `message`: Short timer label
