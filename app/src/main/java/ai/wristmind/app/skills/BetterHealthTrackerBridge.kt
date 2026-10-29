package ai.wristmind.app.skills

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class FawHealthSnapshot(
    val providerSource: String,
    val heartRateBpm: Int? = null,
    val heartRateTimestampMs: Long? = null,
    val heartRateIsLiveSample: Boolean = false,
    val spo2Percent: Int? = null,
    val spo2TimestampMs: Long? = null,
    val stepsToday: Int? = null,
    val caloriesKcal: Int? = null,
    val distanceKm: Float? = null,
    val hasStandardAndroidHrSensor: Boolean = false,
    val detectedProprietarySensors: List<String> = emptyList(),
    val diagnosticNote: String? = null
) {
    fun formatConciseSummary(): String = buildString {
        append("[$providerSource] ")
        val parts = mutableListOf<String>()
        if (heartRateBpm != null && heartRateBpm > 0) {
            val ageTag = when {
                heartRateIsLiveSample -> "live"
                heartRateTimestampMs != null -> formatTime(heartRateTimestampMs)
                else -> "cached"
            }
            parts += "HR: $heartRateBpm BPM ($ageTag)"
        } else {
            parts += if (hasStandardAndroidHrSensor) {
                "HR: Android Sensor(21) ready"
            } else {
                "HR: no reading yet"
            }
        }

        if (spo2Percent != null && spo2Percent > 0) {
            val spo2Tag = spo2TimestampMs?.let { " (${formatTime(it)})" }.orEmpty()
            parts += "SpO2: $spo2Percent%$spo2Tag"
        }

        if (stepsToday != null) {
            parts += "Steps: $stepsToday"
        }
        if (caloriesKcal != null && caloriesKcal > 0) {
            parts += "Cal: $caloriesKcal kcal"
        }
        if (distanceKm != null && distanceKm > 0f) {
            parts += "Dist: ${"%.2f".format(distanceKm)} km"
        }
        if (detectedProprietarySensors.isNotEmpty() && heartRateBpm == null && !hasStandardAndroidHrSensor) {
            parts += "Sensors: ${detectedProprietarySensors.joinToString("/")}"
        }
        append(parts.joinToString(" | "))
        if (!diagnosticNote.isNullOrBlank()) {
            append(" ($diagnosticNote)")
        }
    }

    private fun formatTime(epochMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMs))
}

/**
 * Battery-Aware Full Android Watch (FAW) Health & Heart Rate Bridge.
 *
 * Uses a 3-tier priority order tailored for Android 15 FAWs running **TraX / Better Health Tracker**
 * (`com.ailife.betterhealth`) while avoiding legacy V1 `GET_DATA_API` broadcast battery drain whenever
 * possible:
 *
 *  1. **Tier 1 — TraX V2 ContentProvider (`content://com.ailife.betterhealth.healthprovider/...`)**:
 *     Permission-gated by `com.ailife.betterhealth.READ_HEALTH_DATA`. Reads cached `heartrate`, `oxygen`,
 *     and today's `steps` directly from TraX's SQLite store with **zero broadcast wakeup and zero extra
 *     optical PPG LED drain**.
 *  2. **Tier 2 — Native Android `Sensor.TYPE_HEART_RATE` (21) & `Sensor.TYPE_STEP_COUNTER` (19)**:
 *     On FAWs that expose a standard Android heart-rate sensor, live HR scans (`forceLiveMeasurement = true`)
 *     sample `Sensor.TYPE_HEART_RATE` directly via `SensorManager` and **immediately call
 *     `unregisterListener`** on the first stabilized BPM reading (`35..230`) so the green optical LED
 *     turns off right away.
 *  3. **Tier 3 — Legacy `com.ailife.betterhealth.GET_DATA_API` Broadcast + Proprietary HSC/CD Sensors**:
 *     Used only as a fallback when Tier 1 & Tier 2 do not cover a requested metric (e.g., live optical
 *     SpO2 `OXYGEN` measurement or FAWs whose HR hardware is hidden under vendor types `31`, `43`,
 *     `65596`, `65598`, `65599`).
 */
