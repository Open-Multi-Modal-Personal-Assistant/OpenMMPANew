package ai.wristmind.app.data.memory

import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.GenericDocument

/**
 * Android 15 Jetpack AppSearch lexical document representation.
 *
 * Provides schema definition and type-safe conversion to/from [GenericDocument]
 * without requiring the legacy Java annotation processor, keeping AGP 9.4 + KSP compilation
 * completely warning-free and reflection-free.
 */
data class AppSearchMemoryDocument(
    val namespace: String = NAMESPACE,
    val id: String,
    val objectBoxId: Long,
    val title: String,
    val contentText: String,
    val modalityType: String,
    val tags: String,
    val creationTimestampMillis: Long = System.currentTimeMillis()
) {
    fun toGenericDocument(): GenericDocument =
        GenericDocument.Builder<GenericDocument.Builder<*>>(namespace, id, SCHEMA_TYPE)
            .setCreationTimestampMillis(creationTimestampMillis)
            .setPropertyLong(PROP_OBJECTBOX_ID, objectBoxId)
            .setPropertyString(PROP_TITLE, title)
            .setPropertyString(PROP_CONTENT_TEXT, contentText)
            .setPropertyString(PROP_MODALITY_TYPE, modalityType)
            .setPropertyString(PROP_TAGS, tags)
            .build()

    companion object {
        const val SCHEMA_TYPE = "AppSearchMemoryDocument"
        const val NAMESPACE = "wristmind_memories"

        const val PROP_OBJECTBOX_ID = "objectBoxId"
        const val PROP_TITLE = "title"
        const val PROP_CONTENT_TEXT = "contentText"
        const val PROP_MODALITY_TYPE = "modalityType"
        const val PROP_TAGS = "tags"

        val SCHEMA: AppSearchSchema = AppSearchSchema.Builder(SCHEMA_TYPE)
            .addProperty(
                AppSearchSchema.LongPropertyConfig.Builder(PROP_OBJECTBOX_ID)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setIndexingType(AppSearchSchema.LongPropertyConfig.INDEXING_TYPE_NONE)
                    .build()
            )
            .addProperty(
                AppSearchSchema.StringPropertyConfig.Builder(PROP_TITLE)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()
            )
            .addProperty(
                AppSearchSchema.StringPropertyConfig.Builder(PROP_CONTENT_TEXT)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()
            )
            .addProperty(
                AppSearchSchema.StringPropertyConfig.Builder(PROP_MODALITY_TYPE)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
                    .build()
            )
            .addProperty(
                AppSearchSchema.StringPropertyConfig.Builder(PROP_TAGS)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()
            )
            .build()

        fun fromGenericDocument(doc: GenericDocument): AppSearchMemoryDocument =
            AppSearchMemoryDocument(
                namespace = doc.namespace,
                id = doc.id,
                objectBoxId = doc.getPropertyLong(PROP_OBJECTBOX_ID),
                title = doc.getPropertyString(PROP_TITLE).orEmpty(),
                contentText = doc.getPropertyString(PROP_CONTENT_TEXT).orEmpty(),
                modalityType = doc.getPropertyString(PROP_MODALITY_TYPE).orEmpty(),
                tags = doc.getPropertyString(PROP_TAGS).orEmpty(),
                creationTimestampMillis = doc.creationTimestampMillis
            )
    }
}
