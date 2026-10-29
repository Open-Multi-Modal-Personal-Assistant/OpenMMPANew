package ai.wristmind.app.data.memory

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.RemoveByDocumentIdRequest
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.localstorage.LocalStorage
import androidx.appsearch.platformstorage.PlatformStorage
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android 15 Jetpack AppSearch BM25F lexical search manager.
 *
 * Uses OS-hosted [PlatformStorage] on Android 15+ (zero in-app inverted-index memory overhead,
 * shared with system search and AppFunctions) with seamless fallback to [LocalStorage].
 */
class AppSearchLexicalIndex(
    private val context: Context
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var session: AppSearchSession? = null

    var activeBackendName: String = "UNINITIALIZED"
        private set

    suspend fun ensureInitialized(): AppSearchSession = withContext(dispatcher) {
        mutex.withLock {
            session?.let { return@withLock it }

            val openedSession = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    val platformCtx = PlatformStorage.SearchContext.Builder(context, DATABASE_NAME)
                        .build()
                    PlatformStorage.createSearchSessionAsync(platformCtx).get().also {
                        activeBackendName = "Android 15 PlatformStorage (Icing BM25F)"
                    }
                } else {
                    val localCtx = LocalStorage.SearchContext.Builder(context, DATABASE_NAME).build()
                    LocalStorage.createSearchSessionAsync(localCtx).get().also {
                        activeBackendName = "LocalStorage (Icing BM25F)"
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "PlatformStorage unavailable, falling back to LocalStorage", t)
                val localCtx = LocalStorage.SearchContext.Builder(context, DATABASE_NAME).build()
                LocalStorage.createSearchSessionAsync(localCtx).get().also {
                    activeBackendName = "LocalStorage Fallback (Icing BM25F)"
                }
            }

            val schemaRequest = SetSchemaRequest.Builder()
                .addSchemas(AppSearchMemoryDocument.SCHEMA)
                .setForceOverride(true)
                .build()
            openedSession.setSchemaAsync(schemaRequest).get()

            session = openedSession
            openedSession
        }
    }

    suspend fun putMemory(entity: MemoryChunkEntity) = withContext(dispatcher) {
        try {
            val activeSession = ensureInitialized()
            val doc = AppSearchMemoryDocument(
                id = "mem_${entity.id}",
                objectBoxId = entity.id,
                title = entity.title,
                contentText = entity.contentText,
                modalityType = entity.modalityType,
                tags = entity.tagsCsv,
                creationTimestampMillis = entity.timestampMillis
            )
            val request = PutDocumentsRequest.Builder()
                .addGenericDocuments(doc.toGenericDocument())
                .build()
            activeSession.putAsync(request).get()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to index memory ${entity.id} into AppSearch", t)
        }
    }

    suspend fun removeMemory(objectBoxId: Long) = withContext(dispatcher) {
        try {
            val activeSession = ensureInitialized()
            val req = RemoveByDocumentIdRequest.Builder(AppSearchMemoryDocument.NAMESPACE)
                .addIds("mem_$objectBoxId")
                .build()
            activeSession.removeAsync(req).get()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to remove memory $objectBoxId from AppSearch", t)
        }
    }

    suspend fun searchBm25f(
        rawQuery: String,
        modalityFilter: MemoryChunkEntity.Modality? = null,
        maxResults: Int = 12
    ): List<HybridSearchMath.LexicalHit> = withContext(dispatcher) {
        val sanitized = sanitizeAppSearchQuery(rawQuery)
        if (sanitized.isBlank()) return@withContext emptyList()

        try {
            val activeSession = ensureInitialized()
            val specBuilder = SearchSpec.Builder()
                .addFilterNamespaces(AppSearchMemoryDocument.NAMESPACE)
                .addFilterSchemas(AppSearchMemoryDocument.SCHEMA_TYPE)
                .setRankingStrategy(SearchSpec.RANKING_STRATEGY_RELEVANCE_SCORE)
                .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                .setResultCountPerPage(maxResults)
                .setListFilterQueryLanguageEnabled(true)

            val queryExpr = if (modalityFilter != null) {
                "($sanitized) AND modalityType:${modalityFilter.name}"
            } else {
                sanitized
            }

            val hits = mutableListOf<HybridSearchMath.LexicalHit>()
            activeSession.search(queryExpr, specBuilder.build()).use { results ->
                val page = results.nextPageAsync.get()
                for (result in page) {
                    val doc = AppSearchMemoryDocument.fromGenericDocument(result.genericDocument)
                    hits += HybridSearchMath.LexicalHit(
                        objectBoxId = doc.objectBoxId,
                        bm25fScore = result.rankingSignal
                    )
                }
            }
            hits
        } catch (t: Throwable) {
            Log.w(TAG, "BM25F query failed for '$sanitized'", t)
            emptyList()
        }
    }

    fun close() {
        try {
            session?.close()
            session = null
        } finally {
            executor.shutdown()
        }
    }

    private fun sanitizeAppSearchQuery(query: String): String {
        // Strip punctuation that could break AppSearch query expressions and join terms with OR
        // so partial keyword overlaps still receive BM25F term-frequency scoring.
        val tokens = query
            .replace(Regex("[^\\p{L}\\p{N}\\s_-]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.length >= 2 && it.lowercase() !in STOP_WORDS }
        return if (tokens.isEmpty()) "" else tokens.joinToString(" OR ")
    }

    companion object {
        private const val TAG = "AppSearchLexicalIndex"
        private const val DATABASE_NAME = "wristmind_hybrid_bm25_db"
        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "what", "where", "when", "who",
            "how", "did", "was", "are", "you", "can", "my", "is", "in", "on", "to", "of"
        )
    }
}
