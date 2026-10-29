package ai.wristmind.app.edge

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class ModelInstallStatus(
    val spec: EdgeModelSpec,
    val installedFile: File?,
    val isLoaded: Boolean,
    val downloadProgress: Float? = null
)

data class GalleryRuntimeState(
    val selectedSpec: EdgeModelSpec = ModelCatalog.QWEN3_0_6B_INT4,
    val loadedSpec: EdgeModelSpec? = null,
    val isInitializing: Boolean = false,
    val isBusyGenerating: Boolean = false,
    val preferGpuVision: Boolean = false,
    val statuses: List<ModelInstallStatus> = emptyList(),
    val lastError: String? = null
)

/**
 * Switchable AI Edge Gallery manager for `Qwen3-0.6B` (INT4), `FunctionGemma 270M`,
 * `Gemma 3 270M-IT`, and `SmolVLM-256M` / `SmolVLM2-500M` via LiteRT-LM 0.18.0.
 *
 * Follows all LiteRT-LM lifecycle mandates:
 * - Single-thread executor owns `Engine` and `Conversation`.
 * - Closes `Conversation` before `Engine`, calling `cancelProcess()` if busy, each at most once.
 * - Defaults generative text decoding to `Backend.CPU()` (4-thread XNNPACK) to avoid OpenCL leaks.
 * - Sets `maxNumImages = 1` when loading VLM models so `Content.ImageBytes` tensors are processed.
 */
