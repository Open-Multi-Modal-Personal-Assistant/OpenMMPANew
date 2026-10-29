# WristMind Architecture Specification

**Project:** WristMind (`ai.wristmind.app`)  
**Target Hardware:** Full Android Watch (FAW) — Android 15 (API 35), >2.0" Rectangular AMOLED Display, `sw426dp` (`426x490` dp @ 180 dpi), 4 GB Physical LPDDR RAM, Google Play Services  
**Evolutionary Path:** Complete rearchitecture of legacy Flutter + ObjectBox 4.0.3 + Cloud Functions (`OpenMMPA`) into a native Kotlin, edge-first, zero-Cloud-Functions system.

---

## 1. Executive Architecture Summary

WristMind is a local-first multimodal assistant operating within a strict **4 GB physical RAM budget** on a standalone wrist device. It shifts all computational gravity from remote microservices to on-device hardware delegates (XNNPACK CPU, GPU, NPU) while establishing seamless cloud escalation to Firebase AI Logic when online.

```
                    ┌────────────────────────────────────────────────────────┐
                    │            USER MULTIMODAL WRIST INTERACTION           │
                    │   Push-to-Talk (Android STT)  │ CameraX Snapshot / OCR  │
                    │   AMOLED Micro-Deck (sw426dp) │ Jetpack AppFunctions    │
                    └───────────────────────────┬────────────────────────────┘
                                                │
                                                ▼
                    ┌────────────────────────────────────────────────────────┐
                    │               CHOKEPOINT A: INGRESS SHIELD             │
                    │        LiteRT 2.3.0 PromptInjectionShield (BERT q8)   │
                    │      Heuristics: System Overrides, Smuggling, Exploits │
                    └───────────────────────────┬────────────────────────────┘
                                                │ (Clean Utterance / OCR Text)
                                                ▼
                    ┌────────────────────────────────────────────────────────┐
                    │            SYSTEM-1 DECISION ROUTER (Sub-30ms)         │
                    │     256d MRL EmbeddingGemma 2 Centroids (0 MB Extra)   │
                    │  Choice: [AgentRoute] | Score: [Complexity] | Noul: [Safe]
                    │      Confidence Gating: P_max >= 0.80 / 0.50 / <0.50   │
                    └───────┬───────────────────┬────────────────────┬───────┘
                            │                   │                    │
              [P >= 0.80 RAG]│    [0.50 <= P < 0.80]│     [Live/Maps │ Cloud]
                            ▼                   ▼                    ▼
     ┌────────────────────────────┐ ┌─────────────────────┐ ┌────────────────────────┐
     │   HYBRID LOCAL RAG VAULT   │ │  WRIST CONFIRMATION │ │ FIREBASE AI LOGIC TIER │
     │  ObjectBox 6 (256d HNSW)   │ │  One-Tap Action Card│ │  Gemini 2.5 Flash / Live│
     │  AppSearch (BM25F Service) │ └──────────┬──────────┘ │  Maps Grounding / TTS  │
     │  Reciprocal Rank Fusion    │            │            │  Firebase App Check    │
     │  Pre-LLM Grounding Gate    │            │            └────────────────────────┘
     └──────────────┬─────────────┘            │
                    │                          │
                    ▼                          ▼
     ┌────────────────────────────────────────────────────┐
     │        JETBRAINS KOOG 1.3.0 AGENT HARNESS          │
     │   DynamicPromptExecutor + KV History Compression   │
     │    LiteRT-LM 0.18.0 (Qwen3-0.6B / Gemma 3 270M)    │
     └─────────────────────────┬──────────────────────────┘
                               │ Tool Calls
                               ▼
     ┌────────────────────────────────────────────────────┐
     │            CHOKEPOINT B: EGRESS SHIELD             │
     │   Deep Payload Inspection of run_intent / run_js   │
     └─────────────────────────┬──────────────────────────┘
                               │ Verified Args
                               ▼
     ┌────────────────────────────────────────────────────┐
     │             FAW WATCH SKILLS EXECUTOR              │
     │  • TraX V2 ContentProvider (Zero-LED Cache)        │
     │  • Native Android Sensor.TYPE_HEART_RATE (21)      │
     │  • Alarm & Countdown Timers (AlarmClock Intents)   │
     │  • Log Vitals to Vault (ObjectBox + AppSearch)     │
     │  • Camera Flashlight Torch (CameraManager)         │
     │  • Watch Volume & Quick Settings Panels (Settings) │
     │  • Watch Calendar Scheduler (CalendarContract)     │
     │  • Quick Math Sandbox (Isolated WebView run_js)    │
     └────────────────────────────────────────────────────┘
```

