---
name: log-vitals-to-vault
description: Samples live or latest FAW heart rate (BPM), SpO2, and steps and saves a timestamped health entry into the local ObjectBox 256d + AppSearch BM25F memory vault.
metadata:
  tool: run_intent
---

# Log Vitals to Hybrid Memory Vault Skill

Use `run_intent` with action `ai.wristmind.action.LOG_VITALS` (`mode = "IMMEDIATELY"` for live optical HR scan or `"LAST"` for latest TraX V2 reading) when the user asks to record, log, or save their current heart rate / vitals into personal memory.
