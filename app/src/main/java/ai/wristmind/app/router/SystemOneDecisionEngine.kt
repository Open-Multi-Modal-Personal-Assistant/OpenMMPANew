package ai.wristmind.app.router

import ai.wristmind.app.data.memory.HybridSearchMath
import ai.wristmind.app.edge.EmbeddingGemmaEngine
import ai.wristmind.app.security.PromptInjectionShield
import kotlin.math.exp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Target execution routes evaluated in parallel by the System-1 Decision Engine.
 */
enum class AgentRoute(val displayLabel: String) {
    LOCAL_MEMORY_RAG("Local Hybrid RAG"),
    ANDROID_DEVICE_SKILL("Wrist OS Skill"),
    VISUAL_SCENE_QA("Local SmolVLM"),
    LIVE_CLOUD_STREAM("Gemini Live 1fps"),
    CLOUD_GROUNDED_SEARCH("Firebase Cloud AI")
}

enum class ComplexityLevel {
    LOW,
    MEDIUM,
    HIGH
}

enum class ExecutionGate {
    /** P_max >= 0.80 (or device is offline): execute immediately on-device with zero network call. */
    AUTO_EXECUTE_LOCAL,

    /** 0.50 <= P_max < 0.80 on a state-mutating OS skill: prompt one-tap wrist confirmation card. */
    CONFIRM_ON_WRIST,

    /** P_max < 0.50 or explicit spatial/live cloud query while online: route to Firebase AI Logic. */
    ESCALATE_TO_FIREBASE_CLOUD
}

/**
 * Jev-inspired System-1 Typed Decision Primitives:
 *  - [Choice]: Calibrated categorical distribution summing to 1.0 (0% schema error by construction).
 *  - [Score]: Ordered discrete severity/complexity rating.
 *  - [Noul]: Calibrated boolean safety/precondition check with truth probability.
 */
data class ChoiceDecision<T>(
    val selected: T,
    val maxProbability: Float,
    val distribution: Map<T, Float>
)

data class ScoreDecision(
    val level: ComplexityLevel,
    val normalizedScore: Float
)

data class NoulDecision(
    val value: Boolean,
    val truthProbability: Float
)

data class SystemOneDecision(
    val routeChoice: ChoiceDecision<AgentRoute>,
    val complexityScore: ScoreDecision,
    val safetyNoul: NoulDecision,
    val gate: ExecutionGate,
    val latencyMs: Long,
    val routerBackend: String
)

/**
 * Zero-Marginal-RAM System-1 Decision Engine.
 *
 * Reuses the already-resident [EmbeddingGemmaEngine] with its `task: classification | text: ` prefix
 * against cached 256d MRL route centroids, combined with deterministic lexical anchor boosts and
 * temperature-scaled softmax ($T = 0.08$) calibrated for Brier-score confidence gating.
 */