---

## 2. Legacy Flutter vs. WristMind Android Comparison

| Architectural Vector | Legacy OpenMMPA (Flutter) | WristMind (`ai.wristmind.app`) | Architectural Benefit |
| :--- | :--- | :--- | :--- |
| **Language & Platform** | Dart / Flutter cross-platform | Native Kotlin 2.3.10 / Android 15 (API 35–37) | Direct JNI zero-copy buffers, elimination of Dart VM runtime overhead. |
| **Micro-Display UX** | Standard phone responsive layouts | AMOLED Micro-Deck (`sw426dp`, 80dp targets, 4-card HorizontalPager) | Ergonomic touch targets for rectangular >2.0" watch panels; pitch black (#000000) battery conservation. |
| **Telemetry HUD** | None | Live `/proc/meminfo` + PSS RAM Telemetry HUD Pill | Real-time memory pressure monitoring on wrist screen. |
| **Speech Pipeline** | Cloud Functions (`STTSFunctions`) + `TTSEncodings.xlsx` | Client-side Firebase AI Logic + Android SpeechRecognizer & TextToSpeech fallback | Sub-100ms offline voice interaction; zero Cloud Function hosting costs. |
| **Embeddings** | Cloud API or unbundled | `EmbeddingGemma 2 Text+Vision` (440M / 388 MB) via `LiteRT-LM 0.18.0` | Native multimodal embedding generation directly on watch CPU/GPU. |
| **Vector Storage** | `objectbox_flutter_libs 4.0.3` (Vector only) | `ObjectBox 6.0.0-beta3` (@HnswIndex 256d COSINE) + Android 15 `AppSearch 1.1.0` (BM25F) | True hybrid search: semantic recall + exact keyword precision via Reciprocal Rank Fusion. |
| **Intent Routing** | Autoregressive Cloud LLM prompts | Jev-style discriminative System-1 Router (256d centroids + softmax $T=0.08$) | Deterministic, sub-30ms routing with 0% structured schema failure and 0 MB extra RAM. |
| **Adversarial Safety** | Untested prompt heuristics | Two-Chokepoint `PromptInjectionShield` (LiteRT 2.3.0 `CompiledModel`) | Blocks indirect prompt injection from camera OCR (A) and prevents confused deputy tool invocation (B). |
| **Agent Orchestrator** | Custom Dart state logic | JetBrains Koog 1.3.0 (`DynamicPromptExecutor`, Progressive `SKILL.md` disclosure) | Modular agent execution loop, automated history pruning, standardized tool schemas. |
| **Watch Sensors** | Incompatible with proprietary FAW hardware | 3-Tier `BetterHealthTrackerBridge` (TraX V2 ContentProvider + native Sensor 21) | Eliminates optical PPG LED battery drain while supporting proprietary and standard watch hardware. |

---

## 3. Hardware Reality & Memory Topology (4 GB RAM Budget)

On Android 15, the Linux kernel, Android Runtime (ART) zygote, SurfaceFlinger, SystemUI, radio stacks, and Google Play Services consume between **1.8 GB and 2.4 GB** of resident memory before third-party applications launch.