class BetterHealthTrackerBridge(
    private val context: Context
) {
    companion object {
        const val BETTER_HEALTH_PACKAGE = "com.ailife.betterhealth"
        const val ACTION_GET_DATA_API = "com.ailife.betterhealth.GET_DATA_API"
        const val ACTION_DATA_REPLY = "com.ailife.betterhealth.DATA_REPLY"
        const val CONTENT_AUTHORITY = "com.ailife.betterhealth.healthprovider"
        const val PERMISSION_READ_HEALTH_DATA = "com.ailife.betterhealth.READ_HEALTH_DATA"

        const val DATA_HR = "HR"
        const val DATA_OXYGEN = "OXYGEN"
        const val DATA_STEP = "STEP"
        const val DATA_CALORIE = "CALORIE"
        const val DATA_DISTANCE = "DISTANCE"

        const val TYPE_LAST = "LAST"
        const val TYPE_TODAY = "TODAY"
        const val TYPE_NOW = "NOW"
        const val TYPE_IMMEDIATELY = "IMMEDIATELY"

        private const val CACHED_BROADCAST_TIMEOUT_MS = 2_800L
        private const val LIVE_OPTICAL_BROADCAST_TIMEOUT_MS = 22_000L
        private const val NATIVE_HR_LIVE_TIMEOUT_MS = 10_000L
        private const val NATIVE_STEP_TIMEOUT_MS = 1_500L

        /** Known non-standard FAW optical HR / SpO2 sensor type IDs across HSC & CD watch firmwares. */
        private val KNOWN_FAW_VENDOR_SENSOR_TYPES = setOf(
            31,
            43,
            65596,
            65598,
            65599
        )
    }

    private val broadcastMutex = Mutex()

    @Volatile
    private var lastSnapshot: FawHealthSnapshot? = null

    fun isBetterHealthInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(BETTER_HEALTH_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun hasStandardHeartRateSensor(): Boolean {
        val sensorMgr = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        return sensorMgr?.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null
    }

    /**
     * Returns runtime permissions that exist on this FAW and have `DANGEROUS` protection level
     * (including `com.ailife.betterhealth.READ_HEALTH_DATA` when TraX V2 is installed).
     */
    fun getRequestableRuntimePermissions(): List<String> {
        val candidates = mutableListOf(
            android.Manifest.permission.BODY_SENSORS,
            android.Manifest.permission.ACTIVITY_RECOGNITION
        )
        runCatching {
            val permInfo = context.packageManager.getPermissionInfo(PERMISSION_READ_HEALTH_DATA, 0)
            if (permInfo.protection == PermissionInfo.PROTECTION_DANGEROUS) {
                candidates += PERMISSION_READ_HEALTH_DATA
            }
        }
        return candidates.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Reads watch vitals using TraX V2 ContentProvider + native Android `Sensor.TYPE_HEART_RATE` first,
     * falling back to legacy `GET_DATA_API` broadcasts or vendor sensor IDs only when required.
     */
    suspend fun readHealthSnapshot(
        requestedMetric: String = "ALL",
        forceLiveMeasurement: Boolean = false
    ): FawHealthSnapshot = withContext(Dispatchers.IO) {
        val normalizedMetric = requestedMetric.uppercase(Locale.US)
        val sensorMgr = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val nativeHrSensor = sensorMgr?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        val nativeStepSensor = sensorMgr?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val bhtInstalled = isBetterHealthInstalled()

        // Tier 1: Zero-battery-drain TraX V2 ContentProvider query (`content://com.ailife.betterhealth.healthprovider`)
        val v2ProviderData = if (bhtInstalled) queryTraxV2ContentProvider() else null

        // Tier 2: If live HR requested (or no cached HR yet) AND standard Android TYPE_HEART_RATE exists,
        // sample native SensorManager directly and immediately unregister so optical LED turns off cleanly.
        var nativeLiveHr: Int? = null
        if (
            nativeHrSensor != null &&
            (normalizedMetric == DATA_HR || normalizedMetric == "ALL") &&
            (forceLiveMeasurement || v2ProviderData?.heartRateBpm == null)
        ) {
            val timeout = if (forceLiveMeasurement) NATIVE_HR_LIVE_TIMEOUT_MS else 2_500L
            nativeLiveHr = sampleSingleSensorValue(sensorMgr, nativeHrSensor, timeout)
                ?.toInt()
                ?.takeIf { it in 35..230 }
        }

        val nativeSteps = if (v2ProviderData?.stepsToday == null && nativeStepSensor != null) {
            sampleSingleSensorValue(sensorMgr, nativeStepSensor, NATIVE_STEP_TIMEOUT_MS)
                ?.toInt()
                ?.takeIf { it >= 0 }
        } else {
            null
        }

        // Check if Tier 1 + Tier 2 already satisfied the request without touching legacy GET_DATA_API!
        val mergedHr = nativeLiveHr ?: v2ProviderData?.heartRateBpm
        val mergedSpo2 = v2ProviderData?.spo2Percent
        val mergedSteps = v2ProviderData?.stepsToday ?: nativeSteps

        val needsLegacyBroadcast = bhtInstalled && (
            (forceLiveMeasurement && normalizedMetric == DATA_OXYGEN) ||
                (forceLiveMeasurement && (normalizedMetric == DATA_HR || normalizedMetric == "ALL") && nativeLiveHr == null) ||
                (mergedHr == null && mergedSpo2 == null && mergedSteps == null)
            )

        if (!needsLegacyBroadcast && (mergedHr != null || mergedSpo2 != null || mergedSteps != null || nativeHrSensor != null)) {
            val sourceLabel = when {
                nativeLiveHr != null && v2ProviderData != null -> "Android HR(21) + TraX V2"
                nativeLiveHr != null -> "Android Sensor.TYPE_HEART_RATE"
                v2ProviderData != null -> "TraX V2 ContentProvider"
                else -> "Android Watch Sensors"
            }
            val snap = FawHealthSnapshot(
                providerSource = sourceLabel,
                heartRateBpm = mergedHr,
                heartRateTimestampMs = if (nativeLiveHr != null) System.currentTimeMillis() else v2ProviderData?.heartRateTimestampMs,
                heartRateIsLiveSample = nativeLiveHr != null,
                spo2Percent = mergedSpo2,
                spo2TimestampMs = v2ProviderData?.spo2TimestampMs,
                stepsToday = mergedSteps,
                hasStandardAndroidHrSensor = nativeHrSensor != null
            )
            lastSnapshot = snap
            return@withContext snap
        }

        // Tier 3A: Legacy `com.ailife.betterhealth.GET_DATA_API` broadcast fallback (e.g., live SpO2 or proprietary FAW HR)
        if (bhtInstalled) {
            val legacySnap = queryLegacyBetterHealthBroadcast(
                requestedMetric = normalizedMetric,
                forceLiveMeasurement = forceLiveMeasurement,
                v2Fallback = v2ProviderData,
                hasStandardHr = nativeHrSensor != null
            )
            if (legacySnap != null) {
                lastSnapshot = legacySnap
                return@withContext legacySnap
            }
        }

        // Tier 3B: Direct proprietary FAW HSC/CD vendor sensor probe (`31, 43, 65596, 65598, 65599`)
        probeProprietaryFawSensors(
            sensorMgr = sensorMgr,
            hasStandardHr = nativeHrSensor != null,
            nativeSteps = mergedSteps,
            note = if (bhtInstalled) {
                "Grant READ_HEALTH_DATA to WristMind or wear watch snugly for live PPG lock"
            } else {
                null
            }
        )
    }

    /**
     * Reads TraX V2 ContentProvider directly (`last/heartrate`, `last/oxygen`, and today's `records/range`)
     * without waking the legacy V1 broadcast receiver.
     */
    private fun queryTraxV2ContentProvider(): FawHealthSnapshot? {
        val (lastHr, hrTs) = queryContentProviderLast("heartrate")
        val (lastSpo2, spo2Ts) = queryContentProviderLast("oxygen")
        val todaySteps = queryContentProviderTodaySteps()

        if (lastHr == null && lastSpo2 == null && todaySteps == null) {
            return null
        }
        return FawHealthSnapshot(
            providerSource = "TraX V2 ContentProvider",
            heartRateBpm = lastHr,
            heartRateTimestampMs = hrTs,
            heartRateIsLiveSample = false,
            spo2Percent = lastSpo2,
            spo2TimestampMs = spo2Ts,
            stepsToday = todaySteps
        )
    }

    private fun queryContentProviderTodaySteps(): Int? = runCatching {
        val startOfDayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startSec = startOfDayCal.timeInMillis / 1_000L
        val endSec = System.currentTimeMillis() / 1_000L
        val uri = Uri.parse("content://$CONTENT_AUTHORITY/records/range/$startSec/$endSec")
        var maxSteps = 0
        var hasRow = false
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val stepsIdx = cursor.getColumnIndex("steps")
            if (stepsIdx >= 0) {
                while (cursor.moveToNext()) {
                    hasRow = true
                    val v = cursor.getInt(stepsIdx)
                    if (v > maxSteps) maxSteps = v
                }
            }
        }
        if (hasRow) maxSteps else null
    }.getOrNull()

    private suspend fun queryLegacyBetterHealthBroadcast(
        requestedMetric: String,
        forceLiveMeasurement: Boolean,
        v2Fallback: FawHealthSnapshot?,
        hasStandardHr: Boolean
    ): FawHealthSnapshot? = broadcastMutex.withLock {
        var permissionDenied = false

        val liveHr: Int? = if (forceLiveMeasurement && (requestedMetric == DATA_HR || requestedMetric == "ALL")) {
            val immediate = requestIntBroadcast(
                dataType = DATA_HR,
                queryType = TYPE_IMMEDIATELY,
                timeoutMs = LIVE_OPTICAL_BROADCAST_TIMEOUT_MS
            ) { err -> if (err == "PERMISSION_DENIED") permissionDenied = true }
            immediate ?: requestIntBroadcast(
                dataType = DATA_HR,
                queryType = TYPE_NOW,
                timeoutMs = LIVE_OPTICAL_BROADCAST_TIMEOUT_MS
            ) { err -> if (err == "PERMISSION_DENIED") permissionDenied = true }
        } else {
            null
        }

        val liveSpo2: Int? = if (forceLiveMeasurement && requestedMetric == DATA_OXYGEN) {
            requestIntBroadcast(
                dataType = DATA_OXYGEN,
                queryType = TYPE_IMMEDIATELY,
                timeoutMs = LIVE_OPTICAL_BROADCAST_TIMEOUT_MS
            ) { err -> if (err == "PERMISSION_DENIED") permissionDenied = true }
        } else {
            null
        }

        val cachedHr = liveHr ?: v2Fallback?.heartRateBpm ?: requestIntBroadcast(
            dataType = DATA_HR,
            queryType = TYPE_LAST,
            timeoutMs = CACHED_BROADCAST_TIMEOUT_MS
        ) { err -> if (err == "PERMISSION_DENIED") permissionDenied = true }

        val cachedSpo2 = liveSpo2 ?: v2Fallback?.spo2Percent ?: if (requestedMetric == "ALL" || requestedMetric == DATA_OXYGEN) {
            requestIntBroadcast(
                dataType = DATA_OXYGEN,
                queryType = TYPE_LAST,
                timeoutMs = CACHED_BROADCAST_TIMEOUT_MS
            ) { err -> if (err == "PERMISSION_DENIED") permissionDenied = true }
        } else {
            null
        }

        val steps = v2Fallback?.stepsToday ?: if (requestedMetric == "ALL" || requestedMetric == DATA_STEP) {
            requestIntBroadcast(
                dataType = DATA_STEP,
                queryType = TYPE_TODAY,
                timeoutMs = CACHED_BROADCAST_TIMEOUT_MS
            ) { err -> if (err == "PERMISSION_DENIED") permissionDenied = true }
        } else {
            lastSnapshot?.stepsToday
        }

        val calories = if (requestedMetric == "ALL") {
            requestIntBroadcast(
                dataType = DATA_CALORIE,
                queryType = TYPE_TODAY,
                timeoutMs = CACHED_BROADCAST_TIMEOUT_MS
            )
        } else {
            lastSnapshot?.caloriesKcal
        }

        val distance = if (requestedMetric == "ALL") {
            requestFloatBroadcast(
                dataType = DATA_DISTANCE,
                queryType = TYPE_TODAY,
                timeoutMs = CACHED_BROADCAST_TIMEOUT_MS
            )
        } else {
            lastSnapshot?.distanceKm
        }

        val finalHr = cachedHr?.takeIf { it > 0 }
        val finalSpo2 = cachedSpo2?.takeIf { it > 0 }

        if (finalHr == null && finalSpo2 == null && steps == null && permissionDenied) {
            return@withLock FawHealthSnapshot(
                providerSource = "BetterHealth/TraX",
                hasStandardAndroidHrSensor = hasStandardHr,
                diagnosticNote = "Permission denied — grant READ_HEALTH_DATA in TraX / Android Settings"
            )
        }

        if (finalHr == null && finalSpo2 == null && steps == null && calories == null) {
            return@withLock null
        }

        FawHealthSnapshot(
            providerSource = "BetterHealth/TraX (Broadcast Fallback)",
            heartRateBpm = finalHr,
            heartRateTimestampMs = if (liveHr != null) System.currentTimeMillis() else v2Fallback?.heartRateTimestampMs,
            heartRateIsLiveSample = liveHr != null,
            spo2Percent = finalSpo2,
            spo2TimestampMs = if (liveSpo2 != null) System.currentTimeMillis() else v2Fallback?.spo2TimestampMs,
            stepsToday = steps,
            caloriesKcal = calories,
            distanceKm = distance,
            hasStandardAndroidHrSensor = hasStandardHr
        )
    }

    private suspend fun requestIntBroadcast(
        dataType: String,
        queryType: String,
        timeoutMs: Long,
        onError: (String) -> Unit = {}
    ): Int? {
        val latestInstant = AtomicInteger(-1)
        var receiverRef: BroadcastReceiver? = null
        return try {
            val completed = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Int?> { continuation ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, intent: Intent?) {
                            if (intent?.action != ACTION_DATA_REPLY) return
                            val returnedData = intent.getStringExtra("DATA")
                            if (returnedData != null && returnedData != dataType) return

                            val error = intent.getStringExtra("ERROR")
                            if (!error.isNullOrBlank() && error != "NONE") {
                                onError(error)
                                safeUnregister(this)
                                if (continuation.isActive) continuation.resume(null)
                                return
                            }

                            val instantVal = intent.getIntExtra("RESULT_INSTANT", -1)
                            if (instantVal > 0) {
                                latestInstant.set(instantVal)
                                if (!intent.hasExtra("RESULT")) {
                                    return
                                }
                            }

                            val parsedResult = readNumericExtraAsInt(intent, "RESULT")
                            val chosen = parsedResult?.takeIf { it > 0 }
                                ?: instantVal.takeIf { it > 0 }
                                ?: parsedResult

                            safeUnregister(this)
                            if (continuation.isActive) {
                                continuation.resume(chosen)
                            }
                        }
                    }

                    receiverRef = receiver
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        IntentFilter(ACTION_DATA_REPLY),
                        ContextCompat.RECEIVER_EXPORTED
                    )

                    val request = Intent(ACTION_GET_DATA_API).apply {
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        setPackage(BETTER_HEALTH_PACKAGE)
                        putExtra("DATA", dataType)
                        putExtra("TYPE", queryType)
                    }
                    context.sendBroadcast(request)

                    continuation.invokeOnCancellation {
                        safeUnregister(receiver)
                    }
                }
            }
            completed ?: latestInstant.get().takeIf { it > 0 }
        } finally {
            receiverRef?.let { safeUnregister(it) }
        }
    }

    private suspend fun requestFloatBroadcast(
        dataType: String,
        queryType: String,
        timeoutMs: Long
    ): Float? {
        var receiverRef: BroadcastReceiver? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Float?> { continuation ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, intent: Intent?) {
                            if (intent?.action != ACTION_DATA_REPLY) return
                            val returnedData = intent.getStringExtra("DATA")
                            if (returnedData != null && returnedData != dataType) return

                            val error = intent.getStringExtra("ERROR")
                            val parsed = if (error == null || error == "NONE") {
                                readNumericExtraAsFloat(intent, "RESULT")
                            } else {
                                null
                            }
                            safeUnregister(this)
                            if (continuation.isActive) continuation.resume(parsed)
                        }
                    }

                    receiverRef = receiver
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        IntentFilter(ACTION_DATA_REPLY),
                        ContextCompat.RECEIVER_EXPORTED
                    )

                    val request = Intent(ACTION_GET_DATA_API).apply {
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        setPackage(BETTER_HEALTH_PACKAGE)
                        putExtra("DATA", dataType)
                        putExtra("TYPE", queryType)
                    }
                    context.sendBroadcast(request)

                    continuation.invokeOnCancellation {
                        safeUnregister(receiver)
                    }
                }
            }
        } finally {
            receiverRef?.let { safeUnregister(it) }
        }
    }

    private fun readNumericExtraAsInt(intent: Intent, key: String): Int? {
        if (!intent.hasExtra(key)) return null
        val strVal = intent.getStringExtra(key)
        if (strVal != null) return strVal.toIntOrNull() ?: strVal.toDoubleOrNull()?.toInt()
        val intVal = intent.getIntExtra(key, Int.MIN_VALUE)
        if (intVal != Int.MIN_VALUE) return intVal
        val dblVal = intent.getDoubleExtra(key, Double.NaN)
        if (!dblVal.isNaN()) return dblVal.toInt()
        val fltVal = intent.getFloatExtra(key, Float.NaN)
        if (!fltVal.isNaN()) return fltVal.toInt()
        return null
    }

    private fun readNumericExtraAsFloat(intent: Intent, key: String): Float? {
        if (!intent.hasExtra(key)) return null
        val strVal = intent.getStringExtra(key)
        if (strVal != null) return strVal.toFloatOrNull()
        val fltVal = intent.getFloatExtra(key, Float.NaN)
        if (!fltVal.isNaN()) return fltVal
        val dblVal = intent.getDoubleExtra(key, Double.NaN)
        if (!dblVal.isNaN()) return dblVal.toFloat()
        val intVal = intent.getIntExtra(key, Int.MIN_VALUE)
        if (intVal != Int.MIN_VALUE) return intVal.toFloat()
        return null
    }

    private fun queryContentProviderLast(endpoint: String): Pair<Int?, Long?> = runCatching {
        val uri = Uri.parse("content://$CONTENT_AUTHORITY/last/$endpoint")
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val unixIdx = cursor.getColumnIndex("unix")
                val tsMs = if (unixIdx >= 0) {
                    cursor.getLong(unixIdx).takeIf { it > 0L }?.let { raw ->
                        if (raw < 10_000_000_000L) raw * 1_000L else raw
                    }
                } else {
                    null
                }
                val valueColCandidates = when (endpoint) {
                    "heartrate" -> listOf("heartrate", "hr", "value")
                    "oxygen" -> listOf("bloodoxygen", "oxygen", "spo2", "value")
                    else -> listOf("value")
                }
                val metricVal = valueColCandidates
                    .map { cursor.getColumnIndex(it) }
                    .firstOrNull { it >= 0 }
                    ?.let { cursor.getInt(it) }
                    ?.takeIf { it > 0 }
                return@runCatching metricVal to tsMs
            }
        }
        null to null
    }.getOrDefault(null to null)

    private suspend fun probeProprietaryFawSensors(
        sensorMgr: SensorManager?,
        hasStandardHr: Boolean,
        nativeSteps: Int?,
        note: String?
    ): FawHealthSnapshot {
        if (sensorMgr == null) {
            return FawHealthSnapshot(
                providerSource = "FAW SensorManager",
                diagnosticNote = "SensorManager unavailable"
            )
        }

        val allSensors = sensorMgr.getSensorList(Sensor.TYPE_ALL)
        val vendorCandidates = allSensors.filter { sensor ->
            sensor.type in KNOWN_FAW_VENDOR_SENSOR_TYPES ||
                sensor.name.contains("heart", ignoreCase = true) ||
                sensor.name.contains("ppg", ignoreCase = true) ||
                sensor.stringType.contains("heart", ignoreCase = true)
        }

        val sampledHr = vendorCandidates.firstOrNull()?.let { candidate ->
            sampleSingleSensorValue(sensorMgr, candidate, 3_000L)?.toInt()?.takeIf { it in 35..230 }
        }

        val labels = vendorCandidates.take(3).map { "${it.name}(type=${it.type})" }

        return FawHealthSnapshot(
            providerSource = if (hasStandardHr) "Android HR(21)" else "FAW Raw Sensors",
            heartRateBpm = sampledHr,
            heartRateTimestampMs = sampledHr?.let { System.currentTimeMillis() },
            heartRateIsLiveSample = sampledHr != null,
            stepsToday = nativeSteps,
            hasStandardAndroidHrSensor = hasStandardHr,
            detectedProprietarySensors = labels,
            diagnosticNote = note
        )
    }

    private suspend fun sampleSingleSensorValue(
        sensorManager: SensorManager,
        sensor: Sensor,
        timeoutMs: Long
    ): Float? {
        var listenerRef: SensorEventListener? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Float?> { continuation ->
                    val listener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent?) {
                            val v = event?.values?.firstOrNull()
                            if (v != null && v > 0f) {
                                // Unregister immediately so optical PPG green LED turns off right away
                                sensorManager.unregisterListener(this)
                                if (continuation.isActive) continuation.resume(v)
                            }
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                    }
                    listenerRef = listener
                    val registered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        sensorManager.registerListener(
                            listener,
                            sensor,
                            SensorManager.SENSOR_DELAY_NORMAL,
                            Handler(Looper.getMainLooper())
                        )
                    } else {
                        false
                    }
                    if (!registered && continuation.isActive) {
                        continuation.resume(null)
                    }
                    continuation.invokeOnCancellation {
                        runCatching { sensorManager.unregisterListener(listener) }
                    }
                }
            }
        } finally {
            listenerRef?.let { runCatching { sensorManager.unregisterListener(it) } }
        }
    }

    private fun safeUnregister(receiver: BroadcastReceiver) {
        runCatching { context.unregisterReceiver(receiver) }
    }
}
