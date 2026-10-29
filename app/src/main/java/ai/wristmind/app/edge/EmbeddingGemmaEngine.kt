package ai.wristmind.app.edge

import ai.wristmind.app.data.memory.HybridSearchMath
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.InputData
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single-owner LiteRT-LM 0.18.0 [EmbeddingEngine] wrapper for EmbeddingGemma 2 (Text+Vision 440M
 * or Text 270M), producing 256-dimensional MRL L2-normalized float32 embeddings.
 *
 * Guardrails enforced:
 * 1. Never enables FP16 activation modes (prevents silent EmbeddingGemma 2 NaN overflow).
 * 2. Applies Matryoshka truncation (768d -> 256d) + double-precision L2 normalization.
 * 3. Provides a zero-RAM deterministic N-gram SimHash-256d fallback before `.litertlm` weights
 *    are downloaded so offline hybrid search + System-1 demos work immediately out of the box.
 */
class EmbeddingGemmaEngine(
    private val context: Context,
    private val residencyMonitor: AdaptiveResidencyMonitor
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()

    private var engine: EmbeddingEngine? = null
    private var loadedSpec: EdgeModelSpec? = null

    private val _isNeuralLoaded = MutableStateFlow(false)
    val isNeuralLoaded: StateFlow<Boolean> = _isNeuralLoaded.asStateFlow()

    var activeModeLabel: String = "SimHash-256d (Ready • Add EG2 .litertlm)"
        private set

    init {
        residencyMonitor.registerLowMemoryListener {
            if (residencyMonitor.telemetry.value.residencyMode == ResidencyMode.AUTO_SWAP_ON_DEMAND) {
                unloadIdleWeights()
            }
        }
    }

    suspend fun tryLoadBestAvailableModel(
        preferredSpec: EdgeModelSpec = ModelCatalog.EMBEDDING_GEMMA2_TEXT_VISION_440M,
        enableGpuForVision: Boolean = false
    ): Boolean = withContext(dispatcher) {
        mutex.withLock {
            val chosenSpec = listOf(
                preferredSpec,
                ModelCatalog.EMBEDDING_GEMMA2_TEXT_VISION_440M,
                ModelCatalog.EMBEDDING_GEMMA2_TEXT_270M
            ).distinctBy { it.id }
                .firstOrNull { ModelCatalog.resolveInstalledFile(context, it) != null }
                ?: return@withLock false

            val modelFile = ModelCatalog.resolveInstalledFile(context, chosenSpec)
                ?: return@withLock false

            if (engine != null && loadedSpec?.id == chosenSpec.id) {
                return@withLock true
            }

            closeUnlocked()
            try {
                val visionBackend = when {
                    !chosenSpec.supportsVision -> null
                    enableGpuForVision -> Backend.GPU()
                    else -> Backend.CPU()
                }
                val config = EmbeddingEngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(), // CPU XNNPACK avoids FP16 activation overflow & GPU leaks
                    visionBackend = visionBackend
                )
                val initialized = EmbeddingEngine(config).also { it.initialize() }
                engine = initialized
                loadedSpec = chosenSpec
                _isNeuralLoaded.value = true
                activeModeLabel = "${chosenSpec.shortBadge} (256d MRL)"
                residencyMonitor.refreshNow()
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize ${chosenSpec.displayName}, keeping SimHash-256d fallback", t)
                closeUnlocked()
                false
            }
        }
    }

    suspend fun embedRawText256(formattedTextWithTaskPrefix: String): FloatArray = withContext(dispatcher) {
        mutex.withLock {
            if (engine == null) {
                tryAutoWarmUnlocked()
            }
            val activeEngine = engine
            if (activeEngine != null) {
                try {
                    val raw = activeEngine.computeEmbedding(
                        listOf(InputData.Text(formattedTextWithTaskPrefix))
                    ).embedding
                    return@withLock HybridSearchMath.truncateAndNormalizeMrl(raw)
                } catch (t: Throwable) {
                    Log.w(TAG, "Neural text embedding failed; using deterministic 256d fallback", t)
                }
            }
            deterministicSemanticSimHash256(formattedTextWithTaskPrefix)
        }
    }

    suspend fun embedImage256(imageBytes: ByteArray): FloatArray = withContext(dispatcher) {
        mutex.withLock {
            if (engine == null) {
                tryAutoWarmUnlocked()
            }
            val activeEngine = engine
            if (activeEngine != null && loadedSpec?.supportsVision == true) {
                try {
                    val raw = activeEngine.computeEmbedding(
                        listOf(InputData.Image(imageBytes))
                    ).embedding
                    return@withLock HybridSearchMath.truncateAndNormalizeMrl(raw)
                } catch (t: Throwable) {
                    Log.w(TAG, "Neural vision embedding failed; using byte-histogram 256d fallback", t)
                }
            }
            deterministicImageSignature256(imageBytes)
        }
    }

    suspend fun unloadIdleWeights() = withContext(dispatcher) {
        mutex.withLock {
            closeUnlocked()
            residencyMonitor.refreshNow()
        }
    }

    private fun tryAutoWarmUnlocked() {
        val candidate = ModelCatalog.EMBEDDING_MODELS.firstOrNull {
            ModelCatalog.resolveInstalledFile(context, it) != null
        } ?: return
        val file = ModelCatalog.resolveInstalledFile(context, candidate) ?: return
        try {
            val config = EmbeddingEngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.CPU(),
                visionBackend = if (candidate.supportsVision) Backend.CPU() else null
            )
            engine = EmbeddingEngine(config).also { it.initialize() }
            loadedSpec = candidate
            _isNeuralLoaded.value = true
            activeModeLabel = "${candidate.shortBadge} (256d MRL)"
        } catch (t: Throwable) {
            Log.w(TAG, "Auto-warm skipped for ${candidate.fileName}", t)
            closeUnlocked()
        }
    }

    private fun closeUnlocked() {
        try {
            engine?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Ignored close error on EmbeddingEngine", t)
        } finally {
            engine = null
            loadedSpec = null
            _isNeuralLoaded.value = false
            activeModeLabel = "SimHash-256d (Ready • Add EG2 .litertlm)"
        }
    }

    internal fun deterministicSemanticSimHash256(text: String): FloatArray {
        val vec = FloatArray(HybridSearchMath.MRL_DIMENSIONS)
        val payload = text.substringAfter("| text:", text).lowercase().trim()
        val words = payload.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }

        for (word in words) {
            accumulateTokenHash(vec, word, weight = 1.6f)
            if (word.length >= 4) {
                accumulateTokenHash(vec, word.take(4), weight = 1.1f)
            }
            CONCEPT_SYNONYMS[word]?.forEach { canonical ->
                accumulateTokenHash(vec, "concept:$canonical", weight = 2.0f)
            }
            for (i in 0..(word.length - 3)) {
                accumulateTokenHash(vec, word.substring(i, i + 3), weight = 0.45f)
            }
        }
        if (words.isEmpty()) {
            accumulateTokenHash(vec, payload.ifEmpty { "empty" }, weight = 1.0f)
        }
        return HybridSearchMath.truncateAndNormalizeMrl(vec, HybridSearchMath.MRL_DIMENSIONS)
    }

    private fun deterministicImageSignature256(bytes: ByteArray): FloatArray {
        val vec = FloatArray(HybridSearchMath.MRL_DIMENSIONS)
        val stride = (bytes.size / 1024).coerceAtLeast(1)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            vec[b % HybridSearchMath.MRL_DIMENSIONS] += 1.0f
            i += stride
        }
        return HybridSearchMath.truncateAndNormalizeMrl(vec, HybridSearchMath.MRL_DIMENSIONS)
    }

    private fun accumulateTokenHash(vec: FloatArray, token: String, weight: Float) {
        var h1 = -3750763034362895579L
        for (ch in token) {
            h1 = (h1 xor ch.code.toLong()) * 1099511628211L
        }
        for (proj in 0 until 4) {
            val mixed = h1 xor (proj * 0x9E3779B97F4A7C15UL.toLong())
            val idx = ((mixed ushr 12) % HybridSearchMath.MRL_DIMENSIONS).toInt().let {
                if (it < 0) it + HybridSearchMath.MRL_DIMENSIONS else it
            }
            val sign = if ((mixed and 1L) == 0L) 1.0f else -1.0f
            val phase = (0.75f + 0.25f * cos((proj + 1).toDouble()).toFloat() + 0.1f * sin(idx.toDouble()).toFloat())
            vec[idx] += sign * weight * phase
        }
    }

    companion object {
        private const val TAG = "EmbeddingGemmaEngine"

        private val CONCEPT_SYNONYMS = mapOf(
            "car" to listOf("parking", "vehicle", "garage"),
            "parked" to listOf("parking", "vehicle", "garage"),
            "parking" to listOf("parking", "vehicle", "garage"),
            "garage" to listOf("parking", "vehicle", "garage"),
            "plate" to listOf("parking", "vehicle"),
            "wifi" to listOf("network", "passcode", "hotel"),
            "password" to listOf("network", "passcode", "code"),
            "passcode" to listOf("network", "passcode", "code"),
            "room" to listOf("hotel", "door"),
            "hotel" to listOf("hotel", "room"),
            "allergic" to listOf("health", "allergy", "medical"),
            "allergy" to listOf("health", "allergy", "medical"),
            "medicine" to listOf("health", "medication", "medical"),
            "medication" to listOf("health", "medication", "medical"),
            "alarm" to listOf("device_action", "timer", "clock"),
            "timer" to listOf("device_action", "timer", "clock"),
            "remind" to listOf("device_action", "reminder"),
            "heart" to listOf("device_action", "sensor", "health"),
            "pulse" to listOf("device_action", "sensor", "health"),
            "spo2" to listOf("device_action", "sensor", "health"),
            "oxygen" to listOf("device_action", "sensor", "health"),
            "nearby" to listOf("cloud_maps", "location"),
            "coffee" to listOf("cloud_maps", "location"),
            "restaurant" to listOf("cloud_maps", "location"),
            "live" to listOf("cloud_live", "camera")
        )
    }
}
