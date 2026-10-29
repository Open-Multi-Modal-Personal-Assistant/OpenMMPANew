# OpenMMPANew — Architecture & Deep Research Conversation Log

**Last Updated:** 2026-10-29  
**Workspace:** `/home/csaba/repos/AIML/OpenMMPA/OpenMMPANew`  
**Legacy Reference:** `/home/csaba/repos/AIML/OpenMMPA/OpenMMPA` (Flutter + ObjectBox 4.0.3 + Firebase Cloud Functions)  
**Companion Research Artifact:** `research/Android Edge AI App Rearchitecture.md` (`.pdf`)

---

## Executive Decision Ledger (Finalized & Implemented)

| Area | Agreed Direction & Verified Implementation |
| :--- | :--- |
| **Package & App Name** | **`ai.wristmind.app` / WristMind** (`minSdk = 35`, `targetSdk = 36`, `compileSdk = 37`). |
| **Target Hardware** | Full Android 15 (API 35) wrist device (rectangular >2" micro-display, **Smallest Width = `426dp`**, **4 GB physical RAM**, **Google Play Services** installed, standard Android `Sensor.TYPE_HEART_RATE` (`21`) exposed + **Better Health Tracker / TraX** installed). |
| **Micro-Display UX (`sw426dp` on ~2.1")** | `WristMicroTheme` enforces **`80dp` primary touch targets**, **`20–26sp` typography**, AMOLED pitch-black (`#000000`) surfaces, 4-card `HorizontalPager` (`ASK`, `SNAP`, `LIVE`, `MODELS`), `@Preview(device = "spec:width=426dp,height=490dp,dpi=180")`, and an always-on **`TelemetryHudPill`** (`/proc/meminfo` + PSS RAM + active model + System-1 latency + `HYBRID`/`AIRPLANE` toggle). |
| **Local Generative Model Gallery** | Switchable `LiteRT-LM 0.18.0` gallery (`LiteRtModelGalleryManager` & `ModelCatalog`) supporting **`Qwen3-0.6B` (INT4)**, **`FunctionGemma 270M`**, **`Gemma 3 270M-IT`**, **`SmolVLM-256M-Instruct`**, and **`SmolVLM2-500M-Instruct`** on single-thread CPU XNNPACK (`maxNumImages = 1` for VLMs, `Content.ImageBytes` before `Content.Text`). |
| **Multimodal Embeddings** | **`EmbeddingGemma 2 Text+Vision` (440M = 270M Text + 170M Vision, ~388 MB)** & **`Text 270M`** via `LiteRT-LM 0.18.0` `EmbeddingEngine` (`EmbeddingGemmaEngine`), sliced via MRL (`768d -> 256d`) + double-precision $L_2$ re-normalization (`HybridSearchMath`), **strictly no FP16**, asymmetric task prefixes on text only, plus zero-RAM deterministic 256d SimHash fallback before `.litertlm` download. |
| **Memory Residency Policy** | **Co-resident by default (`CO-RES`)** guarded by `AdaptiveResidencyMonitor` (`/proc/meminfo` `MemTotal`/`MemAvailable` + `Debug.getPss()` + `ComponentCallbacks2.onTrimMemory()`), shifting to `AUTO_SWAP_ON_DEMAND` only when free headroom drops below `340 MB`. |
| **Vector + Hybrid Search (Option B Chosen)** | **ObjectBox `6.0.0-beta3` HNSW** (`@HnswIndex(dimensions = 256, neighborsPerNode = 16, distanceType = VectorDistanceType.COSINE)`) paired with **Android 15 Jetpack `AppSearch 1.1.0` (`PlatformStorage` BM25F)** (`AppSearchLexicalIndex`) fused via weighted **Reciprocal Rank Fusion (RRF)** + Pre-LLM Grounding Gate (`HybridMemoryRepository`), and exposed to OS agents via **Jetpack `AppFunctions 1.0.0-beta01`** (`BaseWristMindAppFunctionService` exposing `searchMemories`, `saveMemoryNote`, and `readWristVitals`). |
| **System-1 Router & Prompt Injection Shield** | 1) `SystemOneDecisionEngine`: Jev-style `Choice`, `Score`, and `Noul` primitives using 256d `EmbeddingGemma 2` classification centroids (`0 MB` extra RAM) + temperature-scaled softmax ($T = 0.08$) and confidence gates ($P_{\max} \ge 0.80$ auto-local, $0.50..0.80$ one-tap wrist confirmation, $< 0.50$ cloud escalation).<br>2) `PromptInjectionShield`: **LiteRT `2.3.0` (`CompiledModel` API)** single-thread reusable `TensorBuffer` classifier (`xnnPackFlags = 0x23` weight cache) + structural adversarial guard at **Chokepoint A** (CameraX ML Kit OCR / untrusted ingress) and **Chokepoint B** (`run_intent` / `run_js` egress). |
| **Agent Orchestrator & 7 FAW Watch Skills** | **JetBrains Koog (`ai.koog:koog-agents:1.3.0` + `ai.koog:skills:1.3.0-beta`)** via `WristMindDynamicPromptExecutor` (`DynamicPromptExecutor`), KV-cache History Compression, and `generateSkillsPrompt(..., SkillsPromptFormat.XML)` over **7 bundled `SKILL.md` directories**: `set-watch-alarm`, `read-wrist-sensors`, `log-vitals-to-vault`, `toggle-watch-torch`, `control-watch-system`, `schedule-watch-event`, and `quick-js-calc`. |
| **3-Tier FAW Health & Sensor Bridge** | `BetterHealthTrackerBridge` prioritizes **Tier 1 TraX V2 `ContentProvider`** (`content://com.ailife.betterhealth.healthprovider/last/{heartrate|oxygen}` & `/records/range/...`, gated by `com.ailife.betterhealth.READ_HEALTH_DATA`, zero broadcast/LED drain) and **Tier 2 Native Android `Sensor.TYPE_HEART_RATE` (`21`)** (with immediate `unregisterListener` on first valid BPM `35..230` so the optical green PPG LED shuts off immediately), falling back to **Tier 3 Legacy `GET_DATA_API` broadcast / vendor sensor IDs (`31, 43, 65596, 65598, 65599`)** only when needed (e.g., live SpO2 `OXYGEN` scan). |
| **Cloud Escalation (Zero Cloud Functions)** | **Firebase AI Logic (`firebase-bom:35.1.0`, `com.google.firebase:firebase-ai`)** (`FirebaseAiCloudBridge`) with App Check (`PlayIntegrity` + `DebugAppCheckProviderFactory`), Remote Config, `GenerativeBackend.googleAI()`, and 1 fps Gemini Live camera/voice burst; offline fallback to Android `SpeechRecognizer` & `TextToSpeech` (`WristSpeechController`). |

