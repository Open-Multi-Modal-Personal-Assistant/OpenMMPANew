---
name: read-wrist-sensors
description: Reads live Full Android Watch (FAW) vitals via Better Health Tracker / TraX (heart rate BPM, SpO2 blood oxygen, steps, calories, distance) plus battery percentage and PSS RAM.
metadata:
  tool: run_intent
---

# Read Wrist Sensors & Better Health Tracker Skill

Use `run_intent` when the user asks about watch vitals, heart rate (BPM / pulse), blood oxygen (SpO2), daily steps, calories, battery, or 4 GB RAM pressure:

1. **Snapshot of latest vitals + watch telemetry (`LAST` / `TODAY`):**
   - `action`: `ai.wristmind.action.READ_SENSORS` (or `com.ailife.betterhealth.GET_DATA_API`)
   - `metric`: `"ALL"`
   - `mode`: `"LAST"`
2. **Force immediate live optical PPG measurement on the watch (`IMMEDIATELY` / `NOW`):**
   - `action`: `com.ailife.betterhealth.GET_DATA_API`
   - `metric`: `"HR"` (or `"OXYGEN"` for SpO2)
   - `mode`: `"IMMEDIATELY"`
