package ai.wristmind.app.data.memory

import kotlin.math.sqrt

/**
 * Pure float32/float64 vector & hybrid-ranking primitives for EmbeddingGemma 2 MRL (256d),
 * Reciprocal Rank Fusion (RRF), and Pre-LLM Offline Grounding Gates.
 */
object HybridSearchMath {
    const val MRL_DIMENSIONS = 256
    const val DEFAULT_RRF_K = 60
    const val DEFAULT_MAX_COSINE_DISTANCE = 0.48

    data class VectorHit(
        val objectBoxId: Long,
        /** ObjectBox COSINE distance in [0.0, 2.0] where 0.0 is identical and 1.0 is orthogonal. */
        val cosineDistance: Double
    )

    data class LexicalHit(
        val objectBoxId: Long,
        /** Android 15 AppSearch BM25F relevance score (higher is more relevant). */
        val bm25fScore: Double
    )

    data class FusedHit(
        val objectBoxId: Long,
        val rrfScore: Double,
        val cosineDistance: Double?,
        val bm25fScore: Double?,
        val vectorRank: Int?,
        val lexicalRank: Int?
    )

    /**
     * Formats text using EmbeddingGemma 2's asymmetric task instruction prefixes.
     * Prefixes are strictly applied to text, never to raw image or audio byte buffers.
     */
    fun formatDocumentText(title: String?, body: String): String {
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: "none"
        return "title: $cleanTitle | text: ${body.trim()}"
    }

    fun formatSearchQuery(query: String): String =
        "task: search query | text: ${query.trim()}"

    fun formatClassificationInput(text: String): String =
        "task: classification | text: ${text.trim()}"

    /**
     * Truncates a 768d (or >= 256d) EmbeddingGemma 2 vector to [targetDim] via Matryoshka
     * Representation Learning (MRL), validates against silent IEEE-754 FP16 NaN/Inf overflow,
     * and applies double-accumulated L2 normalization back into float32.
     */
    fun truncateAndNormalizeMrl(
        rawEmbedding: FloatArray,
        targetDim: Int = MRL_DIMENSIONS
    ): FloatArray {
        require(rawEmbedding.size >= targetDim) {
            "Raw embedding dimension (${rawEmbedding.size}) is smaller than MRL target ($targetDim)"
        }

        val sliced = FloatArray(targetDim)
        var sumSquares = 0.0
        for (i in 0 until targetDim) {
            val v = rawEmbedding[i]
            require(v.isFinite()) {
                "Non-finite activation detected at index $i ($v). Ensure FP16 precision is disabled for EmbeddingGemma 2."
            }
            sliced[i] = v
            sumSquares += v.toDouble() * v.toDouble()
        }

        val norm = sqrt(sumSquares)
        if (norm > 1e-12) {
            val invNorm = (1.0 / norm).toFloat()
            for (i in 0 until targetDim) {
                sliced[i] *= invNorm
            }
        }
        return sliced
    }

    /**
     * Computes cosine similarity in [-1.0, 1.0] between two L2-normalized vectors.
     */
    fun cosineSimilarityNormalized(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Dimension mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
        }
        return dot.coerceIn(-1.0, 1.0)
    }

    /**
     * Fuses ObjectBox HNSW dense vector hits with Android 15 AppSearch BM25F lexical hits
     * using weighted Reciprocal Rank Fusion (RRF):
     *   RRF(d) = w_vec / (k + rank_vec(d)) + w_lex / (k + rank_lex(d))
     */
    fun fuseReciprocalRank(
        vectorHits: List<VectorHit>,
        lexicalHits: List<LexicalHit>,
        rrfK: Int = DEFAULT_RRF_K,
        vectorWeight: Double = 0.60,
        lexicalWeight: Double = 0.40,
        limit: Int = 5
    ): List<FusedHit> {
        val sortedVec = vectorHits.sortedBy { it.cosineDistance }
        val sortedLex = lexicalHits.sortedByDescending { it.bm25fScore }

        val vecIndex = sortedVec.mapIndexed { idx, hit -> hit.objectBoxId to (idx + 1 to hit.cosineDistance) }.toMap()
        val lexIndex = sortedLex.mapIndexed { idx, hit -> hit.objectBoxId to (idx + 1 to hit.bm25fScore) }.toMap()

        val allIds = LinkedHashSet<Long>(vecIndex.size + lexIndex.size).apply {
            addAll(vecIndex.keys)
            addAll(lexIndex.keys)
        }

        return allIds.map { id ->
            val vecEntry = vecIndex[id]
            val lexEntry = lexIndex[id]
            val vecContribution = vecEntry?.let { (rank, _) -> vectorWeight / (rrfK + rank) } ?: 0.0
            val lexContribution = lexEntry?.let { (rank, _) -> lexicalWeight / (rrfK + rank) } ?: 0.0

            FusedHit(
                objectBoxId = id,
                rrfScore = vecContribution + lexContribution,
                cosineDistance = vecEntry?.second,
                bm25fScore = lexEntry?.second,
                vectorRank = vecEntry?.first,
                lexicalRank = lexEntry?.first
            )
        }
            .sortedWith(
                compareByDescending<FusedHit> { it.rrfScore }
                    .thenBy { it.cosineDistance ?: Double.MAX_VALUE }
            )
            .take(limit)
    }

    /**
     * Pre-LLM grounding gate preventing compact 270M/600M models from hallucinating offline
     * when no personal memory matches the user query.
     *
     * Passes if either:
     * 1. Dense semantic cosine distance <= [maxCosineDistance], OR
     * 2. Strong BM25F lexical match exists (e.g., exact license plate, receipt ID, part code).
     */
    fun passesGroundingGate(
        topHits: List<FusedHit>,
        maxCosineDistance: Double = DEFAULT_MAX_COSINE_DISTANCE,
        minLexicalScoreOverride: Double = 1.25
    ): Boolean {
        if (topHits.isEmpty()) return false
        val best = topHits.first()
        val semanticPass = best.cosineDistance != null && best.cosineDistance <= maxCosineDistance
        val lexicalPass = best.bm25fScore != null && best.bm25fScore >= minLexicalScoreOverride
        return semanticPass || lexicalPass
    }
}