---

## Verified Latest Dependency Versions (as of October 29, 2026)

| Component | Artifact / Plugin | Latest Verified Version | Notes |
| :--- | :--- | :--- | :--- |
| **Android Gradle Plugin** | `com.android.application` | **`9.4.1`** | Built-in Kotlin (no `kotlin-android` or `kotlin-kapt` plugins); Gradle `9.4.0` / `9.8.2` compatible. |
| **Kotlin & KSP** | `org.jetbrains.kotlin.*` / `com.google.devtools.ksp` | **`2.3.10`** / **`2.3.12`** | Aligned with JetBrains Koog `1.3.0` (`coroutines 1.10.2`, `serialization 1.10.0`, `datetime 0.7.1`). |
| **Jetpack Compose** | `androidx.compose:compose-bom` | **`2026.09.00`** | Material 3 AMOLED micro-wrist theme (`sw426dp`). |
| **LiteRT-LM (GenAI & Embeddings)** | `com.google.ai.edge.litertlm:litertlm-android` | **`0.18.0`** | `EmbeddingEngine` (EmbeddingGemma 2), Qwen3, FunctionGemma, and SmolVLM `Contents`. |
| **LiteRT V2 (`CompiledModel`)** | `com.google.ai.edge.litert:litert` (+ `litert-gpu`) | **`2.3.0`** | Single-thread `CompiledModel` + reusable `TensorBuffer`s + `xnnPackFlags = 0x23`. |
| **JetBrains Koog** | `ai.koog:koog-agents` + `ai.koog:skills` | **`1.3.0`** + **`1.3.0-beta`** | Using `ai.koog:skills:1.3.0-beta` directly avoids `prompt-executor-litert-client`'s older `litertlm:0.11.0` pin. |
| **ObjectBox** | `io.objectbox` (`objectbox-kotlin`, `objectbox-android`) | **`6.0.0-beta3`** | KSP applied before `io.objectbox` plugin; `@HnswIndex(dimensions = 256L, neighborsPerNode = 16)`. |
| **Jetpack AppSearch & AppFunctions** | `androidx.appsearch:*` / `androidx.appfunctions:*` | **`1.1.0`** / **`1.0.0-beta01`** | Android 15 `PlatformStorage` BM25F + Android 16+ `@AppFunctionDeclaration` & `@AppFunctionServiceEntryPoint`. |
| **Firebase BoM** | `com.google.firebase:firebase-bom` | **`35.1.0`** | `firebase-ai`, `firebase-config`, `firebase-appcheck-playintegrity`, `firebase-appcheck-debug`. |