class SystemOneDecisionEngine(
    private val embeddingEngine: EmbeddingGemmaEngine,
    private val promptShield: PromptInjectionShield
) {
    companion object {
        const val HIGH_CONFIDENCE_THRESHOLD = 0.80f
        const val MID_CONFIDENCE_THRESHOLD = 0.50f
        private const val SOFTMAX_TEMPERATURE = 0.08f

        private val ROUTE_PROTOTYPES: Map<AgentRoute, List<String>> = mapOf(
            AgentRoute.LOCAL_MEMORY_RAG to listOf(
                "Where did I park my car or what is my license plate and garage pillar?",
                "What is my hotel Wi-Fi password, room code, allergy, medication, or saved personal note?",
                "Search my local memories, saved OCR receipts, voice notes, and checklist"
            ),
            AgentRoute.ANDROID_DEVICE_SKILL to listOf(
                "Set an alarm for 7:00 AM, start a countdown timer for 10 minutes, or schedule a calendar event meeting",
                "Read or log my watch heart rate pulse BPM, SpO2 blood oxygen, step counter, calories, TraX Better Health Tracker vitals, battery, or run quick JavaScript calculator",
                "Turn on the watch flashlight torch, mute or change media volume, or open Bluetooth, brightness, or battery saver quick settings"
            ),
            AgentRoute.VISUAL_SCENE_QA to listOf(
                "Look at this captured photo and describe what is on the label or receipt",
                "Read the sign in the camera snapshot offline using the local vision model",
                "Inspect the snapped image on my watch"
            ),
            AgentRoute.LIVE_CLOUD_STREAM to listOf(
                "Start a live bidirectional Gemini camera and voice conversation stream",
                "Open real-time live streaming video assistant",
                "Talk live while streaming my watch camera continuously"
            ),
            AgentRoute.CLOUD_GROUNDED_SEARCH to listOf(
                "Find nearby coffee shops, pharmacies, or EV chargers open right now on Google Maps",
                "Explain a complex general world knowledge topic, news, or multi-city travel plan",
                "Search Google Maps around my current GPS location"
            )
        )

        /**
         * Pure math confidence gate so unit tests can verify exact Jev-style boundary transitions.
         */
        fun resolveExecutionGate(
            selectedRoute: AgentRoute,
            maxProbability: Float,
            isOnline: Boolean,
            hasAttachedImage: Boolean
        ): ExecutionGate {
            if (!isOnline) {
                return if (
                    selectedRoute == AgentRoute.ANDROID_DEVICE_SKILL &&
                    maxProbability < HIGH_CONFIDENCE_THRESHOLD
                ) {
                    ExecutionGate.CONFIRM_ON_WRIST
                } else {
                    ExecutionGate.AUTO_EXECUTE_LOCAL
                }
            }

            if (hasAttachedImage && selectedRoute == AgentRoute.VISUAL_SCENE_QA) {
                return ExecutionGate.AUTO_EXECUTE_LOCAL
            }

            if (selectedRoute == AgentRoute.LIVE_CLOUD_STREAM || selectedRoute == AgentRoute.CLOUD_GROUNDED_SEARCH) {
                return ExecutionGate.ESCALATE_TO_FIREBASE_CLOUD
            }

            return when {
                maxProbability >= HIGH_CONFIDENCE_THRESHOLD -> ExecutionGate.AUTO_EXECUTE_LOCAL
                maxProbability >= MID_CONFIDENCE_THRESHOLD -> {
                    if (selectedRoute == AgentRoute.ANDROID_DEVICE_SKILL) {
                        ExecutionGate.CONFIRM_ON_WRIST
                    } else {
                        ExecutionGate.AUTO_EXECUTE_LOCAL
                    }
                }
                else -> ExecutionGate.ESCALATE_TO_FIREBASE_CLOUD
            }
        }

        fun temperatureScaledSoftmax(
            rawLogits: Map<AgentRoute, Float>,
            temperature: Float = SOFTMAX_TEMPERATURE
        ): Map<AgentRoute, Float> {
            val safeTemp = temperature.coerceAtLeast(1e-3f)
            val maxScore = rawLogits.values.maxOrNull() ?: 0f
            val exps = rawLogits.mapValues { (_, score) ->
                exp(((score - maxScore) / safeTemp).toDouble())
            }
            val sumExp = exps.values.sum().coerceAtLeast(1e-12)
            return exps.mapValues { (_, v) -> (v / sumExp).toFloat() }
        }
    }

    private val centroidMutex = Mutex()
    private var cachedCentroids: Map<AgentRoute, FloatArray>? = null
    private var cachedBackendTag: String = ""

    suspend fun evaluate(
        utterance: String,
        isOnline: Boolean,
        hasAttachedImage: Boolean = false
    ): SystemOneDecision = withContext(Dispatchers.Default) {
        val startNs = System.nanoTime()

        // 1. Noul primitive: fast safety check
        val shieldVerdict = PromptInjectionShield.evaluateHeuristicRules(utterance)
        val safetyNoul = NoulDecision(
            value = shieldVerdict.isSafe,
            truthProbability = (1f - shieldVerdict.injectionProbability).coerceIn(0f, 1f)
        )

        // 2. Choice primitive: 256d EmbeddingGemma 2 classification vector vs route centroids
        val centroids = ensureCentroidsComputed()
        val queryVec = embeddingEngine.embedRawText256(
            HybridSearchMath.formatClassificationInput(utterance)
        )

        val rawScores = LinkedHashMap<AgentRoute, Float>()
        for ((route, centroid) in centroids) {
            val cosine = dotProduct(queryVec, centroid)
            val lexicalBoost = computeLexicalAnchorBoost(utterance, route, hasAttachedImage)
            rawScores[route] = cosine + lexicalBoost
        }

        val probs = temperatureScaledSoftmax(rawScores)
        val bestEntry = probs.maxByOrNull { it.value }!!
        val routeChoice = ChoiceDecision(
            selected = bestEntry.key,
            maxProbability = bestEntry.value,
            distribution = probs
        )

        // 3. Score primitive: estimate reasoning complexity (token length + multi-clause structure)
        val wordCount = utterance.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val complexityNorm = ((wordCount / 32f) +
            if (bestEntry.key == AgentRoute.CLOUD_GROUNDED_SEARCH) 0.45f else 0f)
            .coerceIn(0.05f, 1.0f)
        val complexityLevel = when {
            complexityNorm < 0.35f -> ComplexityLevel.LOW
            complexityNorm < 0.70f -> ComplexityLevel.MEDIUM
            else -> ComplexityLevel.HIGH
        }

        val gate = resolveExecutionGate(
            selectedRoute = bestEntry.key,
            maxProbability = bestEntry.value,
            isOnline = isOnline,
            hasAttachedImage = hasAttachedImage
        )

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        SystemOneDecision(
            routeChoice = routeChoice,
            complexityScore = ScoreDecision(complexityLevel, complexityNorm),
            safetyNoul = safetyNoul,
            gate = gate,
            latencyMs = elapsedMs,
            routerBackend = "Sys1-${embeddingEngine.activeModeLabel}"
        )
    }

    private suspend fun ensureCentroidsComputed(): Map<AgentRoute, FloatArray> {
        val currentBackend = embeddingEngine.activeModeLabel
        cachedCentroids?.takeIf { cachedBackendTag == currentBackend }?.let { return it }

        return centroidMutex.withLock {
            cachedCentroids?.takeIf { cachedBackendTag == currentBackend }?.let { return@withLock it }
            val built = LinkedHashMap<AgentRoute, FloatArray>()
            for ((route, exemplars) in ROUTE_PROTOTYPES) {
                val sum = FloatArray(HybridSearchMath.MRL_DIMENSIONS)
                for (sample in exemplars) {
                    val vec = embeddingEngine.embedRawText256(
                        HybridSearchMath.formatClassificationInput(sample)
                    )
                    for (i in sum.indices) {
                        sum[i] += vec[i]
                    }
                }
                built[route] = HybridSearchMath.truncateAndNormalizeMrl(
                    sum,
                    HybridSearchMath.MRL_DIMENSIONS
                )
            }
            cachedCentroids = built
            cachedBackendTag = currentBackend
            built
        }
    }

    private fun computeLexicalAnchorBoost(
        utterance: String,
        route: AgentRoute,
        hasAttachedImage: Boolean
    ): Float {
        val lower = utterance.lowercase()
        return when (route) {
            AgentRoute.ANDROID_DEVICE_SKILL -> when {
                Regex("""\b(set|create|start|schedule)\b.{0,20}\b(alarm|timer|countdown|calendar|meeting|appointment)\b""").containsMatchIn(lower) -> 0.25f
                Regex("""\b(measure|check|read|log|record|save|what'?s\s+my)\b.{0,22}\b(heart\s*rate|pulse|bpm|spo2|blood\s*oxygen|oxygen|steps?|vitals?|battery)\b""").containsMatchIn(lower) -> 0.25f
                Regex("""\b(turn\s+on|turn\s+off|toggle|mute|unmute|open|show)\b.{0,20}\b(torch|flashlight|volume|bluetooth|brightness|display|battery\s*saver)\b""").containsMatchIn(lower) -> 0.25f
                Regex("""\b(alarm|timer|torch|flashlight|volume|mute|unmute|bluetooth|calendar|heart\s*rate|pulse|bpm|spo2|blood\s*oxygen|steps?|calories?|vitals?|better\s*health|trax|sensor|battery|calculate|math|tip\s+split)\b""").containsMatchIn(lower) -> 0.16f
                else -> 0f
            }
            AgentRoute.LOCAL_MEMORY_RAG -> when {
                Regex("""\b(park|parked|plate|pillar|hotel|wi-?fi|room|allergy|allergic|vitamin|medication|checklist|remember|saved|vault)\b""").containsMatchIn(lower) -> 0.22f
                else -> 0f
            }
            AgentRoute.VISUAL_SCENE_QA -> when {
                hasAttachedImage -> 0.28f
                Regex("""\b(photo|snapshot|image|receipt|label|what\s+do\s+you\s+see)\b""").containsMatchIn(lower) -> 0.16f
                else -> 0f
            }
            AgentRoute.LIVE_CLOUD_STREAM -> when {
                Regex("""\b(gemini\\s+live|live\\s+stream|live\\s+camera|real-?time\\s+video)\b""").containsMatchIn(lower) -> 0.28f
                else -> 0f
            }
            AgentRoute.CLOUD_GROUNDED_SEARCH -> when {
                Regex("""\b(nearby|nearest|google\s+maps|around\s+me|open\s+now|coffee|pharmacy|directions|restaurants?)\b""").containsMatchIn(lower) -> 0.25f
                else -> 0f
            }
        }
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var acc = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            acc += a[i] * b[i]
        }
        return acc
    }
}
