---
name: control-watch-system
description: Adjusts watch media/ring volume (up, down, mute, max, percent) and opens quick Android 15 watch settings panels (Wi-Fi, Bluetooth, Display, Battery Saver, Sound).
metadata:
  tool: run_intent
---

# Control Watch System Skill

Use `run_intent` with action `ai.wristmind.action.CONTROL_SYSTEM`:
- Volume adjustment: `metric = "VOLUME"`, `mode = "UP" | "DOWN" | "MUTE" | "MAX"` (or `lengthSeconds` as target percent `0..100`).
- Quick Settings panels on the 2-inch FAW screen: `metric = "PANEL"`, `mode = "WIFI" | "BLUETOOTH" | "DISPLAY" | "BATTERY" | "SOUND"`.