---

## Chronological Conversation Log

### Turns 1–3 — Audit, Watch Hardware Discovery, System-1 / Koog / Hybrid Search Deep Research (2026-10-29 02:49–04:05 PDT)
- Audited legacy Flutter `OpenMMPA` Cloud Functions (`embed.py`, `rerank.py`, `chirp.py`, `tts.py`) and confirmed ObjectBox requires an external embedding engine and benefits from 256d Matryoshka truncation rather than modulo-folding.
- Verified 4 GB LPDDR budget constraints (`~1.1–1.4 GB` app PSS ceiling), disqualifying Gemini Nano (2B) in favor of `LiteRT-LM 0.18.0` compact models (`Qwen3-0.6B`, `FunctionGemma 270M`, `Gemma 3 270M-IT`, `SmolVLM-256M/500M`) co-resident with `EmbeddingGemma 2 Text+Vision 440M`.
- Audited `JetBrains/skills` (129 skills) and `JetBrains/koog` `1.3.0` (`DynamicPromptExecutor`, `discoverSkills`, History Compression).
- Calibrated micro-display ergonomics for the watch's `Smallest Width = 426dp` across a >2" rectangular panel (`80dp` touch targets, `20–26sp` typography).

### Turn 4 — Final Architectural Decisions & Complete Codebase Implementation (2026-10-29 04:15–04:57 PDT)

#### User Decisions
> 1. `ai.wristmind.app` is OK.  
> 2. Create a new Firebase project.  
> 3. Option B Android 15 AppSearch (`PlatformStorage` BM25F + OS search/`AppFunctions` tie-in).

