package ai.wristmind.app.skills

import ai.koog.skills.model.Skill
import ai.koog.skills.prompt.SkillsPromptFormat
import ai.koog.skills.prompt.generateSkillsPrompt
import ai.wristmind.app.data.memory.HybridMemoryRepository
import ai.wristmind.app.data.memory.IngestionOutcome
import ai.wristmind.app.data.memory.MemoryChunkEntity
import ai.wristmind.app.edge.AdaptiveResidencyMonitor
import ai.wristmind.app.security.PromptInjectionShield
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.webkit.WebView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RunIntentArgs(
    val action: String,
    val hour: Int? = null,
    val minutes: Int? = null,
    val lengthSeconds: Int? = null,
    val message: String? = null,
    val metric: String? = null,
    val mode: String? = null
)

@Serializable
data class RunJsArgs(
    val expression: String
)

data class SkillExecutionResult(
    val executed: Boolean,
    val skillName: String,
    val summary: String
)

/**
 * Stages `SKILL.md` directories, models them as JetBrains Koog [Skill] descriptors rendered via
 * [generateSkillsPrompt], and executes Google AI Edge Gallery-compatible `run_intent` and `run_js`
 * watch skills protected at Chokepoint B by [PromptInjectionShield] and backed by
 * [BetterHealthTrackerBridge] (TraX V2 ContentProvider + native Android `Sensor.TYPE_HEART_RATE` +
 * legacy FAW fallback).
 */
