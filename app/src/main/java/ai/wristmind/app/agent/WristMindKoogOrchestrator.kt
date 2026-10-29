package ai.wristmind.app.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.DynamicPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorOperation
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import ai.wristmind.app.cloud.FirebaseAiCloudBridge
import ai.wristmind.app.data.memory.HybridMemoryRepository
import ai.wristmind.app.data.memory.HybridMemoryResult
import ai.wristmind.app.edge.LiteRtModelGalleryManager
import ai.wristmind.app.router.AgentRoute
import ai.wristmind.app.router.ExecutionGate
import ai.wristmind.app.router.SystemOneDecision
import ai.wristmind.app.router.SystemOneDecisionEngine
import ai.wristmind.app.security.PromptInjectionShield
import ai.wristmind.app.skills.AndroidSkillExecutor
import ai.wristmind.app.skills.RunIntentArgs
import ai.wristmind.app.skills.RunJsArgs
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

object WristLiteRtProvider : LLMProvider(id = "litert-lm-0.18", display = "LiteRT-LM On-Device")

data class PendingWristConfirmation(
    val originalUtterance: String,
    val decision: SystemOneDecision,
    val proposedIntent: RunIntentArgs?,
    val proposedJs: RunJsArgs?,
    val summaryLabel: String
)

data class AgentTurnResponse(
    val answerText: String,
    val decision: SystemOneDecision,
    val citations: List<HybridMemoryResult> = emptyList(),
    val executedTierLabel: String,
    val pendingConfirmation: PendingWristConfirmation? = null,
    val compressedHistoryTurns: Int = 0
)

/**
 * JetBrains Koog 1.3.0 `DynamicPromptExecutor` bridging WristMind's local LiteRT-LM 0.18.0
 * model gallery (`Qwen3-0.6B`, `FunctionGemma 270M`, `Gemma 3 270M`, `SmolVLM`) and cloud
 * Firebase AI Logic (`Gemini 2.5 Flash`) with per-turn dynamic model resolution.
 */
