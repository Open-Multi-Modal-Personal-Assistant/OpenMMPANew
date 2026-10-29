package ai.wristmind.app.skills

import ai.wristmind.app.WristMindApplication
import ai.wristmind.app.data.memory.IngestionOutcome
import ai.wristmind.app.data.memory.MemoryChunkEntity
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Parameters for searching the user's on-device WristMind hybrid multimodal vault. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class SearchMemoriesParams(
    /** Natural-language search query or exact keyword/code (e.g., "where did I park" or "Wi-Fi"). */
    val query: String,
    /** Maximum number of fused ObjectBox HNSW + AppSearch BM25F results to return (0 uses default 3, max 10). */
    val maxResults: Int = 0
)

/** A single grounded memory item returned from WristMind's hybrid ObjectBox + AppSearch index. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class MemoryAppFunctionItem(
    /** Unique ObjectBox entity identifier of the memory chunk. */
    val id: Long,
    /** Concise title summarizing the memory note or captured snapshot. */
    val title: String,
    /** Grounded textual content or OCR transcript stored in the memory chunk. */
    val content: String,
    /** Source modality of the memory ("TEXT", "VOICE", "IMAGE", or "OCR"). */
    val modality: String,
    /** Reciprocal Rank Fusion score combining 256d EmbeddingGemma 2 cosine distance and BM25F. */
    val rrfScore: Double
)

/** Parameters for saving a new personal memory note into WristMind's on-device vault. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class SaveMemoryNoteParams(
    /** Short descriptive title for the note (e.g., "Locker Combination" or "Flight Gate"). */
    val title: String,
    /** Full text body of the memory to embed with EmbeddingGemma 2 (256d MRL) and index locally. */
    val content: String
)

/** Parameters for reading Full Android Watch (FAW) health vitals via Better Health Tracker / TraX. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ReadWristVitalsParams(
    /** Optional target metric to read: "ALL", "HR" (heart rate BPM), "OXYGEN" (SpO2 %), or "STEP" (null defaults to "ALL"). */
    val metric: String? = null,
    /** If true, triggers an immediate live optical PPG measurement instead of returning the latest cached log. */
    val forceLiveMeasurement: Boolean = false
)

/** Health vitals snapshot returned from Better Health Tracker / TraX or raw FAW watch sensors. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class WristVitalsAppFunctionItem(
    /** Active sensor provider ("BetterHealth/TraX" or "FAW Raw Sensors"). */
    val providerSource: String,
    /** Heart rate in beats per minute (0 if unavailable). */
    val heartRateBpm: Int,
    /** Blood oxygen saturation percentage SpO2 (0 if unavailable). */
    val spo2Percent: Int,
    /** Total steps recorded today (0 if unavailable). */
    val stepsToday: Int,
    /** Active calories burned today in kcal. */
    val caloriesKcal: Int,
    /** Concise human-readable vitals summary line. */
    val summary: String
)

/**
 * Android 16+ (targetSdk 36 / compileSdk 37) Jetpack AppFunctions entry point allowing OS-level
 * AI agents to search/store personal memories and read Full Android Watch (FAW) health vitals
 * directly inside WristMind without opening UI.
 *
 * KSP generates `ai.wristmind.app.skills.WristMindAppFunctionService` and
 * `assets/wristmind_app_function_service.xml`.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "WristMindAppFunctionService",
    appFunctionXmlFileName = "wristmind_app_function_service"
)
abstract class BaseWristMindAppFunctionService : AppFunctionService() {

    private val container by lazy {
        (getApplicationContext() as WristMindApplication).container
    }

    /**
     * Searches the user's on-device WristMind memory vault using hybrid Reciprocal Rank Fusion
     * across ObjectBox 256d EmbeddingGemma 2 vectors and Android AppSearch BM25F.
     *
     * @param params Query string and maximum result count.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun searchMemories(
        params: SearchMemoriesParams
    ): List<MemoryAppFunctionItem> = withContext(Dispatchers.IO) {
        if (params.query.isBlank()) {
            throw AppFunctionInvalidArgumentException("Search query must not be blank.")
        }
        val effectiveLimit = if (params.maxResults <= 0) 3 else params.maxResults.coerceIn(1, 10)
        val response = container.memoryRepository.hybridSearch(
            query = params.query,
            limit = effectiveLimit
        )
        response.results.map { hit ->
            MemoryAppFunctionItem(
                id = hit.chunk.id,
                title = hit.chunk.title,
                content = hit.chunk.contentText,
                modality = hit.chunk.modalityType,
                rrfScore = hit.rrfScore
            )
        }
    }

    /**
     * Saves a personal memory note into WristMind's local ObjectBox + AppSearch vault after
     * screening with the on-device PromptInjectionShield.
     *
     * @param params Note title and content body.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun saveMemoryNote(
        params: SaveMemoryNoteParams
    ): MemoryAppFunctionItem = withContext(Dispatchers.IO) {
        if (params.title.isBlank() && params.content.isBlank()) {
            throw AppFunctionInvalidArgumentException("Either title or content must be non-blank.")
        }
        when (
            val outcome = container.memoryRepository.ingestTextOrOcrMemory(
                title = params.title,
                contentText = params.content,
                modality = MemoryChunkEntity.Modality.TEXT,
                isTrustedSource = false
            )
        ) {
            is IngestionOutcome.Stored -> MemoryAppFunctionItem(
                id = outcome.entity.id,
                title = outcome.entity.title,
                content = outcome.entity.contentText,
                modality = outcome.entity.modalityType,
                rrfScore = 1.0
            )
            is IngestionOutcome.BlockedByPromptShield -> throw AppFunctionInvalidArgumentException(
                "Blocked by WristMind PromptInjectionShield: ${outcome.verdict.reason}"
            )
        }
    }

    /**
     * Reads Full Android Watch (FAW) vitals (heart rate BPM, SpO2 %, steps, calories) via
     * Better Health Tracker / TraX (`com.ailife.betterhealth`) with proprietary FAW sensor fallback.
     *
     * @param params Requested metric and whether to trigger an immediate optical PPG scan.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun readWristVitals(
        params: ReadWristVitalsParams
    ): WristVitalsAppFunctionItem = withContext(Dispatchers.IO) {
        val snap = container.betterHealthBridge.readHealthSnapshot(
            requestedMetric = params.metric?.ifBlank { "ALL" } ?: "ALL",
            forceLiveMeasurement = params.forceLiveMeasurement
        )
        WristVitalsAppFunctionItem(
            providerSource = snap.providerSource,
            heartRateBpm = snap.heartRateBpm ?: 0,
            spo2Percent = snap.spo2Percent ?: 0,
            stepsToday = snap.stepsToday ?: 0,
            caloriesKcal = snap.caloriesKcal ?: 0,
            summary = snap.formatConciseSummary()
        )
    }
}