class AndroidSkillExecutor(
    private val context: Context,
    private val residencyMonitor: AdaptiveResidencyMonitor,
    private val promptShield: PromptInjectionShield,
    private val betterHealthBridge: BetterHealthTrackerBridge = BetterHealthTrackerBridge(context),
    private val memoryRepositoryProvider: (() -> HybridMemoryRepository)? = null
) {
    companion object {
        const val ACTION_READ_SENSORS = "ai.wristmind.action.READ_SENSORS"
        const val ACTION_TOGGLE_TORCH = "ai.wristmind.action.TOGGLE_TORCH"
        const val ACTION_CONTROL_SYSTEM = "ai.wristmind.action.CONTROL_SYSTEM"
        const val ACTION_LOG_VITALS = "ai.wristmind.action.LOG_VITALS"

        val BUNDLED_SKILL_DIRS = listOf(
            "set-watch-alarm",
            "read-wrist-sensors",
            "quick-js-calc",
            "toggle-watch-torch",
            "control-watch-system",
            "schedule-watch-event",
            "log-vitals-to-vault"
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val stagedSkillsRoot = File(context.filesDir, "skills")

    @Volatile
    private var torchEnabled: Boolean = false

    @Volatile
    var cachedSkillsXmlPrompt: String = ""
        private set

    @Volatile
    var discoveredSkillNames: List<String> = emptyList()
        private set

    suspend fun stageAndDiscoverSkills(): String = withContext(Dispatchers.IO) {
        val parsedSkills = mutableListOf<Skill>()

        for (dir in BUNDLED_SKILL_DIRS) {
            val targetDir = File(stagedSkillsRoot, dir).apply { mkdirs() }
            val targetFile = File(targetDir, "SKILL.md")
            runCatching {
                val content = context.assets.open("skills/$dir/SKILL.md").bufferedReader().use { it.readText() }
                targetFile.writeText(content)
                parsedSkills += parseSkillMarkdown(dir, targetFile.absolutePath, content)
            }
        }

        discoveredSkillNames = BUNDLED_SKILL_DIRS
        cachedSkillsXmlPrompt = if (parsedSkills.isNotEmpty()) {
            runCatching { generateSkillsPrompt(parsedSkills, SkillsPromptFormat.XML) }
                .getOrElse { fallbackXmlCatalog() }
        } else {
            fallbackXmlCatalog()
        }
        cachedSkillsXmlPrompt
    }

    private fun parseSkillMarkdown(fallbackName: String, location: String, rawMarkdown: String): Skill {
        val frontmatter = rawMarkdown.trimStart()
            .takeIf { it.startsWith("---") }
            ?.removePrefix("---")
            ?.substringBefore("---", "")
            .orEmpty()
        var name = fallbackName
        var description = ""
        var tool = "run_intent"
        for (line in frontmatter.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("name:") -> name = trimmed.removePrefix("name:").trim().trim('"')
                trimmed.startsWith("description:") -> description = trimmed.removePrefix("description:").trim().trim('"')
                trimmed.startsWith("tool:") -> tool = trimmed.removePrefix("tool:").trim().trim('"')
            }
        }
        return Skill(
            name = name,
            description = description,
            location = location,
            license = null,
            compatibility = "android-15-faw",
            metadata = mapOf("tool" to tool),
            allowedTools = tool
        )
    }

    suspend fun executeRunIntent(args: RunIntentArgs): SkillExecutionResult {
        val serialized = json.encodeToString(args)
        val verdict = promptShield.inspectSkillInvocation("run_intent", serialized)
        if (!verdict.isSafe) {
            return SkillExecutionResult(
                executed = false,
                skillName = "run_intent",
                summary = "Blocked by PromptInjectionShield (${verdict.triggeredCategory}): ${verdict.reason}"
            )
        }

        return when (args.action) {
            AlarmClock.ACTION_SET_ALARM, "SET_ALARM" -> {
                val h = (args.hour ?: 7).coerceIn(0, 23)
                val m = (args.minutes ?: 0).coerceIn(0, 59)
                val label = args.message ?: "WristMind Alarm"
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, h)
                    putExtra(AlarmClock.EXTRA_MINUTES, m)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val launched = runCatching { context.startActivity(intent) }.isSuccess
                val timeFormatted = "%02d:%02d".format(h, m)
                SkillExecutionResult(
                    executed = launched,
                    skillName = "set-watch-alarm",
                    summary = if (launched) {
                        "Set watch alarm for $timeFormatted ($label)."
                    } else {
                        "Prepared watch alarm Intent for $timeFormatted ($label) [no default Clock handler installed]."
                    }
                )
            }

            AlarmClock.ACTION_SET_TIMER, "SET_TIMER" -> {
                val secs = (args.lengthSeconds ?: 300).coerceIn(5, 86_400)
                val label = args.message ?: "WristMind Timer"
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val launched = runCatching { context.startActivity(intent) }.isSuccess
                SkillExecutionResult(
                    executed = launched,
                    skillName = "set-watch-alarm",
                    summary = if (launched) {
                        "Started ${secs}s watch countdown timer ($label)."
                    } else {
                        "Prepared ${secs}s timer Intent ($label) [Clock app unavailable]."
                    }
                )
            }

            BetterHealthTrackerBridge.ACTION_GET_DATA_API,
            ACTION_READ_SENSORS,
            "READ_SENSORS" -> {
                val batteryMgr = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val batteryPct = batteryMgr?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val ram = residencyMonitor.refreshNow()

                val targetMetric = args.metric ?: "ALL"
                val forceLive = args.mode.equals(BetterHealthTrackerBridge.TYPE_IMMEDIATELY, ignoreCase = true) ||
                    args.mode.equals(BetterHealthTrackerBridge.TYPE_NOW, ignoreCase = true)

                val healthSnapshot = betterHealthBridge.readHealthSnapshot(
                    requestedMetric = targetMetric,
                    forceLiveMeasurement = forceLive
                )

                val summary = buildString {
                    append(healthSnapshot.formatConciseSummary())
                    append(" | Battery: ${if (batteryPct >= 0) "$batteryPct%" else "N/A"}")
                    append(" | RAM: ${ram.appPssMb}/${ram.memAvailableMb} MB free (${ram.residencyMode.badge})")
                }
                SkillExecutionResult(
                    executed = true,
                    skillName = "read-wrist-sensors",
                    summary = summary
                )
            }

            ACTION_LOG_VITALS, "LOG_VITALS" -> executeLogVitalsToVault(args)

            ACTION_TOGGLE_TORCH, "TOGGLE_TORCH" -> executeToggleWatchTorch(args)

            ACTION_CONTROL_SYSTEM, "CONTROL_SYSTEM" -> executeControlWatchSystem(args)

            Intent.ACTION_INSERT, "SCHEDULE_EVENT" -> executeScheduleWatchEvent(args)

            else -> SkillExecutionResult(
                executed = false,
                skillName = "run_intent",
                summary = "Unsupported watch Intent action: ${args.action}"
            )
        }
    }

    private suspend fun executeLogVitalsToVault(args: RunIntentArgs): SkillExecutionResult {
        val forceLive = args.mode.equals(BetterHealthTrackerBridge.TYPE_IMMEDIATELY, ignoreCase = true) ||
            args.mode.equals(BetterHealthTrackerBridge.TYPE_NOW, ignoreCase = true)
        val snapshot = betterHealthBridge.readHealthSnapshot(
            requestedMetric = args.metric ?: "ALL",
            forceLiveMeasurement = forceLive
        )
        val timestampLabel = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val vitalsSummary = snapshot.formatConciseSummary()
        val noteSuffix = args.message?.takeIf { it.isNotBlank() }?.let { " | Note: $it" }.orEmpty()
        val fullEntry = "Recorded at $timestampLabel — $vitalsSummary$noteSuffix"

        val repo = memoryRepositoryProvider?.invoke()
        val storedId = if (repo != null) {
            when (
                val outcome = repo.ingestTextOrOcrMemory(
                    title = "Watch Vitals ($timestampLabel)",
                    contentText = fullEntry,
                    modality = MemoryChunkEntity.Modality.TEXT,
                    sourceUri = "wristmind://vitals/${System.currentTimeMillis()}",
                    tagsCsv = "vitals,health,heartrate,spo2,steps,faw",
                    isTrustedSource = true
                )
            ) {
                is IngestionOutcome.Stored -> outcome.entity.id
                is IngestionOutcome.BlockedByPromptShield -> null
            }
        } else {
            null
        }

        return SkillExecutionResult(
            executed = true,
            skillName = "log-vitals-to-vault",
            summary = if (storedId != null) {
                "Logged vitals to Hybrid Memory Vault (#$storedId): $vitalsSummary"
            } else {
                "Sampled vitals ($vitalsSummary) [vault unavailable]"
            }
        )
    }

    private fun executeToggleWatchTorch(args: RunIntentArgs): SkillExecutionResult {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return SkillExecutionResult(
                executed = false,
                skillName = "toggle-watch-torch",
                summary = "CameraManager unavailable on this watch."
            )

        val flashCameraId = runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()

        if (flashCameraId == null) {
            return SkillExecutionResult(
                executed = false,
                skillName = "toggle-watch-torch",
                summary = "No dedicated camera LED flash unit reported on this FAW hardware."
            )
        }

        val desiredState = when (args.mode?.uppercase(Locale.US)) {
            "ON", "TRUE", "1" -> true
            "OFF", "FALSE", "0" -> false
            else -> !torchEnabled
        }

        return runCatching {
            cameraManager.setTorchMode(flashCameraId, desiredState)
            torchEnabled = desiredState
            SkillExecutionResult(
                executed = true,
                skillName = "toggle-watch-torch",
                summary = "Watch LED flashlight turned ${if (desiredState) "ON" else "OFF"} (camera=$flashCameraId)."
            )
        }.getOrElse { err ->
            SkillExecutionResult(
                executed = false,
                skillName = "toggle-watch-torch",
                summary = "Unable to switch watch torch: ${err.message}"
            )
        }
    }

    private fun executeControlWatchSystem(args: RunIntentArgs): SkillExecutionResult {
        val metric = args.metric?.uppercase(Locale.US) ?: "VOLUME"
        val mode = args.mode?.uppercase(Locale.US) ?: "UP"

        if (metric == "PANEL" || mode in setOf("WIFI", "BLUETOOTH", "DISPLAY", "BATTERY", "SOUND")) {
            val (panelLabel, panelAction) = when (mode) {
                "WIFI", "INTERNET" -> "Internet / Wi-Fi" to Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                "BLUETOOTH", "BT" -> "Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS
                "DISPLAY", "BRIGHTNESS" -> "Display & Brightness" to Settings.ACTION_DISPLAY_SETTINGS
                "BATTERY", "POWER" -> "Battery Saver" to Settings.ACTION_BATTERY_SAVER_SETTINGS
                else -> "Sound & Volume" to Settings.Panel.ACTION_VOLUME
            }
            val intent = Intent(panelAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val opened = runCatching { context.startActivity(intent) }.isSuccess
            return SkillExecutionResult(
                executed = opened,
                skillName = "control-watch-system",
                summary = if (opened) {
                    "Opened $panelLabel quick panel on watch."
                } else {
                    "Requested $panelLabel settings ($panelAction), but panel is unavailable on this ROM."
                }
            )
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return SkillExecutionResult(
                executed = false,
                skillName = "control-watch-system",
                summary = "AudioManager unavailable."
            )

        val stream = AudioManager.STREAM_MUSIC
        val maxVol = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
        val targetPercent = args.lengthSeconds?.takeIf { it in 0..100 }

        when {
            targetPercent != null -> {
                val targetIndex = ((targetPercent / 100f) * maxVol).roundToInt().coerceIn(0, maxVol)
                audioManager.setStreamVolume(stream, targetIndex, 0)
            }
            mode == "MUTE" -> audioManager.setStreamVolume(stream, 0, 0)
            mode == "UNMUTE" -> {
                val restored = (maxVol * 0.5f).roundToInt().coerceAtLeast(1)
                audioManager.setStreamVolume(stream, restored, 0)
            }
            mode == "MAX" -> audioManager.setStreamVolume(stream, maxVol, 0)
            mode == "DOWN" || mode == "LOWER" -> {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
            }
            else -> {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
            }
        }

        val currentVol = audioManager.getStreamVolume(stream)
        val pct = ((currentVol.toFloat() / maxVol) * 100f).roundToInt()
        return SkillExecutionResult(
            executed = true,
            skillName = "control-watch-system",
            summary = "Watch media volume set to $currentVol/$maxVol ($pct%)."
        )
    }

    private fun executeScheduleWatchEvent(args: RunIntentArgs): SkillExecutionResult {
        val title = args.message?.takeIf { it.isNotBlank() } ?: "WristMind Watch Event"
        val durationSec = (args.lengthSeconds ?: 1_800).coerceIn(300, 86_400)
        val cal = Calendar.getInstance().apply {
            if (args.hour != null) {
                set(Calendar.HOUR_OF_DAY, args.hour.coerceIn(0, 23))
                set(Calendar.MINUTE, (args.minutes ?: 0).coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                if (timeInMillis < System.currentTimeMillis() - 60_000L) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            } else {
                add(Calendar.MINUTE, 30)
            }
        }
        val startMs = cal.timeInMillis
        val endMs = startMs + durationSec * 1_000L
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US).format(Date(startMs))

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        return SkillExecutionResult(
            executed = launched,
            skillName = "schedule-watch-event",
            summary = if (launched) {
                "Opened watch Calendar event '$title' at $timeFmt (${durationSec / 60} min)."
            } else {
                "Prepared Calendar event '$title' for $timeFmt [no Calendar app installed on watch]."
            }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun executeRunJs(args: RunJsArgs): SkillExecutionResult {
        val serialized = json.encodeToString(args)
        val verdict = promptShield.inspectSkillInvocation("run_js", serialized)
        if (!verdict.isSafe) {
            return SkillExecutionResult(
                executed = false,
                skillName = "quick-js-calc",
                summary = "Blocked by PromptInjectionShield (${verdict.triggeredCategory}): ${verdict.reason}"
            )
        }

        return withContext(Dispatchers.Main) {
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
            }
            try {
                val jsScript = """
                    (function() {
                        window.ai_edge_gallery_get_result = function(val) { return String(val); };
                        try {
                            return window.ai_edge_gallery_get_result(${args.expression});
                        } catch (err) {
                            return "JS_ERR: " + err.message;
                        }
                    })();
                """.trimIndent()
                val rawOut = suspendCancellableCoroutine<String> { cont ->
                    webView.evaluateJavascript(jsScript) { value ->
                        if (cont.isActive) cont.resume(value ?: "null")
                    }
                }
                SkillExecutionResult(
                    executed = !rawOut.contains("JS_ERR:"),
                    skillName = "quick-js-calc",
                    summary = "Result: ${rawOut.trim('"')}"
                )
            } finally {
                webView.destroy()
            }
        }
    }

    fun inferDirectSkillFromUtterance(utterance: String): Pair<RunIntentArgs?, RunJsArgs?> {
        val lower = utterance.lowercase()

        // 1. Flashlight / Torch
        if (Regex("""\b(torch|flashlight|flash\s*light)\b""").containsMatchIn(lower)) {
            val mode = when {
                Regex("""\b(off|disable|stop)\b""").containsMatchIn(lower) -> "OFF"
                Regex("""\b(on|enable|start)\b""").containsMatchIn(lower) -> "ON"
                else -> "TOGGLE"
            }
            return RunIntentArgs(action = ACTION_TOGGLE_TORCH, mode = mode) to null
        }

        // 2. Watch Volume & Quick Settings Panels
        if (Regex("""\b(volume|mute|unmute|louder|quieter)\b""").containsMatchIn(lower)) {
            val pctMatch = Regex("""(\d{1,3})\s*%""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
            val mode = when {
                lower.contains("unmute") -> "UNMUTE"
                lower.contains("mute") -> "MUTE"
                Regex("""\b(max|full)\b""").containsMatchIn(lower) -> "MAX"
                Regex("""\b(down|lower|quieter|decrease)\b""").containsMatchIn(lower) -> "DOWN"
                else -> "UP"
            }
            return RunIntentArgs(
                action = ACTION_CONTROL_SYSTEM,
                metric = "VOLUME",
                mode = mode,
                lengthSeconds = pctMatch?.coerceIn(0, 100)
            ) to null
        }

        if (
            Regex("""\b(open|show|toggle|launch)\b.{0,20}\b(bluetooth|wi-?fi|internet|brightness|display|battery\s*saver|sound)\b""").containsMatchIn(lower) ||
            Regex("""\b(bluetooth|brightness|battery\s*saver)\s+(settings|panel)\b""").containsMatchIn(lower)
        ) {
            val panelMode = when {
                lower.contains("bluetooth") -> "BLUETOOTH"
                lower.contains("wi-fi") || lower.contains("wifi") || lower.contains("internet") -> "WIFI"
                lower.contains("brightness") || lower.contains("display") -> "DISPLAY"
                lower.contains("battery") -> "BATTERY"
                else -> "SOUND"
            }
            return RunIntentArgs(
                action = ACTION_CONTROL_SYSTEM,
                metric = "PANEL",
                mode = panelMode
            ) to null
        }

        // 3. Calendar Event Scheduling
        if (Regex("""\b(calendar|schedule|appointment|meeting)\b""").containsMatchIn(lower)) {
            val timeMatch = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(lower)
            var hour = timeMatch?.groupValues?.get(1)?.toIntOrNull()
            val min = timeMatch?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val ampm = timeMatch?.groupValues?.get(3).orEmpty()
            if (hour != null) {
                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
            }
            val cleanedTitle = utterance
                .replace(Regex("""(?i)\b(schedule|add|create|put|on my calendar|calendar|event|at\s+\d{1,2}(:\d{2})?\s*(am|pm)?)\b"""), "")
                .trim()
                .ifBlank { "WristMind Watch Event" }
            return RunIntentArgs(
                action = Intent.ACTION_INSERT,
                hour = hour,
                minutes = min,
                lengthSeconds = 1_800,
                message = cleanedTitle
            ) to null
        }

        // 4. Countdown Timer & Alarm
        val timerMatch = Regex("""(\d+)\s*(minute|min|second|sec)s?""").find(lower)
        if (lower.contains("timer") || lower.contains("countdown")) {
            val amount = timerMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val unit = timerMatch?.groupValues?.get(2) ?: "min"
            val seconds = if (unit.startsWith("sec")) amount else amount * 60
            return RunIntentArgs(
                action = AlarmClock.ACTION_SET_TIMER,
                lengthSeconds = seconds,
                message = "WristMind Timer"
            ) to null
        }

        if (lower.contains("alarm") || lower.contains("wake me")) {
            val timeMatch = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(lower)
            var hour = timeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 7
            val min = timeMatch?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val ampm = timeMatch?.groupValues?.get(3).orEmpty()
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            return RunIntentArgs(
                action = AlarmClock.ACTION_SET_ALARM,
                hour = hour,
                minutes = min,
                message = "WristMind Alarm"
            ) to null
        }

        // 5. Log Vitals into Hybrid Memory Vault
        val wantsLiveMeasure = Regex("""\b(measure|live|right\s+now|take\s+my|scan)\b""").containsMatchIn(lower)
        if (
            Regex("""\b(log|record|save|store)\b.{0,24}\b(heart\s*rate|pulse|bpm|spo2|oxygen|vitals?|health)\b""").containsMatchIn(lower)
        ) {
            return RunIntentArgs(
                action = ACTION_LOG_VITALS,
                metric = "ALL",
                mode = if (wantsLiveMeasure) BetterHealthTrackerBridge.TYPE_IMMEDIATELY else BetterHealthTrackerBridge.TYPE_LAST
            ) to null
        }

        // 6. Read Watch Vitals (TraX V2 + Native Sensor.TYPE_HEART_RATE + Legacy Fallback)
        if (Regex("""\b(spo2|blood\s*oxygen|oxygen)\b""").containsMatchIn(lower)) {
            return RunIntentArgs(
                action = BetterHealthTrackerBridge.ACTION_GET_DATA_API,
                metric = BetterHealthTrackerBridge.DATA_OXYGEN,
                mode = if (wantsLiveMeasure) BetterHealthTrackerBridge.TYPE_IMMEDIATELY else BetterHealthTrackerBridge.TYPE_LAST
            ) to null
        }

        if (Regex("""\b(heart\s*rate|pulse|bpm)\b""").containsMatchIn(lower)) {
            return RunIntentArgs(
                action = ACTION_READ_SENSORS,
                metric = if (wantsLiveMeasure) BetterHealthTrackerBridge.DATA_HR else "ALL",
                mode = if (wantsLiveMeasure) BetterHealthTrackerBridge.TYPE_IMMEDIATELY else BetterHealthTrackerBridge.TYPE_LAST
            ) to null
        }

        if (Regex("""\b(battery|steps?|calories?|distance|vitals?|better\s*health|trax|sensor|ram|memory\s+pressure)\b""").containsMatchIn(lower)) {
            return RunIntentArgs(
                action = ACTION_READ_SENSORS,
                metric = "ALL",
                mode = BetterHealthTrackerBridge.TYPE_LAST
            ) to null
        }

        // 7. Quick JS Sandbox Calculator
        val mathMatch = Regex("""([0-9]+(?:\.[0-9]+)?(?:\s*[\+\-\*/%]\s*[0-9]+(?:\.[0-9]+)?)+)""").find(lower)
        if (mathMatch != null) {
            return null to RunJsArgs(expression = mathMatch.value)
        }

        return RunIntentArgs(
            action = ACTION_READ_SENSORS,
            metric = "ALL",
            mode = BetterHealthTrackerBridge.TYPE_LAST
        ) to null
    }

    private fun fallbackXmlCatalog(): String = """
        <skills>
          <skill name="set-watch-alarm" tool="run_intent">Sets Android watch alarms and timers via AlarmClock Intents.</skill>
          <skill name="read-wrist-sensors" tool="run_intent">Reads FAW heart rate (BPM), SpO2, steps, calories, distance via TraX V2 ContentProvider + native Sensor.TYPE_HEART_RATE (21) + legacy GET_DATA_API fallback, plus watch battery and PSS RAM.</skill>
          <skill name="log-vitals-to-vault" tool="run_intent">Samples watch heart rate, SpO2, and steps and stores a timestamped vitals note into ObjectBox 256d + AppSearch BM25F memory.</skill>
          <skill name="toggle-watch-torch" tool="run_intent">Turns the FAW camera LED flashlight (torch) on or off via CameraManager.</skill>
          <skill name="control-watch-system" tool="run_intent">Adjusts watch media volume (up/down/mute/max/%) and opens Android 15 quick settings panels (Wi-Fi, Bluetooth, Display, Battery Saver, Sound).</skill>
          <skill name="schedule-watch-event" tool="run_intent">Schedules an Android calendar event on the watch via CalendarContract.Events Intent.</skill>
          <skill name="quick-js-calc" tool="run_js">Evaluates math and unit conversions inside an isolated WebView sandbox.</skill>
        </skills>
    """.trimIndent()
}