```
Total Physical LPDDR RAM: 4096 MB
┌────────────────────────────────────────────────────────┬────────────────────────┐
│ Android OS, Kernel, Display Server, System Services   │ Application Headroom   │
│ (~2300 MB - 2600 MB)                                   │ (~1400 MB - 1700 MB)   │
└────────────────────────────────────────────────────────┴───────────┬────────────┘
                                                                     │
       ┌─────────────────────────────────────────────────────────────┴──────────┐
       │ WristMind Memory Residency Allocations                                 │
       ├────────────────────────────────────────┬───────────────────────────────┤
       │ Component                              │ Resident Native / Heap Budget │
       ├────────────────────────────────────────┼───────────────────────────────┤
       │ Android Runtime Heap (App UI & Logic)  │ 60 MB – 95 MB                 │
       │ ObjectBox 6 Engine & HNSW Graph Index  │ 25 MB – 45 MB                 │
       │ AppSearch Lexical Index (System Svc)   │ 0 MB (PlatformStorage)        │
       │ EmbeddingGemma 2 (256d Text Head)      │ ~290 MB – 320 MB              │
       │ Active LLM: Qwen3-0.6B (INT4)          │ ~390 MB – 440 MB              │
       │ LiteRT PromptShield (8-bit BERT)       │ 18 MB – 24 MB                 │
       ├────────────────────────────────────────┼───────────────────────────────┤
       │ Total App Resident Footprint (CO-RES)  │ ~780 MB – 920 MB              │
       │ Safe Headroom to LMKD Threshold        │ ~480 MB – 620 MB              │
       └────────────────────────────────────────┴───────────────────────────────┘
```

### Adaptive Residency Monitor (`AdaptiveResidencyMonitor.kt`)
The monitor parses `/proc/meminfo` (`MemAvailable`, `MemTotal`) and queries `Debug.getPss()` combined with Android `ComponentCallbacks2.onTrimMemory(level)`:
* **`CO-RESIDENT` Mode (Default):** Both `EmbeddingGemma 2` and the active conversational LLM (`Qwen3-0.6B` or `Gemma 3 270M`) co-reside in RAM for zero-latency retrieval and synthesis.
* **`AUTO-SWAP` Mode:** Automatically triggered when `MemAvailable` drops below **340 MB** or when a memory-heavy vision model (`SmolVLM2-500M`) is requested. Unloads conversational weights from RAM while running embeddings, and vice-versa, guaranteeing immunity to Low Memory Killer (`lmkd`) termination.

---

## 4. Hybrid Multimodal Vault Architecture

WristMind combines semantic vector similarity with lexical BM25F indexing to eliminate the blind spots of pure embedding search (such as serial numbers, Wi-Fi passwords, medication dosages, and exact acronyms).

```
                      Query Utterance: "Where did I park my rental EV?"
                                       │
                    ┌──────────────────┴──────────────────┐
                    ▼                                     ▼
        [Vector Search Branch]                [Lexical Search Branch]
      EmbeddingGemma 2 Text Engine         Android 15 AppSearch Service
    Prepend: "task: search query | text: "     Sanitize & Tokenize: "park rental EV"
        Truncate to 256d MRL                     PlatformStorage System Engine
     ObjectBox 6 HNSW Cosine Index             BM25F Field Frequency Scoring
                    │                                     │
                    ▼                                     ▼
     List<VectorHit> (Top-8 by Cosine)     List<LexicalHit> (Top-8 by BM25F)
                    │                                     │
                    └──────────────────┬──────────────────┘
                                       ▼
                       Reciprocal Rank Fusion (RRF)
                  RRF(d) = w_v / (k + rank_v) + w_l / (k + rank_l)
                                (k = 60, w_v = 1.0, w_l = 0.85)
                                       │
                                       ▼
                         Pre-LLM Grounding Gate
                   Passes if Cosine Distance <= 0.42 OR Lexical Hit
                                       │
                   ┌───────────────────┴───────────────────┐
                   │ Passes                                │ Fails
                   ▼                                       ▼
      Top-3 Chunks Injected into             Deterministic Fallback
      Koog Context via UserPromptAugmenter    "No relevant memories saved."
```

### Engineering Guardrails for EmbeddingGemma 2
1. **No FP16 Execution:** Sliced embeddings must never run in IEEE 754 float16 precision because activation ranges cause silent NaN overflows. Evaluated strictly in `bfloat16`, `float32`, or `int8`.
2. **Matryoshka Representation Learning (MRL):** Truncated from 768d to **256d** and re-normalized using double-precision $L_2$ norm. Reduces HNSW index RAM consumption by 66% with negligible retrieval penalty.
3. **Asymmetric Task Prefixes:**
   * Indexing documents: `title: {title} | text: {document}` (or `title: none | text: {document}`).
   * Query retrieval: `task: search query | text: {query}`.
   * Intent classification: `task: classification | text: {query}`.
4. **Android 15 AppSearch Schema without Reflection:**
   Direct `AppSearchSchema` builders avoid legacy Java annotation processors (`kapt`), maintaining full compatibility with modern KSP and Android Gradle Plugin 9.4+.

