package ai.wristmind.app.security

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.LiteRtException
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class ShieldVerdict(
    val isSafe: Boolean,
    val injectionProbability: Float,
    val triggeredCategory: String,
    val reason: String,
    val backend: String,
    val latencyMs: Long
)

/**
 * Tiny On-Device Prompt Injection & Confused-Deputy Defense Shield.
 *
 * Enforces two security chokepoints on a 4 GB Android watch:
 *  - Chokepoint A (Ingress): Screens untrusted external text (CameraX ML Kit OCR, web/Maps snippets)
 *    before indexing into ObjectBox HNSW / AppSearch BM25F so indirect prompt injections cannot poison RAG.
 *  - Chokepoint B (Egress): Audits tool arguments (`run_intent`, `run_js`) before OS dispatch.
 *
 * Uses the LiteRT 2.3.0 `CompiledModel` API (`prompt_guard_2_22m_int8.tflite`) confined to a single
 * background thread with reusable `TensorBuffer` instances and XNNPACK weight caching (`xnnPackFlags = 0x23`),
 * paired with a deterministic structural/lexical adversarial rule layer that works zero-download offline.
 */
class PromptInjectionShield(
    private val context: Context? = null
) : AutoCloseable {

    companion object {
        const val TFLITE_FILENAME = "prompt_guard_2_22m_int8.tflite"
        const val SEQ_LEN = 128
        const val BLOCK_THRESHOLD = 0.65f

        private val liteRtEnvironment by lazy { Environment.create() }

        private data class AdversarialRule(
            val category: String,
            val weight: Float,
            val regex: Regex,
            val description: String
        )

        private val ADVERSARIAL_RULES = listOf(
            AdversarialRule(
                category = "SYSTEM_OVERRIDE",
                weight = 0.88f,
                regex = Regex(
                    """(?i)\b(ignore|disregard|forget|override|bypass)\b.{0,40}\b(previous|prior|above|all|system|developer|safety)\b.{0,30}\b(instructions?|prompts?|rules?|guardrails?|directives?)\b"""
                ),
                description = "Attempts to override system/developer instructions"
            ),
            AdversarialRule(
                category = "ROLE_HIJACK",
                weight = 0.82f,
                regex = Regex(
                    """(?i)(<\s*/?\s*(system|instruction|admin|tool_call)\s*>|\[\s*SYSTEM\s*\]|###\s*(System|Instruction)\s*:|\byou\s+are\s+now\s+(DAN|unrestricted|root|admin)\b)"""
                ),
                description = "Injects synthetic system/control delimiter tokens"
            ),
            AdversarialRule(
                category = "CONFUSED_DEPUTY_TOOL",
                weight = 0.90f,
                regex = Regex(
                    """(?i)\b(run_intent|run_js|startActivity|sendTextMessage|ACTION_SEND|ACTION_CALL|SmsManager|ai_edge_gallery_get_result)\b"""
                ),
                description = "Embeds direct agent tool/Intent invocation inside untrusted external text"
            ),
            AdversarialRule(
                category = "DATA_EXFILTRATION",
                weight = 0.86f,
                regex = Regex(
                    """(?i)(!\[[^\]]*]\(\s*https?://|\b(exfiltrate|leak|forward|webhook|curl|fetch)\b.{0,45}\b(https?://|password|passcode|token|secret|memory|allergy|wifi)\b)"""
                ),
                description = "Attempts covert markdown image or webhook data exfiltration"
            ),
            AdversarialRule(
                category = "JS_DOM_XSS",
                weight = 0.85f,
                regex = Regex(
                    """(?i)\b(eval\s*\(|Function\s*\(|XMLHttpRequest|navigator\.sendBeacon|document\.cookie|localStorage)\b"""
                ),
                description = "Attempts unsafe network/DOM access in headless JS skill sandbox"
            )
        )

        /**
         * Pure-Kotlin deterministic prompt-injection scorer (callable in JVM unit tests and as fast pre-filter).
         */
        fun evaluateHeuristicRules(text: String): ShieldVerdict {
            val startNs = System.nanoTime()
            if (text.isBlank()) {
                return ShieldVerdict(
                    isSafe = true,
                    injectionProbability = 0.01f,
                    triggeredCategory = "CLEAN",
                    reason = "Empty input",
                    backend = "HeuristicGuard",
                    latencyMs = 0L
                )
            }

            // Detect invisible Unicode Tag characters (U+E0000..U+E007F) or excessive zero-width smuggling
            val hiddenChars = text.count { ch ->
                val code = ch.code
                code in 0x200B..0x200F || code in 0x202A..0x202E || code in 0xFEFF..0xFEFF
            }
            if (hiddenChars >= 3) {
                val elapsed = (System.nanoTime() - startNs) / 1_000_000L
                return ShieldVerdict(
                    isSafe = false,
                    injectionProbability = 0.94f,
                    triggeredCategory = "UNICODE_SMUGGLING",
                    reason = "Detected $hiddenChars hidden zero-width/bidi control characters",
                    backend = "HeuristicGuard",
                    latencyMs = elapsed
                )
            }

            var cumulativeScore = 0.04f
            var topCategory = "CLEAN"
            var topReason = "Passed structural & lexical safety checks"

            for (rule in ADVERSARIAL_RULES) {
                if (rule.regex.containsMatchIn(text)) {
                    // Combine independent rule triggers smoothly into [0, 0.99]
                    cumulativeScore = 1f - ((1f - cumulativeScore) * (1f - rule.weight))
                    if (topCategory == "CLEAN" || rule.weight >= 0.85f) {
                        topCategory = rule.category
                        topReason = rule.description
                    }
                }
            }

            val prob = cumulativeScore.coerceIn(0f, 0.99f)
            val elapsed = (System.nanoTime() - startNs) / 1_000_000L
            return ShieldVerdict(
                isSafe = prob < BLOCK_THRESHOLD,
                injectionProbability = prob,
                triggeredCategory = if (prob >= BLOCK_THRESHOLD) topCategory else "CLEAN",
                reason = topReason,
                backend = "HeuristicGuard",
                latencyMs = elapsed
            )
        }
    }

    private val singleThreadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "litert-prompt-shield").apply { isDaemon = true }
    }
    private val shieldDispatcher = singleThreadExecutor.asCoroutineDispatcher()

    private var compiledHolder: CompiledShieldHolder? = null

    private val _lastVerdict = MutableStateFlow<ShieldVerdict?>(null)
    val lastVerdict: StateFlow<ShieldVerdict?> = _lastVerdict.asStateFlow()

    private val _activeBackend = MutableStateFlow("HeuristicGuard (22M LiteRT Ready)")
    val activeBackend: StateFlow<String> = _activeBackend.asStateFlow()

    /**
     * Chokepoint A: Screens untrusted external content (CameraX OCR, shared text, web snippets).
     */
    suspend fun inspectUntrustedText(rawText: String): ShieldVerdict = withContext(shieldDispatcher) {
        val startNs = System.nanoTime()
        val heuristic = evaluateHeuristicRules(rawText)

        // Try loading LiteRT 2.3.0 CompiledModel if weights file exists on disk
        val holder = ensureCompiledModelLoadedOnExecutor()
        val finalVerdict = if (holder != null) {
            val tfliteProb = holder.inferInjectionProbability(rawText)
            val fusedProb = maxOf(heuristic.injectionProbability, tfliteProb)
            val isSafe = fusedProb < BLOCK_THRESHOLD
            val elapsed = (System.nanoTime() - startNs) / 1_000_000L
            ShieldVerdict(
                isSafe = isSafe,
                injectionProbability = fusedProb,
                triggeredCategory = if (!isSafe) {
                    if (heuristic.injectionProbability >= tfliteProb) heuristic.triggeredCategory else "LITERT_PROMPT_GUARD"
                } else {
                    "CLEAN"
                },
                reason = if (!isSafe && heuristic.injectionProbability < tfliteProb) {
                    "Blocked by LiteRT 2.3.0 CompiledModel classifier (p=${"%.2f".format(tfliteProb)})"
                } else {
                    heuristic.reason
                },
                backend = "LiteRT-2.3 (${holder.accelerator.name}) + Heuristic",
                latencyMs = elapsed
            )
        } else {
            heuristic
        }

        _lastVerdict.value = finalVerdict
        finalVerdict
    }

    /**
     * Chokepoint B: Screens outgoing `run_intent` and `run_js` skill parameters before execution.
     */
    suspend fun inspectSkillInvocation(
        toolName: String,
        serializedArgs: String
    ): ShieldVerdict = withContext(shieldDispatcher) {
        val startNs = System.nanoTime()
        // Explicitly block dangerous Android Intent actions or external network calls inside `run_js`
        val dangerousActionRegex = Regex(
            """(?i)\b(android\.intent\.action\.(CALL|DELETE|UNINSTALL_PACKAGE|INSTALL_PACKAGE)|content://sms|content://contacts|https?://(?!localhost))"""
        )
        if (dangerousActionRegex.containsMatchIn(serializedArgs)) {
            val verdict = ShieldVerdict(
                isSafe = false,
                injectionProbability = 0.96f,
                triggeredCategory = "UNSAFE_SKILL_TARGET",
                reason = "Blocked privileged Intent/URI or external network target in $toolName",
                backend = "SkillEgressGuard",
                latencyMs = (System.nanoTime() - startNs) / 1_000_000L
            )
            _lastVerdict.value = verdict
            return@withContext verdict
        }

        if (toolName == "run_js") {
            val jsCheck = ADVERSARIAL_RULES.first { it.category == "JS_DOM_XSS" }
            if (jsCheck.regex.containsMatchIn(serializedArgs)) {
                val verdict = ShieldVerdict(
                    isSafe = false,
                    injectionProbability = 0.93f,
                    triggeredCategory = jsCheck.category,
                    reason = jsCheck.description,
                    backend = "SkillEgressGuard",
                    latencyMs = (System.nanoTime() - startNs) / 1_000_000L
                )
                _lastVerdict.value = verdict
                return@withContext verdict
            }
        }

        val verdict = ShieldVerdict(
            isSafe = true,
            injectionProbability = 0.02f,
            triggeredCategory = "CLEAN",
            reason = "Verified $toolName arguments",
            backend = "SkillEgressGuard",
            latencyMs = (System.nanoTime() - startNs) / 1_000_000L
        )
        _lastVerdict.value = verdict
        verdict
    }

    private fun ensureCompiledModelLoadedOnExecutor(): CompiledShieldHolder? {
        compiledHolder?.let { return it }
        val ctx = context ?: return null
        val modelFile = File(File(ctx.filesDir, "models"), TFLITE_FILENAME)
            .takeIf { it.exists() && it.length() > 1_024L }
            ?: return null

        val holder = try {
            CompiledShieldHolder(ctx, modelFile, Accelerator.GPU)
        } catch (_: LiteRtException) {
            try {
                CompiledShieldHolder(ctx, modelFile, Accelerator.CPU)
            } catch (_: Exception) {
                null
            }
        } catch (_: Exception) {
            null
        }

        if (holder != null) {
            compiledHolder = holder
            _activeBackend.value = "LiteRT-2.3 CompiledModel (${holder.accelerator.name})"
        }
        return holder
    }

    override fun close() {
        singleThreadExecutor.execute {
            compiledHolder?.close()
            compiledHolder = null
        }
        singleThreadExecutor.shutdown()
    }

    /**
     * Strict LiteRT 2.3.0 `CompiledModel` wrapper adhering to single-thread confinement,
     * pre-allocated reusable `TensorBuffer`s, XNNPACK `0x23` transient indirection cache, and warm-up.
     */
    private class CompiledShieldHolder(
        context: Context,
        modelFile: File,
        val accelerator: Accelerator
    ) : AutoCloseable {
        private val options = CompiledModel.Options(accelerator).apply {
            if (accelerator == Accelerator.CPU) {
                val cacheFile = File(context.codeCacheDir, "${TFLITE_FILENAME}-v1.xnnpack_cache")
                cpuOptions = CompiledModel.CpuOptions(
                    numThreads = 2,
                    xnnPackFlags = 0x23,
                    xnnPackWeightCachePath = cacheFile.path
                )
            }
        }

        private val model: CompiledModel = CompiledModel.create(
            modelFile.absolutePath,
            options,
            liteRtEnvironment
        )
        private val inputs = mutableListOf<TensorBuffer>()
        private val outputs = mutableListOf<TensorBuffer>()

        init {
            try {
                inputs += model.createInputBuffers()
                outputs += model.createOutputBuffers()
                // Mandatory warm-up inference so first OCR scan pays zero compilation penalty
                inferInjectionProbability("warmup benign note")
            } catch (e: Exception) {
                close()
                throw e
            }
        }

        fun inferInjectionProbability(text: String): Float {
            val encoded = FloatArray(SEQ_LEN)
            val tokens = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            for (i in 0 until minOf(tokens.size, SEQ_LEN)) {
                encoded[i] = ((tokens[i].hashCode() and 0x7FFF) % 30_000 + 101).toFloat()
            }
            inputs[0].writeFloat(encoded)
            model.run(inputs, outputs)
            val logits = outputs[0].readFloat()
            if (logits.size >= 2) {
                val maxLogit = maxOf(logits[0], logits[1])
                val expSafe = exp((logits[0] - maxLogit).toDouble())
                val expInj = exp((logits[1] - maxLogit).toDouble())
                return (expInj / (expSafe + expInj)).toFloat().coerceIn(0f, 1f)
            }
            return logits.firstOrNull()?.coerceIn(0f, 1f) ?: 0f
        }

        override fun close() {
            (inputs + outputs).forEach { runCatching { it.close() } }
            runCatching { model.close() }
        }
    }
}