class LiteRtModelGalleryManager(
    private val context: Context,
    private val residencyMonitor: AdaptiveResidencyMonitor,
    private val embeddingEngine: EmbeddingGemmaEngine
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val engineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)
    private val mutex = Mutex()
    private val httpClient = OkHttpClient()

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var activeSystemInstruction: String = DEFAULT_SYSTEM_PROMPT

    private val _state = MutableStateFlow(GalleryRuntimeState())
    val state: StateFlow<GalleryRuntimeState> = _state.asStateFlow()

    fun refreshCatalogStatuses() {
        val loadedId = _state.value.loadedSpec?.id
        val currentMap = _state.value.statuses.associateBy { it.spec.id }
        val updated = ModelCatalog.ALL_MODELS.map { spec ->
            val file = ModelCatalog.resolveInstalledFile(context, spec)
            ModelInstallStatus(
                spec = spec,
                installedFile = file,
                isLoaded = spec.id == loadedId ||
                    (spec.role == EdgeModelRole.EMBEDDING && embeddingEngine.activeModeLabel.startsWith(spec.shortBadge)),
                downloadProgress = currentMap[spec.id]?.downloadProgress
            )
        }
        _state.update { it.copy(statuses = updated) }
    }

    suspend fun selectAndLoadModel(
        spec: EdgeModelSpec,
        systemInstruction: String = activeSystemInstruction
    ): Boolean = withContext(engineDispatcher) {
        if (spec.role == EdgeModelRole.EMBEDDING) {
            val ok = embeddingEngine.tryLoadBestAvailableModel(
                preferredSpec = spec,
                enableGpuForVision = _state.value.preferGpuVision
            )
            refreshCatalogStatuses()
            return@withContext ok
        }

        mutex.withLock {
            _state.update { it.copy(selectedSpec = spec, lastError = null) }
            val file = ModelCatalog.resolveInstalledFile(context, spec)
            if (file == null) {
                refreshCatalogStatuses()
                return@withLock false
            }

            if (_state.value.loadedSpec?.id == spec.id && engine != null) {
                return@withLock true
            }

            // If device memory is in AUTO_SWAP_ON_DEMAND mode, free embedder weights before loading >500MB VLM/LLM
            if (residencyMonitor.refreshNow().residencyMode == ResidencyMode.AUTO_SWAP_ON_DEMAND) {
                embeddingEngine.unloadIdleWeights()
            }

            releaseUnlocked()
            _state.update { it.copy(isInitializing = true) }

            try {
                val visionBackend = when {
                    !spec.supportsVision -> null
                    _state.value.preferGpuVision -> Backend.GPU()
                    else -> Backend.CPU()
                }
                val config = EngineConfig(
                    modelPath = file.absolutePath,
                    backend = Backend.CPU(),
                    visionBackend = visionBackend,
                    maxNumTokens = 1024, // Keep KV-cache compact on 4 GB watch
                    maxNumImages = if (spec.supportsVision) 1 else null,
                    cacheDir = context.cacheDir.absolutePath
                )
                val initialized = Engine(config).also { it.initialize() }
                engine = initialized
                activeSystemInstruction = systemInstruction
                conversation = initialized.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(systemInstruction),
                        samplerConfig = SamplerConfig(topK = 20, topP = 0.92, temperature = 0.35)
                    )
                )
                _state.update {
                    it.copy(
                        loadedSpec = spec,
                        isInitializing = false,
                        lastError = null
                    )
                }
                residencyMonitor.refreshNow()
                refreshCatalogStatuses()
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load ${spec.displayName}", t)
                releaseUnlocked()
                _state.update {
                    it.copy(
                        isInitializing = false,
                        lastError = "Load failed (${spec.shortBadge}): ${t.message}"
                    )
                }
                refreshCatalogStatuses()
                false
            }
        }
    }

    suspend fun resetConversation(systemInstruction: String = activeSystemInstruction) =
        withContext(engineDispatcher) {
            mutex.withLock {
                val activeEngine = engine ?: return@withLock
                while (_state.value.isBusyGenerating) {
                    conversation?.cancelProcess()
                    delay(60)
                }
                conversation?.close()
                activeSystemInstruction = systemInstruction
                conversation = activeEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(systemInstruction),
                        samplerConfig = SamplerConfig(topK = 20, topP = 0.92, temperature = 0.35)
                    )
                )
            }
        }

    fun streamLocalGeneration(
        prompt: String,
        imageBytes: ByteArray? = null
    ): Flow<String> = flow {
        val activeSpec = _state.value.selectedSpec
        val installed = ModelCatalog.resolveInstalledFile(context, activeSpec)
        if (installed != null && _state.value.loadedSpec?.id != activeSpec.id) {
            selectAndLoadModel(activeSpec)
        }

        val activeConversation = conversation
        if (activeConversation == null) {
            // Extractive deterministic synthesis fallback when no .litertlm binary is on disk yet
            emit(extractiveFallbackSynthesis(prompt))
            return@flow
        }

        _state.update { it.copy(isBusyGenerating = true, lastError = null) }
        try {
            val stream = if (imageBytes != null && imageBytes.isNotEmpty() && _state.value.loadedSpec?.supportsVision == true) {
                // Content.ImageBytes must precede Content.Text per LiteRT-LM token sequencing rules
                val multimodal = Contents.of(
                    listOf(
                        Content.ImageBytes(imageBytes),
                        Content.Text(prompt)
                    )
                )
                activeConversation.sendMessageAsync(multimodal)
            } else {
                activeConversation.sendMessageAsync(prompt)
            }

            stream.collect { chunk ->
                emit(chunk.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Local generation error", t)
            _state.update { it.copy(lastError = t.message) }
            emit("\n[Local engine error: ${t.message}]")
        } finally {
            _state.update { it.copy(isBusyGenerating = false) }
        }
    }.flowOn(engineDispatcher)

    suspend fun generateBlocking(
        prompt: String,
        imageBytes: ByteArray? = null
    ): String {
        val sb = StringBuilder()
        streamLocalGeneration(prompt, imageBytes).collect { sb.append(it) }
        return sb.toString().trim()
    }

    suspend fun downloadModelFromHuggingFace(
        spec: EdgeModelSpec,
        hfBearerToken: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetDir = ModelCatalog.modelsDir(context)
        val partFile = File(targetDir, "${spec.fileName}.part")
        val finalFile = File(targetDir, spec.fileName)

        try {
            updateDownloadProgress(spec.id, 0.01f)
            val reqBuilder = Request.Builder().url(spec.downloadUrl)
            if (!hfBearerToken.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer ${hfBearerToken.trim()}")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}: ${response.message} (Gated HF repos require an HF token)")
                }
                val body = response.body
                val totalBytes = body.contentLength().coerceAtLeast(spec.approxDiskMb * 1024L * 1024L)
                var downloaded = 0L

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val pct = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0.01f, 0.99f)
                            updateDownloadProgress(spec.id, pct)
                        }
                    }
                }
            }

            if (finalFile.exists()) finalFile.delete()
            check(partFile.renameTo(finalFile)) { "Failed to finalize ${finalFile.name}" }
            updateDownloadProgress(spec.id, null)
            refreshCatalogStatuses()
            Result.success(finalFile)
        } catch (t: Throwable) {
            partFile.delete()
            updateDownloadProgress(spec.id, null)
            _state.update { it.copy(lastError = "Download ${spec.shortBadge}: ${t.message}") }
            Result.failure(t)
        }
    }

    fun setPreferGpuVision(enabled: Boolean) {
        _state.update { it.copy(preferGpuVision = enabled) }
    }

    suspend fun unloadActiveModel() = withContext(engineDispatcher) {
        mutex.withLock {
            releaseUnlocked()
            residencyMonitor.refreshNow()
            refreshCatalogStatuses()
        }
    }

    private suspend fun releaseUnlocked() {
        while (_state.value.isBusyGenerating) {
            conversation?.cancelProcess()
            delay(80)
        }
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        _state.update { it.copy(loadedSpec = null, isInitializing = false, isBusyGenerating = false) }
    }

    private fun updateDownloadProgress(specId: String, progress: Float?) {
        _state.update { current ->
            current.copy(
                statuses = current.statuses.map { st ->
                    if (st.spec.id == specId) st.copy(downloadProgress = progress) else st
                }
            )
        }
    }

    private fun extractiveFallbackSynthesis(prompt: String): String {
        val contextSection = prompt.substringAfter("GROUNDED_MEMORIES:", "")
            .substringBefore("USER_QUERY:")
            .trim()
        return if (contextSection.isNotEmpty()) {
            val topFact = contextSection.lines().firstOrNull { it.isNotBlank() } ?: contextSection
            "Grounded Memory Match (Offline Extractive Mode — push ${state.value.selectedSpec.fileName} for neural generation):\n$topFact"
        } else {
            "Offline Edge Ready. Push or download ${state.value.selectedSpec.fileName} in the Gallery tab to enable on-device neural generation."
        }
    }

    companion object {
        private const val TAG = "LiteRtModelGallery"
        const val DEFAULT_SYSTEM_PROMPT =
            "You are WristMind, a concise multimodal personal assistant running on a 4GB Android 15 watch. " +
                "Answer strictly from GROUNDED_MEMORIES when answering personal questions, keep replies under 3 short sentences for a micro-display, and invoke tools accurately when requested."
    }
}