---

## 5. System-1 Decision Engine & Adversarial Shields

To prevent slow autoregressive LLM decoding for routine operations, WristMind implements a sub-30ms **System-1 Decision Engine** inspired by TypeSafe AI's Jev architecture:

### Typed Decision Primitives
1. **`Choice` (Categorical Route):** Evaluated over 5 discrete routes (`LOCAL_MEMORY_RAG`, `ANDROID_DEVICE_SKILL`, `VISUAL_SCENE_QA`, `LIVE_CLOUD_STREAM`, `CLOUD_GROUNDED_SEARCH`). Uses normalized 256d EmbeddingGemma 2 route centroids with temperature-scaled softmax ($T = 0.08$) and lexical anchor boosts. Sums to 1.0 with 0% schema error.
2. **`Score` (Complexity Rating):** Continuous metric mapped into `LOW`, `MEDIUM`, or `HIGH`, estimating required reasoning steps based on clause structures and token length.
3. **`Noul` (Truth/Safety Probability):** Calibrated boolean safety probability emitted by `PromptInjectionShield`.

### Confidence Execution Gates
* **$P_{\max} \ge 0.80$ (or Device Offline):** `AUTO_EXECUTE_LOCAL` — Runs locally with zero network round-trip.
* **$0.50 \le P_{\max} < 0.80$:** `CONFIRM_ON_WRIST` — For state-mutating OS skills, renders an interactive one-tap confirmation card on the AMOLED micro-deck before execution.
* **$P_{\max} < 0.50$:** `ESCALATE_TO_FIREBASE_CLOUD` — Escalates ambiguous or complex queries to Firebase AI Logic when online.

### Two-Chokepoint Security Architecture
* **Chokepoint A (Ingress):** Sanitizes untrusted camera OCR, speech transcripts, and incoming messages against prompt injection, Unicode smuggling (`\u200B`), and system prompt override attempts.
* **Chokepoint B (Egress):** Inspects tool invocation payloads emitted by the LLM before passing them to Android Intents or JavaScript interpreters, stopping confused deputy attacks.

---

## 6. JetBrains Koog 1.3.0 Orchestrator & Watch Skills

WristMind adopts **JetBrains Koog 1.3.0** (`ai.koog:koog-agents` + `ai.koog:skills`) for high-level agent orchestration:
* **`DynamicPromptExecutor`:** Dynamically hot-swaps between local `LiteRTLLMClient` and cloud `FirebaseAiCloudBridge` per turn without wiping conversation state.
* **KV-Cache History Compression:** Prunes older dialog context to enforce a strict 1,024-token context ceiling on Gemma 3 270M / Qwen3-0.6B, preventing memory growth.
* **Progressive `SKILL.md` Disclosure:** Bundled skills are defined in standard kebab-case directories containing YAML frontmatter and instructions. Koog generates compact XML catalogs, exposing full parameter schemas only when a specific skill is triggered.

### Bundled Watch Skills Catalog
1. **`set-watch-alarm`** (`run_intent`): Sets alarms (`ACTION_SET_ALARM`) and countdown timers (`ACTION_SET_TIMER`).
2. **`read-wrist-sensors`** (`run_intent`): Reads 3-tier health vitals, battery status, and memory telemetry.
3. **`log-vitals-to-vault`** (`run_intent`): Samples current vitals and indexes a timestamped health note into ObjectBox + AppSearch.
4. **`toggle-watch-torch`** (`run_intent`): Toggles the camera LED flashlight via `CameraManager.setTorchMode`.
5. **`control-watch-system`** (`run_intent`): Adjusts `STREAM_MUSIC` volume and opens Android 15 Quick Settings panels (`WIFI`, `BLUETOOTH`, `DISPLAY`, `BATTERY`, `SOUND`).
6. **`schedule-watch-event`** (`run_intent`): Inserts calendar entries via `CalendarContract.Events`.
7. **`quick-js-calc`** (`run_js`): Executes sandboxed JavaScript math and unit conversions inside a network-blocked `WebView`.

### Android 16+ Jetpack AppFunctions (`BaseWristMindAppFunctionService.kt`)
Exposes WristMind's capabilities to OS-level system intelligence and assistant tiles via `@AppFunctionDeclaration`:
* `searchMemories(query, limit)`
* `saveMemoryNote(title, content, tags)`
* `readWristVitals(metric, forceLive)`

