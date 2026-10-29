# WristMind (`ai.wristmind.app`) — 4 GB Android 15 Watch Edge-First AI Showcase

Rearchitecture of the legacy cross-platform Flutter **OpenMMPA** assistant into a **zero-Cloud-Functions**, edge-first native Kotlin Android application engineered specifically for a **Full Android 15 rectangular watch (>2" display, `Smallest Width = 426dp`, 4 GB physical LPDDR RAM, Google Play Services)**.

---

## Documentation

* **[Architecture Specification](docs/ARCHITECTURE.md):** In-depth technical architecture covering the 4 GB LPDDR memory topology, LiteRT-LM runtime, ObjectBox 6 + AppSearch hybrid RRF vault, System-1 decision engine, Prompt Injection Shield, Koog agent harness, 3-tier health bridge, and Firebase AI Logic cloud tier.
* **[Getting Started & User Manual](docs/USER_GUIDE.md):** Complete guide for installing, debugging over Wi-Fi ADB, sideloading LiteRT-LM models, navigating the 4-card AMOLED deck, invoking watch skills, and developer desktop tools.
* **[Architecture Research Conversation Log](research/Architecture_Conversation_Log.md):** Detailed chronological decision ledger and deep research synthesis.

---

## Architectural Highlights

| Layer | Legacy OpenMMPA (Flutter) | WristMind (`ai.wristmind.app`) |
|---|---|---|
| **Target Form Factor** | Phone Flutter widget tree | **Micro-Wrist AMOLED Deck (`sw426dp` on >2" watch)**: `80dp` touch targets, `20–26sp` typography, live `/proc/meminfo` + PSS RAM HUD pill |
| **Multimodal Embeddings** | Unbundled / Cloud API | **`EmbeddingGemma 2` Text+Vision (440M, 388 MB)** & Text (270M) via `LiteRT-LM 0.18.0` `EmbeddingEngine`, sliced via MRL (`768d -> 256d` + $L_2$ norm, FP16 disabled) |
| **Hybrid Memory Vault** | `objectbox_flutter_libs 4.0.3` (vector only) | **ObjectBox `6.0.0-beta3` HNSW** (`dimensions = 256`, `neighborsPerNode = 16`, `COSINE`) + **Android 15 Jetpack `AppSearch 1.1.0` (`PlatformStorage` BM25F)** fused via **Reciprocal Rank Fusion (RRF)** + Pre-LLM Grounding Gate |
| **OS Agent Exposure** | None | **Android 16+ Jetpack `AppFunctions 1.0.0-beta01`** (`@AppFunctionServiceEntryPoint` exposing `searchMemories`, `saveMemoryNote`, and `readWristVitals` to system agents) |
| **Local Model Gallery** | None | Switchable **`LiteRT-LM 0.18.0`** gallery: `Qwen3-0.6B` (INT4), `FunctionGemma 270M`, `Gemma 3 270M-IT`, `SmolVLM-256M-Instruct`, `SmolVLM2-500M-Instruct` with `CO-RES` / `AUTO-SWAP` residency |
| **System-1 Router & Shield** | Cloud LLM prompt routing | Sub-30 ms Jev-style **System-1 Decision Engine** (`Choice`, `Score`, `Noul`, confidence gates $P \ge 0.80$ / $0.50..0.80$ / $<0.50$) + **LiteRT `2.3.0` `CompiledModel` Prompt-Injection Shield** (Chokepoints A & B) |
| **Agent Harness & FAW Skills** | Custom Dart state | **JetBrains Koog `1.3.0`** (`DynamicPromptExecutor`, KV-cache History Compression, `discoverSkills` progressive `SKILL.md` XML catalog across **7 FAW watch skills**) |
| **FAW Health & Sensor Bridge** | None | **3-Tier Battery-Aware `BetterHealthTrackerBridge`**: Tier 1 **TraX V2 `ContentProvider`** (`content://com.ailife.betterhealth.healthprovider`, zero LED drain), Tier 2 **Native Android `Sensor.TYPE_HEART_RATE` (21)** one-shot sampling with immediate `unregisterListener`, Tier 3 legacy `GET_DATA_API` + proprietary HSC/CD vendor sensor IDs (`31, 43, 65596, 65598, 65599`) |
| **Cloud & Speech I/O** | `STTSFunctions` Cloud Functions + `TTSEncodings.xlsx` | **Firebase AI Logic (`firebase-bom:35.1.0`)** + App Check (`PlayIntegrity` / `Debug`) + Remote Config + 1 fps Gemini Live camera/voice burst; offline fallback to Android `SpeechRecognizer` & `TextToSpeech` |

---

## Bundled Watch Skills (`assets/skills/*/SKILL.md`)

1. **`set-watch-alarm`** (`run_intent`): Sets watch alarms (`ACTION_SET_ALARM`) and countdown timers (`ACTION_SET_TIMER`).
2. **`read-wrist-sensors`** (`run_intent`): Reads FAW heart rate (BPM), SpO2, steps, calories, distance via TraX V2 `ContentProvider` + native `Sensor.TYPE_HEART_RATE` (21) + legacy `GET_DATA_API` fallback, plus battery % and live PSS RAM.
3. **`log-vitals-to-vault`** (`run_intent`): Samples live or latest FAW vitals and persists a timestamped health note into ObjectBox 256d HNSW + AppSearch BM25F so vitals history is searchable offline.
4. **`toggle-watch-torch`** (`run_intent`): Toggles the FAW camera LED flashlight (`CameraManager.setTorchMode`) when hardware flash is available.
5. **`control-watch-system`** (`run_intent`): Adjusts watch `STREAM_MUSIC` volume (`UP`, `DOWN`, `MUTE`, `UNMUTE`, `MAX`, `%`) and opens Android 15 Quick Settings panels (`WIFI`, `BLUETOOTH`, `DISPLAY`, `BATTERY`, `SOUND`).
6. **`schedule-watch-event`** (`run_intent`): Schedules watch calendar events via `CalendarContract.Events` (`ACTION_INSERT`).
7. **`quick-js-calc`** (`run_js`): Evaluates math and unit conversions inside an isolated, network-disabled `WebView` sandbox (`window.ai_edge_gallery_get_result`).

---

## Quick Start & Build

```bash
# Run unit tests (256d MRL math, FP16 NaN guard, RRF fusion, Prompt Shield, System-1 gates, TraX V2 + Native HR formatting) & build APK
./gradlew testDebugUnitTest assembleDebug
```

### Optional: Push `.litertlm` / `.tflite` Weights via ADB (or Download In-App from the `MODELS` Card)
WristMind boots and runs its seeded Hybrid Vault, System-1 Router, OCR Prompt-Injection Shield, and all 7 Watch Skills **immediately out of the box** even before model weights are downloaded. To enable neural on-device generation/embeddings offline via `adb push`:

```bash
adb push Qwen3-0.6B.litertlm /sdcard/Download/
adb push embeddinggemma-2-text-vision-440m.litertlm /sdcard/Download/
adb push smolvlm-256m-instruct.litertlm /sdcard/Download/
```

### Attaching Firebase AI Logic (`app/google-services.json`)
Once `app/google-services.json` is placed in `app/`, Gradle automatically activates `com.google.gms.google-services`, enabling Firebase App Check, Remote Config, and Gemini 2.5 Flash / 1 fps Live Cloud Burst.