#### Implementation Summary
- Created complete Gradle/AGP `9.4.1` project scaffold (`settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `gradlew`, `AndroidManifest.xml`, `app_metadata.xml`).
- Implemented **Hybrid Multimodal Vault**, **4 GB Edge Runtime & Switchable Model Gallery**, **LiteRT 2.3.0 Prompt-Injection Shield & System-1 Router**, **JetBrains Koog 1.3.0 Orchestrator**, **Firebase AI Logic & Offline Speech Pipeline**, and **`sw426dp` Micro-Wrist AMOLED Deck UI**.

### Turn 5 — Android Agent Tools Inventory & Full Android Watch (FAW) Better Health Tracker Integration (2026-10-29 05:10–05:38 PDT)

#### User Query
> What Android tools are available for the agent in the application? Sometimes FAW's (Full Android Watch) Heart Rate sensors are not standard Android sensors. I installed Better Health Tracker (`https://gitlab.com/ailife8881/better-health-tracker/-/wikis/home`) so the sensor can be read (`https://gitlab.com/ailife8881/health-api-example`).

#### Findings & Actions
- Inventoried all agent tools across three layers: local `SKILL.md` tools (`run_intent` & `run_js`), Jetpack `AppFunctions` (`BaseWristMindAppFunctionService`), and cloud `FirebaseAiCloudBridge` grounding tools.
- Analyzed `@ailife8881`'s Better Health Tracker wiki, `health-api-example`, and `RTOSify` (`HealthDataCollector.kt`), adding `BetterHealthTrackerBridge.kt`, `<queries>` visibility for `com.ailife.betterhealth`, `com.ailife.betterhealth.READ_HEALTH_DATA` permission handling, and a new `readWristVitals` `@AppFunctionDeclaration` entry point.
- Verified clean Kotlin compilation, KSP codegen (ObjectBox + AppSearch + AppFunctions), and JUnit4 unit tests (`./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` -> `BUILD SUCCESSFUL`).

### Turn 6 — TraX V2 Replacement for Legacy `GET_DATA_API`, Native FAW `Sensor.TYPE_HEART_RATE`, Discourse #74381 Research & Expanded Watch Tools (2026-10-29 05:45–06:10 PDT)

#### User Feedback & Questions
> 1. The `GET_DATA_API` in the app is marked as legacy (there was a note it could drain the battery faster - maybe for the hackathon I won't worry about that), not sure what's the replacement, if you don't see clear replacement we can just go with it.  
> 2. Also when I ran the app it looked like the HR sensor on this one is available as Android sensor?  
> 3. Also see for extra forum info `https://discourse.fullandroidwatch.org/t/better-health-tracker-universal-health-monitoring-for-all-android-wearables/74381/37`  
> 4. No code is committed yet, let's commit our work, including the research (potentially in multiple commits?)  
> 5. The conversation log can be updated with the latest.  
> 6. Yes we can add more FAW watch tools.

#### Deep Research Findings (`discourse.fullandroidwatch.org` Thread #74381 & `@ailife8881` TraX V2)
1. **Why `GET_DATA_API` Was Marked Legacy & Drained Battery:**
   - In July/August 2026 (`Post #12` & `Post #33` of FAW Discourse thread `#74381`), `@ailife8881` evolved Better Health Tracker into **TraX** (built for the Onyx / CloX watch ecosystem).
   - Legacy V1 `com.ailife.betterhealth.GET_DATA_API` was marked legacy because unthrottled broadcast polling from third-party watchfaces/launchers (e.g., Universal Launcher) repeatedly woke TraX's background service and kept firing the optical green PPG sensor—causing severe battery drain (as discussed around `Post #37`–`#41`, alongside optical sensor lockups if a listener isn't cleanly released).
   - **What Replaced V1 `GET_DATA_API`:** TraX V2 introduced Android system permission gating (`com.ailife.betterhealth.READ_HEALTH_DATA`) paired with:
     1. **TraX's exported `ContentProvider` (`content://com.ailife.betterhealth.healthprovider/last/{heartrate|oxygen}` and `/records/range/{start}/{end}`)** — reads TraX's SQLite database directly via `ContentResolver` with **zero broadcast wakeup and zero optical LED activation** (the exact pattern `@ailife8881` uses in `RTOSify`'s `HealthDataCollector.kt`), and
     2. Optional **Android Health Connect** sync (`Post #1` / `Post #33`).
2. **Native Android `Sensor.TYPE_HEART_RATE` (`21`) on This Android 15 FAW:**
   - Modern Android 15 FAW firmwares expose their optical PPG hardware directly through standard Android `SensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)` (`type = 21`) in addition to TraX.
   - Upgraded `BetterHealthTrackerBridge.kt` to a **3-tier battery-safe hierarchy**:
     - **Tier 1 (Cached Vitals — Zero LED Drain):** Queries TraX V2 `ContentProvider` (`content://com.ailife.betterhealth.healthprovider/...`) first.
     - **Tier 2 (Live HR — Standard Android `Sensor.TYPE_HEART_RATE` 21):** Samples `Sensor.TYPE_HEART_RATE` directly and calls `sensorManager.unregisterListener(this)` **immediately** on the first valid BPM (`35..230`) so the green optical LED shuts off right away and never locks up.
     - **Tier 3 (Fallback Only):** Invokes legacy `GET_DATA_API` broadcast (e.g., for live SpO2 `OXYGEN` scans) or probes proprietary HSC/CD vendor sensor IDs (`31, 43, 65596, 65598, 65599`).
3. **Expanded FAW Watch Skills (3 -> 7 Bundled `SKILL.md` Tools):**
   - Added `log-vitals-to-vault` (reads FAW vitals and indexes a timestamped health note directly into ObjectBox 256d HNSW + AppSearch BM25F), `toggle-watch-torch` (`CameraManager.setTorchMode`), `control-watch-system` (`AudioManager` volume control + Android 15 Quick Settings panels for Wi-Fi, Bluetooth, Brightness/Display, Battery Saver, and Sound), and `schedule-watch-event` (`CalendarContract.Events` `ACTION_INSERT`).