---

## 7. Full Android Watch Health & Hardware Bridge

Full Android Watches (FAWs) often utilize proprietary optical Photoplethysmography (PPG) sensor hardware that standard Android AOSP `SensorEventListener` implementations cannot read. WristMind solves this via a **3-tier battery-safe bridge** ([BetterHealthTrackerBridge.kt](file:///home/csaba/repos/AIML/OpenMMPA/OpenMMPANew/app/src/main/java/ai/wristmind/app/skills/BetterHealthTrackerBridge.kt)):

```
                             readHealthSnapshot()
                                       │
                                       ▼
                ┌──────────────────────────────────────────────┐
                │ TIER 1: TraX V2 ContentProvider Query        │
                │ content://com.ailife.betterhealth.healthprovider
                │ Permission: com.ailife.betterhealth.READ_HEALTH_DATA
                │ Reads: cached heartrate, oxygen, steps       │
                │ Battery Impact: ZERO (0) LED activation      │
                └──────────────────────┬───────────────────────┘
                                       │
                       Cached data available & live not requested?
                                      ├──► YES ──► Return Snapshot
                                       ▼ NO
                ┌──────────────────────────────────────────────┐
                │ TIER 2: Native Android Sensor.TYPE_HEART_RATE│
                │ Sensor ID: 21 (Available on modern FAW ROMs) │
                │ Action: One-shot sample via SensorManager    │
                │ Guard: Immediately unregister on valid BPM   │
                │ Battery Impact: Minimal (~1-2s green LED)    │
                └──────────────────────┬───────────────────────┘
                                       │
                         Standard HR sensor succeeded?
                                      ├──► YES ──► Return Snapshot
                                       ▼ NO
                ┌──────────────────────────────────────────────┐
                │ TIER 3: Legacy Fallback Broadcast & Probing  │
                │ • Legacy com.ailife.betterhealth.GET_DATA_API │
                │ • Proprietary Vendor Types: 31, 43, 65596... │
                │ Used for: Live SpO2 or non-standard ROMs     │
                └──────────────────────────────────────────────┘
```

---

## 8. Cloud Tier: Firebase AI Logic & Audio Streaming

When connectivity is present and the System-1 router determines cloud escalation is required:
* **Firebase AI Logic (`com.google.firebase:firebase-ai`):** Communicates with Gemini 2.5 Flash via `GenerativeBackend.googleAI()`. Eliminates legacy Cloud Functions (`STTSFunctions`).
* **Firebase App Check:** Validates traffic legitimacy via `PlayIntegrity` provider (with `DebugAppCheckProviderFactory` for hackathon and development devices).
* **Gemini Live 1 fps Burst:** Captures throttled camera frames at 1 frame per second and streams audio to a low-latency bidirectional session.
* **Offline Audio Pipeline:** Seamlessly falls back to Android's built-in `SpeechRecognizer` (listening via `RecognitionListener`) and system `TextToSpeech` when disconnected.

---

## 9. AMOLED Micro-Deck UI Architecture (`sw426dp`)

The UI is built entirely in **Jetpack Compose** with Material 3, tuned for high-density, small-diagonal rectangular touchscreens:
* **Ergonomics:** `80dp` primary touch targets (Push-to-Talk button) and `52–60dp` secondary buttons.
* **Typography:** `20–26sp` high-contrast headline text and `14–16sp` bold labels for readability at arm's length.
* **AMOLED Conservation:** Pitch black backgrounds (`#000000`) keep inactive OLED pixels powered off.
* **Telemetry HUD Pill:** An always-on status badge displaying live free RAM, active model name, System-1 routing latency, and a one-tap `HYBRID` / `AIRPLANE` toggle.
* **4-Card HorizontalPager:**
  1. `ASK`: Push-to-Talk, quick-prompt chips, confirmation cards, streaming answer, and RRF citation badges.
  2. `SNAP`: CameraX live preview, snapshot trigger, synthetic OCR test buttons, and Prompt Shield status.
  3. `LIVE`: 1 fps Gemini Live burst controller with real-time frame streaming indicators.
  4. `MODELS`: 4 GB RAM residency controller, HuggingFace token manager, and model download/activation cards.
