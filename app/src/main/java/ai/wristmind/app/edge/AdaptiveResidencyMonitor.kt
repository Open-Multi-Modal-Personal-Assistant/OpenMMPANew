package ai.wristmind.app.edge

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Debug
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ResidencyMode(val badge: String) {
    CO_RESIDENT("CO-RES"),
    AUTO_SWAP_ON_DEMAND("AUTO-SWAP")
}

enum class MemoryPressureLevel {
    HEALTHY,
    TIGHT,
    CRITICAL
}

data class DeviceMemoryTelemetry(
    val physicalTotalMb: Long = 4096L,
    val memAvailableMb: Long = 1400L,
    val appPssMb: Long = 0L,
    val nativeHeapMb: Long = 0L,
    val residencyMode: ResidencyMode = ResidencyMode.CO_RESIDENT,
    val pressureLevel: MemoryPressureLevel = MemoryPressureLevel.HEALTHY,
    val lastTrimLevel: Int = 0
)

/**
 * Monitors real physical LPDDR RAM directly via `/proc/meminfo` (bypassing OEM Virtual-RAM spoofing
 * in ActivityManager.totalMem) alongside Android `onTrimMemory()` callbacks.
 *
 * Keeps EmbeddingGemma 2 Text+Vision (440M) and the active Gallery LLM/VLM co-resident by default,
 * transitioning to [ResidencyMode.AUTO_SWAP_ON_DEMAND] only if free headroom drops below [SWAP_WATERMARK_MB].
 */
class AdaptiveResidencyMonitor : ComponentCallbacks2 {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var userPinnedMode: ResidencyMode? = null
    private val lowMemoryListeners = mutableListOf<suspend () -> Unit>()

    private val _telemetry = MutableStateFlow(sampleNow(lastTrim = 0))
    val telemetry: StateFlow<DeviceMemoryTelemetry> = _telemetry.asStateFlow()

    fun startMonitoring(intervalMs: Long = 2_500L) {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                val snapshot = sampleNow(lastTrim = _telemetry.value.lastTrimLevel)
                _telemetry.value = snapshot
                if (snapshot.pressureLevel == MemoryPressureLevel.CRITICAL) {
                    notifyLowMemoryListeners()
                }
                delay(intervalMs)
            }
        }
    }

    fun registerLowMemoryListener(listener: suspend () -> Unit) {
        synchronized(lowMemoryListeners) {
            lowMemoryListeners += listener
        }
    }

    fun setPinnedResidencyMode(mode: ResidencyMode?) {
        userPinnedMode = mode
        _telemetry.value = sampleNow(lastTrim = _telemetry.value.lastTrimLevel)
    }

    fun refreshNow(): DeviceMemoryTelemetry {
        val snap = sampleNow(lastTrim = _telemetry.value.lastTrimLevel)
        _telemetry.value = snap
        return snap
    }

    override fun onTrimMemory(level: Int) {
        val snap = sampleNow(lastTrim = level)
        _telemetry.value = snap
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            scope.launch { notifyLowMemoryListeners() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    private suspend fun notifyLowMemoryListeners() {
        val copy = synchronized(lowMemoryListeners) { lowMemoryListeners.toList() }
        for (listener in copy) {
            runCatching { listener() }
        }
    }

    private fun sampleNow(lastTrim: Int): DeviceMemoryTelemetry {
        val (memTotalMb, memAvailMb) = readProcMemInfoMb()
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
        val pssMb = runCatching { Debug.getPss() / 1024L }.getOrDefault(nativeHeapMb)

        val pressure = when {
            memAvailMb < CRITICAL_WATERMARK_MB ||
                lastTrim >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryPressureLevel.CRITICAL
            memAvailMb < SWAP_WATERMARK_MB ||
                lastTrim >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryPressureLevel.TIGHT
            else -> MemoryPressureLevel.HEALTHY
        }

        val effectiveMode = userPinnedMode ?: when (pressure) {
            MemoryPressureLevel.HEALTHY -> ResidencyMode.CO_RESIDENT
            MemoryPressureLevel.TIGHT, MemoryPressureLevel.CRITICAL -> ResidencyMode.AUTO_SWAP_ON_DEMAND
        }

        return DeviceMemoryTelemetry(
            physicalTotalMb = memTotalMb,
            memAvailableMb = memAvailMb,
            appPssMb = pssMb,
            nativeHeapMb = nativeHeapMb,
            residencyMode = effectiveMode,
            pressureLevel = pressure,
            lastTrimLevel = lastTrim
        )
    }

    private fun readProcMemInfoMb(): Pair<Long, Long> {
        return try {
            var totalKb = 4_194_304L
            var availKb = 1_433_600L
            File("/proc/meminfo").useLines { lines ->
                for (line in lines) {
                    when {
                        line.startsWith("MemTotal:") -> {
                            totalKb = extractKb(line, totalKb)
                        }
                        line.startsWith("MemAvailable:") -> {
                            availKb = extractKb(line, availKb)
                        }
                    }
                }
            }
            (totalKb / 1024L) to (availKb / 1024L)
        } catch (_: Throwable) {
            4096L to 1400L
        }
    }

    private fun extractKb(line: String, fallback: Long): Long =
        Regex("(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: fallback

    companion object {
        const val SWAP_WATERMARK_MB = 340L
        const val CRITICAL_WATERMARK_MB = 220L
    }
}
