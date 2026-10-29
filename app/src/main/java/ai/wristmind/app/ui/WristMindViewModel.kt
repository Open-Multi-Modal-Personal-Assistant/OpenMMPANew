package ai.wristmind.app.ui

import ai.wristmind.app.WristMindApplication
import ai.wristmind.app.agent.PendingWristConfirmation
import ai.wristmind.app.audio.SpeechUiState
import ai.wristmind.app.cloud.LiveBurstStatus
import ai.wristmind.app.data.memory.HybridMemoryResult
import ai.wristmind.app.data.memory.IngestionOutcome
import ai.wristmind.app.data.memory.MemoryChunkEntity
import ai.wristmind.app.edge.DeviceMemoryTelemetry
import ai.wristmind.app.edge.EdgeModelSpec
import ai.wristmind.app.edge.GalleryRuntimeState
import ai.wristmind.app.edge.ResidencyMode
import ai.wristmind.app.router.SystemOneDecision
import ai.wristmind.app.security.ShieldVerdict
import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WristConversationUiState(
    val queryInput: String = "",
    val streamingAnswer: String = "Tap a quick wrist prompt or hold Push-to-Talk. Works 100% offline via ObjectBox 256d HNSW + Android 15 AppSearch BM25F!",
    val isRunningTurn: Boolean = false,
    val isOnlineMode: Boolean = true,
    val autoSpeakReplies: Boolean = true,
    val executedTierBadge: String = "LOCAL READY",
    val citations: List<HybridMemoryResult> = emptyList(),
    val lastOcrStatus: String = "Point watch camera at a sign/receipt or tap a synthetic OCR test below.",
    val lastCapturedJpeg: ByteArray? = null,
    val hfTokenInput: String = ""
)

class WristMindViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as WristMindApplication).container

    private val _uiState = MutableStateFlow(WristConversationUiState())
    val uiState: StateFlow<WristConversationUiState> = _uiState.asStateFlow()

    val memoryTelemetry: StateFlow<DeviceMemoryTelemetry> = container.residencyMonitor.telemetry
    val galleryState: StateFlow<GalleryRuntimeState> = container.galleryManager.state
    val recentMemories: StateFlow<List<MemoryChunkEntity>> = container.memoryRepository.recentMemories
    val lastDecision: StateFlow<SystemOneDecision?> = container.koogOrchestrator.lastDecision
    val pendingConfirmation: StateFlow<PendingWristConfirmation?> = container.koogOrchestrator.pendingConfirmation
    val lastShieldVerdict: StateFlow<ShieldVerdict?> = container.promptShield.lastVerdict
    val liveBurstStatus: StateFlow<LiveBurstStatus> = container.cloudBridge.liveBurstStatus
    val speechState: StateFlow<SpeechUiState> = container.speechController.state

    fun updateQueryInput(text: String) {
        _uiState.update { it.copy(queryInput = text) }
    }

    fun updateHfToken(token: String) {
        _uiState.update { it.copy(hfTokenInput = token) }
    }

    fun toggleOnlineMode() {
        _uiState.update {
            val next = !it.isOnlineMode
            it.copy(
                isOnlineMode = next,
                executedTierBadge = if (next) "HYBRID ARMED" else "AIRPLANE EDGE"
            )
        }
    }

    fun toggleAutoSpeak() {
        _uiState.update { it.copy(autoSpeakReplies = !it.autoSpeakReplies) }
    }

    fun submitQuery(explicitQuery: String? = null, attachLastSnapshot: Boolean = false) {
        val q = (explicitQuery ?: _uiState.value.queryInput).trim()
        if (q.isEmpty() || _uiState.value.isRunningTurn) return

        _uiState.update {
            it.copy(
                queryInput = q,
                isRunningTurn = true,
                streamingAnswer = "Routing via System-1..."
            )
        }

        viewModelScope.launch {
            val imgBytes = if (attachLastSnapshot) _uiState.value.lastCapturedJpeg else null
            val response = container.koogOrchestrator.runTurn(
                utterance = q,
                isOnline = _uiState.value.isOnlineMode,
                attachedImageBytes = imgBytes,
                onStreamToken = { partial ->
                    _uiState.update { st -> st.copy(streamingAnswer = partial) }
                }
            )

            _uiState.update {
                it.copy(
                    isRunningTurn = false,
                    streamingAnswer = response.answerText,
                    executedTierBadge = response.executedTierLabel,
                    citations = response.citations
                )
            }

            if (_uiState.value.autoSpeakReplies && response.pendingConfirmation == null) {
                container.speechController.speakConciseReply(response.answerText)
            }
        }
    }

    fun confirmPendingSkill() {
        viewModelScope.launch {
            val res = container.koogOrchestrator.confirmPendingWristSkill() ?: return@launch
            _uiState.update {
                it.copy(
                    streamingAnswer = res.answerText,
                    executedTierBadge = res.executedTierLabel
                )
            }
            if (_uiState.value.autoSpeakReplies) {
                container.speechController.speakConciseReply(res.answerText)
            }
        }
    }

    fun dismissPendingSkill() {
        container.koogOrchestrator.dismissPendingConfirmation()
        _uiState.update {
            it.copy(
                streamingAnswer = "Watch skill cancelled.",
                executedTierBadge = "CANCELLED"
            )
        }
    }

    fun togglePushToTalk() {
        if (speechState.value.isListening) {
            container.speechController.stopListening()
        } else {
            container.speechController.startPushToTalk(
                preferOffline = !_uiState.value.isOnlineMode
            ) { recognized ->
                submitQuery(recognized)
            }
        }
    }

    fun captureAndIngestBitmap(bitmap: Bitmap, fallbackLabel: String = "Watch Snapshot") {
        viewModelScope.launch {
            _uiState.update { it.copy(lastOcrStatus = "Running on-device ML Kit OCR + PromptInjectionShield...") }
            val processed = container.cameraOcrManager.processBitmap(bitmap)
            val textBody = processed.extractedOcrText.ifBlank { fallbackLabel }
            val title = textBody.lines().firstOrNull()?.take(36)?.ifBlank { "Watch Snapshot" } ?: "Watch Snapshot"

            val outcome = container.memoryRepository.ingestTextOrOcrMemory(
                title = title,
                contentText = textBody,
                modality = MemoryChunkEntity.Modality.OCR,
                isTrustedSource = false, // Forces Chokepoint A PromptInjectionShield inspection!
                rawImageBytes = processed.jpegBytes
            )

            when (outcome) {
                is IngestionOutcome.Stored -> {
                    _uiState.update {
                        it.copy(
                            lastCapturedJpeg = processed.jpegBytes,
                            executedTierBadge = "VAULT INDEXED",
                            lastOcrStatus = "Indexed '${outcome.entity.title}' into ObjectBox 256d HNSW + AppSearch BM25F!"
                        )
                    }
                }
                is IngestionOutcome.BlockedByPromptShield -> {
                    _uiState.update {
                        it.copy(
                            executedTierBadge = "SHIELD BLOCKED",
                            lastOcrStatus = "BLOCKED (${outcome.verdict.triggeredCategory}, p=${"%.2f".format(outcome.verdict.injectionProbability)}): ${outcome.verdict.reason}"
                        )
                    }
                }
            }
        }
    }

    fun runSyntheticOcrDemo(adversarialAttack: Boolean) {
        val (bmp, syntheticText) = container.cameraOcrManager.createSyntheticSignBitmap(adversarialAttack)
        captureAndIngestBitmap(bmp, fallbackLabel = syntheticText)
    }

    fun toggleLiveBurst() {
        if (liveBurstStatus.value.isStreaming) {
            container.cloudBridge.stopLiveBurst()
        } else {
            container.cloudBridge.startOneFpsLiveBurst(_uiState.value.queryInput)
        }
    }

    fun sendOneFpsSyntheticOrCameraFrame(cameraBitmap: Bitmap?) {
        val bmp = cameraBitmap ?: container.cameraOcrManager.createSyntheticSignBitmap(adversarialAttack = false).first
        container.cloudBridge.submitOneFpsCameraFrame(
            frameBitmap = bmp,
            voicePrompt = _uiState.value.queryInput
        )
    }

    fun selectGalleryModel(spec: EdgeModelSpec) {
        viewModelScope.launch {
            container.galleryManager.selectAndLoadModel(spec)
        }
    }

    fun downloadGalleryModel(spec: EdgeModelSpec) {
        viewModelScope.launch {
            val token = _uiState.value.hfTokenInput.takeIf { it.isNotBlank() }
            val res = container.galleryManager.downloadModelFromHuggingFace(spec, token)
            if (res.isSuccess) {
                container.galleryManager.selectAndLoadModel(spec)
            }
        }
    }

    fun cycleResidencyPolicy() {
        val current = memoryTelemetry.value.residencyMode
        val next = if (current == ResidencyMode.CO_RESIDENT) {
            ResidencyMode.AUTO_SWAP_ON_DEMAND
        } else {
            ResidencyMode.CO_RESIDENT
        }
        container.residencyMonitor.setPinnedResidencyMode(next)
    }

    override fun onCleared() {
        super.onCleared()
        container.speechController.close()
    }
}