@OptIn(ExperimentalTime::class)
class WristMindDynamicPromptExecutor(
    private val galleryManager: LiteRtModelGalleryManager,
    private val cloudBridge: FirebaseAiCloudBridge,
    private val isOnlineProvider: () -> Boolean
) : DynamicPromptExecutor() {

    companion object {
        val LOCAL_EDGE_MODEL = LLModel(
            provider = WristLiteRtProvider,
            id = "litert-edge-active",
            contextLength = 2_048L,
            maxOutputTokens = 256L
        )

        val FIREBASE_CLOUD_MODEL = LLModel(
            provider = LLMProvider.Google,
            id = FirebaseAiCloudBridge.DEFAULT_CLOUD_MODEL,
            contextLength = 32_768L,
            maxOutputTokens = 512L
        )
    }

    @Volatile
    var attachedImageBytesForTurn: ByteArray? = null

    @Volatile
    var gpsHintForTurn: String? = null

    override suspend fun resolveModel(
        model: LLModel,
        promptExecutorOperation: PromptExecutorOperation
    ): ResolvedModel {
        val effective = if (
            model.provider == LLMProvider.Google &&
            (!isOnlineProvider() || !cloudBridge.isFirebaseConfigured())
        ) {
            LOCAL_EDGE_MODEL
        } else {
            model
        }
        return ResolvedModel(effectiveModel = effective)
    }

    override suspend fun execute(
        prompt: Prompt,
        resolvedModel: ResolvedModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        val sb = StringBuilder()
        executeStreaming(prompt, resolvedModel, tools).collect { frame ->
            if (frame is StreamFrame.TextDelta) {
                sb.append(frame.text)
            }
        }
        val finalContent = sb.toString().trim()
        return Message.Assistant(
            content = finalContent,
            metaInfo = ResponseMetaInfo.create(
                clock = KoogClock.System,
                modelId = resolvedModel.effectiveModel.id
            )
        )
    }

    override fun executeStreaming(
        prompt: Prompt,
        resolvedModel: ResolvedModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> = flow {
        val flattenedPrompt = renderCompactKoogPrompt(prompt)
        val imageBytes = attachedImageBytesForTurn

        if (resolvedModel.effectiveModel.provider == LLMProvider.Google) {
            cloudBridge.streamCloudEscalation(
                promptText = flattenedPrompt,
                imageJpegBytes = imageBytes,
                gpsCoordinatesHint = gpsHintForTurn
            ).collect { chunk ->
                emit(StreamFrame.TextDelta(text = chunk))
            }
        } else {
            val selectedSpec = galleryManager.state.value.selectedSpec
            val isInstalled = galleryManager.state.value.statuses
                .firstOrNull { it.spec.id == selectedSpec.id }
                ?.installedFile != null

            if (isInstalled || galleryManager.state.value.loadedSpec != null) {
                galleryManager.streamLocalGeneration(
                    prompt = flattenedPrompt,
                    imageBytes = imageBytes
                ).collect { chunk ->
                    emit(StreamFrame.TextDelta(text = chunk))
                }
            } else {
                val synthesized = synthesizeExtractiveOfflineFallback(prompt)
                emit(StreamFrame.TextDelta(text = synthesized))
            }
        }
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        resolvedModel: ResolvedModel,
        tools: List<ToolDescriptor>
    ): LLMChoice = listOf(execute(prompt, resolvedModel, tools))

    override suspend fun moderate(
        prompt: Prompt,
        model: ResolvedModel
    ): ModerationResult {
        throw UnsupportedOperationException("WristMind uses on-device LiteRT 2.3 PromptInjectionShield for moderation")
    }

    override fun close() = Unit

    private fun renderCompactKoogPrompt(prompt: Prompt): String = buildString {
        for (msg in prompt.messages) {
            when (msg.role) {
                Message.Role.System -> appendLine("[SYSTEM]\n${msg.textContent()}\n")
                Message.Role.User -> appendLine("[USER]\n${msg.textContent()}\n")
                Message.Role.Assistant -> appendLine("[ASSISTANT]\n${msg.textContent()}\n")
            }
        }
    }.trim()

    private fun synthesizeExtractiveOfflineFallback(prompt: Prompt): String {
        val activeName = galleryManager.state.value.selectedSpec.shortBadge
        val lastUserText = prompt.messages.lastOrNull { it.role == Message.Role.User }
            ?.textContent()
            .orEmpty()
        val vaultSection = lastUserText.substringAfter("GROUNDED_VAULT_CONTEXT:", "").trim()
        if (vaultSection.isNotBlank()) {
            val topFact = vaultSection.lines().firstOrNull { it.startsWith("- [") }
                ?.removePrefix("- ")
                .orEmpty()
            return "$topFact (Offline extractive recall — pull $activeName in Gallery for neural generation)."
        }
        return "Local model $activeName is not downloaded yet. Open the Gallery card to pull .litertlm weights or query your seeded Hybrid Vault!"
    }
}

class WristMindKoogOrchestrator(
    private val memoryRepository: HybridMemoryRepository,
    private val systemOneRouter: SystemOneDecisionEngine,
    private val galleryManager: LiteRtModelGalleryManager,
    private val cloudBridge: FirebaseAiCloudBridge,
    private val skillExecutor: AndroidSkillExecutor,
    private val promptShield: PromptInjectionShield
) {
    companion object {
        private const val MAX_RETAINED_TURNS_BEFORE_COMPRESSION = 4
    }

    private data class TurnRecord(val user: String, val assistant: String)

    private val recentTurns = ArrayDeque<TurnRecord>()
    private var rollingSummary: String = ""

    @Volatile
    private var isDeviceOnline: Boolean = true

    private val dynamicExecutor = WristMindDynamicPromptExecutor(
        galleryManager = galleryManager,
        cloudBridge = cloudBridge,
        isOnlineProvider = { isDeviceOnline }
    )

    private val _pendingConfirmation = MutableStateFlow<PendingWristConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingWristConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _lastDecision = MutableStateFlow<SystemOneDecision?>(null)
    val lastDecision: StateFlow<SystemOneDecision?> = _lastDecision.asStateFlow()

    suspend fun runTurn(
        utterance: String,
        isOnline: Boolean,
        attachedImageBytes: ByteArray? = null,
        gpsCoordinatesHint: String? = null,
        onStreamToken: (String) -> Unit = {}
    ): AgentTurnResponse {
        isDeviceOnline = isOnline
        _pendingConfirmation.value = null

        val decision = systemOneRouter.evaluate(
            utterance = utterance,
            isOnline = isOnline,
            hasAttachedImage = attachedImageBytes != null
        )
        _lastDecision.value = decision

        if (!decision.safetyNoul.value) {
            val verdict = promptShield.inspectUntrustedText(utterance)
            val msg = "Blocked by PromptInjectionShield (${verdict.triggeredCategory}, p=${"%.2f".format(verdict.injectionProbability)}): ${verdict.reason}"
            onStreamToken(msg)
            return AgentTurnResponse(
                answerText = msg,
                decision = decision,
                executedTierLabel = "SHIELD BLOCKED"
            )
        }

        if (
            decision.routeChoice.selected == AgentRoute.ANDROID_DEVICE_SKILL &&
            decision.gate == ExecutionGate.CONFIRM_ON_WRIST
        ) {
            val (intentArgs, jsArgs) = skillExecutor.inferDirectSkillFromUtterance(utterance)
            val summary = intentArgs?.let { "${it.action.substringAfterLast('.')} (${it.hour ?: it.lengthSeconds ?: "sensors"})" }
                ?: jsArgs?.let { "JS: ${it.expression}" }
                ?: utterance
            val pending = PendingWristConfirmation(
                originalUtterance = utterance,
                decision = decision,
                proposedIntent = intentArgs,
                proposedJs = jsArgs,
                summaryLabel = summary
            )
            _pendingConfirmation.value = pending
            val confirmPrompt = "Confirm watch action ($summary)? Confidence P=${"%.2f".format(decision.routeChoice.maxProbability)} is in [0.50, 0.80)."
            onStreamToken(confirmPrompt)
            return AgentTurnResponse(
                answerText = confirmPrompt,
                decision = decision,
                executedTierLabel = "TAP TO CONFIRM",
                pendingConfirmation = pending
            )
        }

        if (decision.routeChoice.selected == AgentRoute.ANDROID_DEVICE_SKILL) {
            val (intentArgs, jsArgs) = skillExecutor.inferDirectSkillFromUtterance(utterance)
            val result = when {
                jsArgs != null -> skillExecutor.executeRunJs(jsArgs)
                intentArgs != null -> skillExecutor.executeRunIntent(intentArgs)
                else -> skillExecutor.executeRunIntent(RunIntentArgs("ai.wristmind.action.READ_SENSORS"))
            }
            recordAndCompressHistory(utterance, result.summary)
            onStreamToken(result.summary)
            return AgentTurnResponse(
                answerText = result.summary,
                decision = decision,
                executedTierLabel = "LOCAL SKILL (${result.skillName})",
                compressedHistoryTurns = recentTurns.size
            )
        }

        val hybridSearch = memoryRepository.hybridSearch(query = utterance, limit = 3)

        if (
            decision.gate == ExecutionGate.AUTO_EXECUTE_LOCAL &&
            decision.routeChoice.selected == AgentRoute.LOCAL_MEMORY_RAG &&
            !hybridSearch.passedGroundingGate &&
            attachedImageBytes == null
        ) {
            val deterministicMiss = "No grounded match found in your local ObjectBox + AppSearch vault while offline. " +
                "(Try asking about your parked EV, hotel Wi-Fi, allergies, or connect online for Firebase AI Logic)."
            onStreamToken(deterministicMiss)
            return AgentTurnResponse(
                answerText = deterministicMiss,
                decision = decision,
                citations = hybridSearch.results,
                executedTierLabel = "GROUNDING GATE (0 TOKENS)"
            )
        }

        val skillsXml = skillExecutor.cachedSkillsXmlPrompt
        val targetModel = if (decision.gate == ExecutionGate.ESCALATE_TO_FIREBASE_CLOUD) {
            WristMindDynamicPromptExecutor.FIREBASE_CLOUD_MODEL
        } else {
            WristMindDynamicPromptExecutor.LOCAL_EDGE_MODEL
        }

        val koogPrompt = buildKoogPrompt(
            utterance = utterance,
            citations = hybridSearch.results.takeIf { hybridSearch.passedGroundingGate }.orEmpty(),
            skillsXml = skillsXml
        )

        dynamicExecutor.attachedImageBytesForTurn = attachedImageBytes
        dynamicExecutor.gpsHintForTurn = gpsCoordinatesHint

        val resolved = dynamicExecutor.resolveModel(targetModel, PromptExecutorOperation.Streaming)
        val answerBuilder = StringBuilder()
        dynamicExecutor.executeStreaming(koogPrompt, resolved, emptyList()).collect { frame ->
            if (frame is StreamFrame.TextDelta) {
                answerBuilder.append(frame.text)
                onStreamToken(answerBuilder.toString())
            }
        }

        dynamicExecutor.attachedImageBytesForTurn = null
        dynamicExecutor.gpsHintForTurn = null

        val finalAnswer = answerBuilder.toString().trim()
        recordAndCompressHistory(utterance, finalAnswer)

        val tierLabel = if (resolved.effectiveModel.provider == LLMProvider.Google) {
            "CLOUD (${cloudBridge.cloudModelName.value})"
        } else {
            "LOCAL (${galleryManager.state.value.selectedSpec.shortBadge})"
        }

        return AgentTurnResponse(
            answerText = finalAnswer,
            decision = decision,
            citations = hybridSearch.results,
            executedTierLabel = tierLabel,
            compressedHistoryTurns = recentTurns.size
        )
    }

    suspend fun confirmPendingWristSkill(): AgentTurnResponse? {
        val pending = _pendingConfirmation.value ?: return null
        _pendingConfirmation.value = null
        val result = when {
            pending.proposedJs != null -> skillExecutor.executeRunJs(pending.proposedJs)
            pending.proposedIntent != null -> skillExecutor.executeRunIntent(pending.proposedIntent)
            else -> skillExecutor.executeRunIntent(RunIntentArgs("ai.wristmind.action.READ_SENSORS"))
        }
        recordAndCompressHistory(pending.originalUtterance, result.summary)
        return AgentTurnResponse(
            answerText = result.summary,
            decision = pending.decision,
            executedTierLabel = "CONFIRMED SKILL (${result.skillName})",
            compressedHistoryTurns = recentTurns.size
        )
    }

    fun dismissPendingConfirmation() {
        _pendingConfirmation.value = null
    }

    private fun buildKoogPrompt(
        utterance: String,
        citations: List<HybridMemoryResult>,
        skillsXml: String
    ): Prompt = prompt("wristmind-turn") {
        system(
            buildString {
                appendLine("You are WristMind, an edge-first multimodal assistant on a 2-inch Android 15 watch (4GB RAM).")
                appendLine("Keep answers ultra-concise (<= 35 words) and strictly grounded in GROUNDED_VAULT_CONTEXT when present.")
                if (rollingSummary.isNotBlank()) {
                    appendLine("COMPRESSED_PRIOR_CONTEXT: $rollingSummary")
                }
                if (skillsXml.isNotBlank()) {
                    appendLine(skillsXml)
                }
            }.trim()
        )

        for (turn in recentTurns) {
            user(turn.user)
            assistant(turn.assistant)
        }

        user(
            buildString {
                if (citations.isNotEmpty()) {
                    appendLine("GROUNDED_VAULT_CONTEXT:")
                    for (hit in citations) {
                        appendLine("- [${hit.chunk.modalityType} | ${hit.chunk.title}]: ${hit.chunk.contentText}")
                    }
                    appendLine()
                }
                append("QUESTION: $utterance")
            }
        )
    }

    private fun recordAndCompressHistory(userText: String, assistantText: String) {
        recentTurns.addLast(TurnRecord(userText.take(160), assistantText.take(200)))
        while (recentTurns.size > MAX_RETAINED_TURNS_BEFORE_COMPRESSION) {
            val evicted = recentTurns.removeFirst()
            val snippet = "${evicted.user.take(48)} -> ${evicted.assistant.take(64)}"
            rollingSummary = if (rollingSummary.isBlank()) {
                snippet
            } else {
                "$rollingSummary | $snippet".takeLast(320)
            }
        }
    }
}
