package ai.wristmind.app.data.memory

import ai.wristmind.app.edge.EmbeddingGemmaEngine
import ai.wristmind.app.security.PromptInjectionShield
import ai.wristmind.app.security.ShieldVerdict
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.query.QueryCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class HybridMemoryResult(
    val chunk: MemoryChunkEntity,
    val rrfScore: Double,
    val cosineDistance: Double?,
    val bm25fScore: Double?,
    val vectorRank: Int?,
    val lexicalRank: Int?
)

data class HybridSearchResponse(
    val query: String,
    val results: List<HybridMemoryResult>,
    val passedGroundingGate: Boolean,
    val latencyMs: Long,
    val embedderBackend: String,
    val lexicalBackend: String
)

sealed interface IngestionOutcome {
    data class Stored(val entity: MemoryChunkEntity) : IngestionOutcome
    data class BlockedByPromptShield(val verdict: ShieldVerdict) : IngestionOutcome
}

/**
 * Coordinates ObjectBox 6 HNSW (256d MRL EmbeddingGemma 2 vectors) with Android 15
 * AppSearch PlatformStorage (BM25F) via Reciprocal Rank Fusion (RRF), gated on ingress
 * by the on-device [PromptInjectionShield].
 */
class HybridMemoryRepository(
    boxStore: BoxStore,
    private val appSearchIndex: AppSearchLexicalIndex,
    private val embeddingEngine: EmbeddingGemmaEngine,
    private val promptShield: PromptInjectionShield
) {
    private val box: Box<MemoryChunkEntity> = boxStore.boxFor(MemoryChunkEntity::class.java)

    private val _recentMemories = MutableStateFlow<List<MemoryChunkEntity>>(emptyList())
    val recentMemories: StateFlow<List<MemoryChunkEntity>> = _recentMemories.asStateFlow()

    suspend fun initializeAndSeedDemoVault() = withContext(Dispatchers.IO) {
        appSearchIndex.ensureInitialized()
        if (box.isEmpty) {
            seedHackathonDemoMemories()
        }
        refreshRecentMemories()
    }

    suspend fun ingestTextOrOcrMemory(
        title: String,
        contentText: String,
        modality: MemoryChunkEntity.Modality = MemoryChunkEntity.Modality.TEXT,
        sourceUri: String = "",
        tagsCsv: String = "",
        isTrustedSource: Boolean = modality != MemoryChunkEntity.Modality.OCR,
        rawImageBytes: ByteArray? = null
    ): IngestionOutcome = withContext(Dispatchers.IO) {
        if (!isTrustedSource) {
            val verdict = promptShield.inspectUntrustedText("$title\n$contentText")
            if (!verdict.isSafe) {
                return@withContext IngestionOutcome.BlockedByPromptShield(verdict)
            }
        }

        val vector256 = if (rawImageBytes != null && rawImageBytes.isNotEmpty() && contentText.isBlank()) {
            embeddingEngine.embedImage256(rawImageBytes)
        } else {
            val formattedDoc = HybridSearchMath.formatDocumentText(title, contentText)
            val textVec = embeddingEngine.embedRawText256(formattedDoc)
            if (rawImageBytes != null && rawImageBytes.isNotEmpty()) {
                val imgVec = embeddingEngine.embedImage256(rawImageBytes)
                blendAndNormalize(textVec, imgVec)
            } else {
                textVec
            }
        }

        val entity = MemoryChunkEntity(
            modalityType = modality.name,
            title = title.ifBlank { "none" },
            contentText = contentText.take(4_000),
            sourceUri = sourceUri,
            tagsCsv = tagsCsv,
            isTrustedSource = isTrustedSource,
            timestampMillis = System.currentTimeMillis(),
            embedding = vector256
        )

        box.put(entity)
        appSearchIndex.putMemory(entity)
        refreshRecentMemories()
        IngestionOutcome.Stored(entity)
    }

    suspend fun hybridSearch(
        query: String,
        modalityFilter: MemoryChunkEntity.Modality? = null,
        sinceTimestampMillis: Long? = null,
        limit: Int = 4
    ): HybridSearchResponse = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()

        val queryVec = embeddingEngine.embedRawText256(HybridSearchMath.formatSearchQuery(query))
        val candidatePool = (limit * 4).coerceAtLeast(16)

        var condition: QueryCondition<MemoryChunkEntity> =
            MemoryChunkEntity_.embedding.nearestNeighbors(queryVec, candidatePool)
        if (modalityFilter != null) {
            condition = condition.and(MemoryChunkEntity_.modalityType.equal(modalityFilter.name))
        }
        if (sinceTimestampMillis != null) {
            condition = condition.and(MemoryChunkEntity_.timestampMillis.greater(sinceTimestampMillis))
        }

        val obxResults = box.query(condition).build().use { it.findWithScores() }
        val vectorHits = obxResults.map { scored ->
            HybridSearchMath.VectorHit(
                objectBoxId = scored.get().id,
                cosineDistance = scored.score
            )
        }

        val lexicalHits = appSearchIndex.searchBm25f(
            rawQuery = query,
            modalityFilter = modalityFilter,
            maxResults = candidatePool
        )

        val fused = HybridSearchMath.fuseReciprocalRank(
            vectorHits = vectorHits,
            lexicalHits = lexicalHits,
            limit = limit
        )
        val passedGate = HybridSearchMath.passesGroundingGate(fused)

        val entitiesById = box.getMap(fused.map { it.objectBoxId })
        val hydrated = fused.mapNotNull { hit ->
            val chunk = entitiesById[hit.objectBoxId] ?: return@mapNotNull null
            HybridMemoryResult(
                chunk = chunk,
                rrfScore = hit.rrfScore,
                cosineDistance = hit.cosineDistance,
                bm25fScore = hit.bm25fScore,
                vectorRank = hit.vectorRank,
                lexicalRank = hit.lexicalRank
            )
        }

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        HybridSearchResponse(
            query = query,
            results = hydrated,
            passedGroundingGate = passedGate,
            latencyMs = elapsedMs,
            embedderBackend = embeddingEngine.activeModeLabel,
            lexicalBackend = appSearchIndex.activeBackendName
        )
    }

    suspend fun deleteMemory(id: Long) = withContext(Dispatchers.IO) {
        box.remove(id)
        appSearchIndex.removeMemory(id)
        refreshRecentMemories()
    }

    fun count(): Long = box.count()

    private fun refreshRecentMemories() {
        val latest = box.query()
            .orderDesc(MemoryChunkEntity_.timestampMillis)
            .build()
            .use { it.find(0, 25) }
        _recentMemories.value = latest
    }

    private fun blendAndNormalize(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(HybridSearchMath.MRL_DIMENSIONS)
        for (i in out.indices) {
            out[i] = (a[i] * 0.55f) + (b[i] * 0.45f)
        }
        return HybridSearchMath.truncateAndNormalizeMrl(out, HybridSearchMath.MRL_DIMENSIONS)
    }

    private suspend fun seedHackathonDemoMemories() {
        val samples = listOf(
            Triple(
                "Garage Parking Spot",
                "Parked rental EV (plate 8KZM419) on Level P3, Pillar Green-14 near the North elevator.",
                MemoryChunkEntity.Modality.IMAGE to "parking,car,garage,plate"
            ),
            Triple(
                "Hotel Wi-Fi & Room Door Code",
                "Grand Hyatt Room 1408. Guest Wi-Fi passcode: EdgeWrist2026! Late checkout confirmed for 2:00 PM.",
                MemoryChunkEntity.Modality.OCR to "hotel,wifi,room,code"
            ),
            Triple(
                "Medication & Allergy Profile",
                "Allergic to penicillin. Taking Vitamin D3 2000 IU each morning after breakfast.",
                MemoryChunkEntity.Modality.VOICE to "health,allergy,medication"
            ),
            Triple(
                "Hackathon Pitch Checklist",
                "Demo airplane-mode instant hybrid recall on sw426dp watch, live PSS RAM pill, Prompt-Guard OCR defense, and Gemini Live 1fps camera burst.",
                MemoryChunkEntity.Modality.TEXT to "hackathon,demo,pitch"
            )
        )
        for ((title, body, meta) in samples) {
            ingestTextOrOcrMemory(
                title = title,
                contentText = body,
                modality = meta.first,
                tagsCsv = meta.second,
                isTrustedSource = true
            )
        }
    }
}
