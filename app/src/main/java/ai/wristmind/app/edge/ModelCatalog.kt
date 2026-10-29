package ai.wristmind.app.edge

import android.content.Context
import android.os.Environment
import java.io.File

enum class EdgeModelRole {
    EMBEDDING,
    GENERATIVE_TEXT,
    VISION_LANGUAGE
}

data class EdgeModelSpec(
    val id: String,
    val displayName: String,
    val shortBadge: String,
    val role: EdgeModelRole,
    val fileName: String,
    val huggingFaceRepo: String,
    val downloadUrl: String,
    val approxDiskMb: Int,
    val approxPeakRssMb: Int,
    val supportsVision: Boolean,
    val supportsToolCalling: Boolean,
    val summary: String
)

/**
 * Switchable AI Edge Gallery model catalog tailored to a 4 GB physical RAM Android 15 watch.
 *
 * Resolves `.litertlm` files from app internal `filesDir/models`, app external `files/models`,
 * or `/sdcard/Download` (convenient for `adb push` during hackathon testing).
 */
object ModelCatalog {

    val EMBEDDING_GEMMA2_TEXT_VISION_440M = EdgeModelSpec(
        id = "embeddinggemma-2-text-vision-440m",
        displayName = "EmbeddingGemma 2 Text+Vision (440M)",
        shortBadge = "EG2-440M",
        role = EdgeModelRole.EMBEDDING,
        fileName = "embeddinggemma-2-text-vision-440m.litertlm",
        huggingFaceRepo = "litert-community/embeddinggemma-2-740m-litert-lm",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/embeddinggemma-2-text-vision-440m.litertlm",
        approxDiskMb = 388,
        approxPeakRssMb = 450,
        supportsVision = true,
        supportsToolCalling = false,
        summary = "Unified 270M Text + 170M Vision encoder sliced via MRL to 256d (no FP16)."
    )

    val EMBEDDING_GEMMA2_TEXT_270M = EdgeModelSpec(
        id = "embeddinggemma-2-text-270m",
        displayName = "EmbeddingGemma 2 Text (270M)",
        shortBadge = "EG2-270M",
        role = EdgeModelRole.EMBEDDING,
        fileName = "embeddinggemma-2-text-270m.litertlm",
        huggingFaceRepo = "litert-community/embeddinggemma-2-text-270m-litert-lm",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm",
        approxDiskMb = 175,
        approxPeakRssMb = 310,
        supportsVision = false,
        supportsToolCalling = false,
        summary = "Ultra-light 270M text+code embedder & System-1 centroid router."
    )

    val QWEN3_0_6B_INT4 = EdgeModelSpec(
        id = "qwen3-0.6b-int4",
        displayName = "Qwen3-0.6B (INT4)",
        shortBadge = "Qwen3-0.6B",
        role = EdgeModelRole.GENERATIVE_TEXT,
        fileName = "Qwen3-0.6B.litertlm",
        huggingFaceRepo = "litert-community/Qwen3-0.6B",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
        approxDiskMb = 329,
        approxPeakRssMb = 560,
        supportsVision = false,
        supportsToolCalling = true,
        summary = "All-around 0.6B local chat, RAG synthesis, and multi-tool agent model."
    )

    val FUNCTION_GEMMA_270M = EdgeModelSpec(
        id = "functiongemma-270m",
        displayName = "FunctionGemma 270M",
        shortBadge = "FnGemma-270M",
        role = EdgeModelRole.GENERATIVE_TEXT,
        fileName = "functiongemma-270m-ft-mobile.litertlm",
        huggingFaceRepo = "litert-community/functiongemma-270m-ft-mobile",
        downloadUrl = "https://huggingface.co/litert-community/functiongemma-270m-ft-mobile/resolve/main/functiongemma-270m-ft-mobile.litertlm",
        approxDiskMb = 285,
        approxPeakRssMb = 360,
        supportsVision = false,
        supportsToolCalling = true,
        summary = "130+ tok/s tool-calling specialist for Android native SKILL.md intents."
    )

    val GEMMA3_270M_IT = EdgeModelSpec(
        id = "gemma3-270m-it",
        displayName = "Gemma 3 270M-IT",
        shortBadge = "Gemma3-270M",
        role = EdgeModelRole.GENERATIVE_TEXT,
        fileName = "gemma3-270m-it-q4.litertlm",
        huggingFaceRepo = "litert-community/gemma-3-270m-it",
        downloadUrl = "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q4.litertlm",
        approxDiskMb = 195,
        approxPeakRssMb = 330,
        supportsVision = false,
        supportsToolCalling = true,
        summary = "Lowest-RAM Gemma 3 decoder for grounded ObjectBox RAG compression."
    )

    val SMOLVLM_256M_INSTRUCT = EdgeModelSpec(
        id = "smolvlm-256m-instruct",
        displayName = "SmolVLM-256M-Instruct",
        shortBadge = "SmolVLM-256M",
        role = EdgeModelRole.VISION_LANGUAGE,
        fileName = "smolvlm-256m-instruct.litertlm",
        huggingFaceRepo = "litert-community/SmolVLM-256M-Instruct",
        downloadUrl = "https://huggingface.co/litert-community/SmolVLM-256M-Instruct/resolve/main/smolvlm-256m-instruct.litertlm",
        approxDiskMb = 280,
        approxPeakRssMb = 520,
        supportsVision = true,
        supportsToolCalling = false,
        summary = "Compact offline Vision-Language model co-resident with EG2-440M."
    )

    val SMOLVLM2_500M_INSTRUCT = EdgeModelSpec(
        id = "smolvlm2-500m-instruct",
        displayName = "SmolVLM2-500M-Instruct",
        shortBadge = "SmolVLM2-500M",
        role = EdgeModelRole.VISION_LANGUAGE,
        fileName = "smolvlm2-500m-instruct.litertlm",
        huggingFaceRepo = "litert-community/SmolVLM2-500M-Video-Instruct",
        downloadUrl = "https://huggingface.co/litert-community/SmolVLM2-500M-Video-Instruct/resolve/main/smolvlm2-500m-instruct.litertlm",
        approxDiskMb = 510,
        approxPeakRssMb = 720,
        supportsVision = true,
        supportsToolCalling = false,
        summary = "Higher-detail local VLM for offline watch camera visual Q&A."
    )

    val EMBEDDING_MODELS = listOf(
        EMBEDDING_GEMMA2_TEXT_VISION_440M,
        EMBEDDING_GEMMA2_TEXT_270M
    )

    val GENERATIVE_GALLERY_MODELS = listOf(
        QWEN3_0_6B_INT4,
        FUNCTION_GEMMA_270M,
        GEMMA3_270M_IT,
        SMOLVLM_256M_INSTRUCT,
        SMOLVLM2_500M_INSTRUCT
    )

    val ALL_MODELS = EMBEDDING_MODELS + GENERATIVE_GALLERY_MODELS

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    /**
     * Resolves an installed `.litertlm` file across app internal storage, external app storage,
     * and `/sdcard/Download` so developers can either download in-app or `adb push`.
     */
    fun resolveInstalledFile(context: Context, spec: EdgeModelSpec): File? {
        val candidates = buildList {
            add(File(modelsDir(context), spec.fileName))
            add(File(context.filesDir, spec.fileName))
            context.getExternalFilesDir("models")?.let { add(File(it, spec.fileName)) }
            context.getExternalFilesDir(null)?.let { add(File(it, spec.fileName)) }
            @Suppress("DEPRECATION")
            add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), spec.fileName))
        }
        return candidates.firstOrNull { it.exists() && it.isFile && it.length() > 1_048_576L }
    }
}
