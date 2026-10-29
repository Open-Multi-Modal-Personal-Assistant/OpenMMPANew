package ai.wristmind.app.data.memory

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.VectorDistanceType

/**
 * Primary multimodal personal memory entity stored in ObjectBox 6.
 *
 * Uses a 256-dimensional Matryoshka Representation Learning (MRL) slice of
 * EmbeddingGemma 2 (768d -> 256d + L2 normalization) with HNSW neighborsPerNode = 16L
 * to minimize resident native graph RAM on a 4 GB Android 15 watch.
 */
@Entity
data class MemoryChunkEntity(
    @Id var id: Long = 0,
    @Index var modalityType: String = Modality.TEXT.name,
    var title: String = "none",
    var contentText: String = "",
    @Index var sourceUri: String = "",
    var tagsCsv: String = "",
    var isTrustedSource: Boolean = true,
    @Index var timestampMillis: Long = System.currentTimeMillis(),
    @HnswIndex(
        dimensions = 256L,
        neighborsPerNode = 16,
        distanceType = VectorDistanceType.COSINE
    )
    var embedding: FloatArray? = null
) {
    enum class Modality {
        TEXT,
        IMAGE,
        VOICE,
        OCR
    }
}
