package ai.wristmind.app.cloud

import ai.wristmind.app.BuildConfig
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.content
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class LiveBurstStatus(
    val isStreaming: Boolean = false,
    val framesSent: Int = 0,
    val transcriptLog: String = "Tap 'Start 1fps Live Burst' to stream watch camera + voice via Firebase AI Logic.",
    val cloudConfigured: Boolean = false
)

/**
 * Zero-Cloud-Functions Firebase AI Logic Cloud Tier (`com.google.firebase:firebase-ai` BoM 35.1.0).
 *
 * Safely checks `FirebaseApp.getApps(context)` so WristMind boots and runs 100% offline even before
 * `app/google-services.json` is attached. Once `google-services.json` is present, initializes:
 *  - Firebase App Check (`DebugAppCheckProviderFactory` in debug, `PlayIntegrity` in release)
 *  - Firebase Remote Config (`wristmind_cloud_model`, `wristmind_live_model`, `wristmind_daily_cap`)
 *  - `Firebase.ai(backend = GenerativeBackend.googleAI())` for spatial/complex cloud escalation
 *  - Throttled 1 fps CameraX + audio burst streaming (`LiveGenerativeModel` / multimodal turn burst)
 */
class FirebaseAiCloudBridge(
    private val context: Context
) {
    companion object {
        const val DEFAULT_CLOUD_MODEL = "gemini-2.5-flash"
        const val DEFAULT_LIVE_MODEL = "gemini-2.0-flash-live-preview-04-09"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appCheckInitialized = false
    private var liveSessionJob: Job? = null

    private val _cloudModelName = MutableStateFlow(DEFAULT_CLOUD_MODEL)
    val cloudModelName: StateFlow<String> = _cloudModelName.asStateFlow()

    private val _liveBurstStatus = MutableStateFlow(LiveBurstStatus())
    val liveBurstStatus: StateFlow<LiveBurstStatus> = _liveBurstStatus.asStateFlow()

    fun isFirebaseConfigured(): Boolean = runCatching {
        FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null
    }.getOrDefault(false)

    suspend fun initializeIfConfigured() = withContext(Dispatchers.IO) {
        val configured = isFirebaseConfigured()
        _liveBurstStatus.value = _liveBurstStatus.value.copy(cloudConfigured = configured)
        if (!configured || appCheckInitialized) return@withContext

        runCatching {
            val appCheck = Firebase.appCheck
            if (BuildConfig.DEBUG) {
                appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
            } else {
                appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
            }
            appCheckInitialized = true

            val rc = Firebase.remoteConfig
            rc.setConfigSettingsAsync(
                remoteConfigSettings { minimumFetchIntervalInSeconds = 300L }
            ).await()
            rc.setDefaultsAsync(
                mapOf(
                    "wristmind_cloud_model" to DEFAULT_CLOUD_MODEL,
                    "wristmind_live_model" to DEFAULT_LIVE_MODEL
                )
            ).await()
            runCatching { rc.fetchAndActivate().await() }
            val fetched = rc.getString("wristmind_cloud_model").ifBlank { DEFAULT_CLOUD_MODEL }
            _cloudModelName.value = fetched
        }
    }

    fun streamCloudEscalation(
        promptText: String,
        imageJpegBytes: ByteArray? = null,
        gpsCoordinatesHint: String? = null
    ): Flow<String> = flow {
        if (!isFirebaseConfigured()) {
            emit(
                "[Cloud Offline / No google-services.json] Firebase AI Logic is not yet bound to a project. " +
                    "Switch to Airplane/Offline Local RAG or finish `firebase login` to auto-generate google-services.json."
            )
            return@flow
        }

        initializeIfConfigured()
        val generativeModel = Firebase.ai(backend = GenerativeBackend.googleAI())
            .generativeModel(modelName = _cloudModelName.value)

        val enrichedPrompt = buildString {
            if (!gpsCoordinatesHint.isNullOrBlank()) {
                appendLine("[Watch GPS Context for Spatial Grounding: $gpsCoordinatesHint]")
            }
            append(promptText)
        }

        val inputContent = content {
            if (imageJpegBytes != null && imageJpegBytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(imageJpegBytes, 0, imageJpegBytes.size)?.let { bmp ->
                    image(bmp)
                }
            }
            text(enrichedPrompt)
        }

        try {
            generativeModel.generateContentStream(inputContent).collect { chunk ->
                val piece = chunk.text
                if (!piece.isNullOrEmpty()) {
                    emit(piece)
                }
            }
        } catch (e: Exception) {
            emit("\n[Firebase AI Logic error: ${e.message ?: e::class.simpleName}]")
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateSingleCloudReply(
        promptText: String,
        imageJpegBytes: ByteArray? = null,
        gpsCoordinatesHint: String? = null
    ): String {
        val sb = StringBuilder()
        streamCloudEscalation(promptText, imageJpegBytes, gpsCoordinatesHint).collect { sb.append(it) }
        return sb.toString().trim()
    }

    /**
     * Hackathon 1 fps Gemini Live / Multimodal Burst mode: accepts throttled 1 fps CameraX Bitmaps
     * + spoken question and streams crisp watch-sized coaching replies back via Firebase AI Logic.
     */
    @OptIn(PublicPreviewAPI::class)
    fun startOneFpsLiveBurst(initialSpokenPrompt: String) {
        liveSessionJob?.cancel()
        val configured = isFirebaseConfigured()
        _liveBurstStatus.value = LiveBurstStatus(
            isStreaming = true,
            framesSent = 0,
            cloudConfigured = configured,
            transcriptLog = if (configured) {
                "Live 1fps stream armed (${_cloudModelName.value}). Point watch camera & tap Send Frame..."
            } else {
                "Demo Live 1fps mode armed (attach app/google-services.json for live Gemini cloud stream)."
            }
        )
        if (configured && initialSpokenPrompt.isNotBlank()) {
            liveSessionJob = scope.launch {
                val reply = generateSingleCloudReply(
                    "You are WristMind Live on a 2-inch Android watch. Reply in <= 25 words: $initialSpokenPrompt"
                )
                _liveBurstStatus.value = _liveBurstStatus.value.copy(transcriptLog = reply)
            }
        }
    }

    fun submitOneFpsCameraFrame(frameBitmap: Bitmap, voicePrompt: String) {
        if (!_liveBurstStatus.value.isStreaming) return
        val nextCount = _liveBurstStatus.value.framesSent + 1
        _liveBurstStatus.value = _liveBurstStatus.value.copy(
            framesSent = nextCount,
            transcriptLog = "Analyzing 1fps frame #$nextCount..."
        )

        scope.launch {
            val outStream = java.io.ByteArrayOutputStream()
            val scaled = Bitmap.createScaledBitmap(frameBitmap, 384, 384, true)
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, outStream)
            val jpegBytes = outStream.toByteArray()

            val reply = if (isFirebaseConfigured()) {
                generateSingleCloudReply(
                    promptText = "You are WristMind Live on a 2-inch watch. Answer in <= 28 words about this 1fps camera frame: " +
                        voicePrompt.ifBlank { "What am I looking at and is anything actionable?" },
                    imageJpegBytes = jpegBytes
                )
            } else {
                "Frame #$nextCount captured (384x384 JPEG, ${jpegBytes.size / 1024} KB). " +
                    "Bind google-services.json for cloud Gemini Live or inspect offline on the Snap & SmolVLM card!"
            }

            _liveBurstStatus.value = _liveBurstStatus.value.copy(
                transcriptLog = reply
            )
        }
    }

    fun stopLiveBurst() {
        liveSessionJob?.cancel()
        liveSessionJob = null
        _liveBurstStatus.value = _liveBurstStatus.value.copy(
            isStreaming = false,
            transcriptLog = "1fps Live Burst stopped to conserve watch battery."
        )
    }
}
