package ai.wristmind.app.data.memory

import androidx.appsearch.annotation.Document
import androidx.appsearch.app.AppSearchSchema

/**
 * Android 15 Jetpack AppSearch lexical document mirrored in PlatformStorage.
 *
 * Hosted inside the Android 15 system service (0 MB in-app inverted-index RAM),
 * providing field-weighted BM25F lexical ranking alongside OS search & AppFunctions visibility.
 */
@Document
data class AppSearchMemoryDocument(
    @Document.Namespace
    val namespace: String = NAMESPACE,

    @Document.Id
    val id: String,

    @Document.LongProperty
    val objectBoxId: Long,

    @Document.StringProperty(
        indexingType = AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES,
        tokenizerType = AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN
    )
    val title: String,

    @Document.StringProperty(
        indexingType = AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES,
        tokenizerType = AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN
    )
    val contentText: String,

    @Document.StringProperty(
        indexingType = AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS,
        tokenizerType = AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN
    )
    val modalityType: String,

    @Document.StringProperty(
        indexingType = AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES,
        tokenizerType = AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN
    )
    val tags: String,

    @Document.CreationTimestampMillis
    val creationTimestampMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val NAMESPACE = "wristmind_memories"
    }
}
