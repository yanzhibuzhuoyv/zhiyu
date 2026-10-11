package com.zhiyu.app.data

import com.zhiyu.app.domain.*
import com.zhiyu.app.notifications.ProactiveForeground
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LocalSemanticRetrieval(val originals: List<LocalRecall>, val route: String, val fallbackReason: String?,
    val status: LocalRetrievalStatus = LocalRetrievalStatus.NOT_SEARCHED)
data class LocalSemanticBatch(val encodedChunks: Int, val completedMessages: Int, val reason: String?, val skippedMessages: Int = 0)

/** Semantic first; lexical fallback never claims that a keyword match is an embedding. */
class LocalSemanticService(private val archive: LocalChatDatabase, private val encoder: LocalSemanticEncoder,
    private val threshold: Double = LocalSemanticRetrievalRules.SEMANTIC_ONLY_THRESHOLD,
    private val candidateThreshold:Double = LocalSemanticRetrievalRules.CANDIDATE_THRESHOLD) {
    private val store = LocalSemanticStore(archive)
    private val corrections = LocalSemanticCorrections(archive)
    companion object { private val background = Mutex() }
    init { require(candidateThreshold in 0.0..1.0 && threshold in candidateThreshold..1.0) }
    fun statistics(owner:String,id:String):LocalSemanticStatistics = store.statistics(owner,id,encoder.modelIdentity)
    suspend fun retrieve(owner: String, id: String, query: String, exclude: Set<String> = emptySet()): LocalSemanticRetrieval = withContext(Dispatchers.Default) {
        require(exclude.size <= 100)
        if (query.isBlank() || query.codePoints().noneMatch { Character.isLetterOrDigit(it) })
            return@withContext LocalSemanticRetrieval(emptyList(), "empty_query", null, LocalRetrievalStatus.NOT_SEARCHED)
        if (!encoder.isAvailable()) return@withContext fallback(owner,id,query,exclude,emptyList(),"model_unavailable")
        if (!store.hasVectors(owner,id,encoder.modelIdentity)) return@withContext fallback(owner,id,query,exclude,emptyList(),
            if (store.isComplete(owner,id,encoder.modelIdentity,exclude)) "no_text_sources" else "index_pending")
        try {
            val queryVectors = encoder.encodeQueryChunks(query).map { LocalSemanticVectorRules.normalized(it.embedding,encoder.dimensions) }
            require(queryVectors.size in 1..8)
            val active = store.search(owner,id,encoder.modelIdentity,encoder.dimensions,queryVectors,exclude,minimumScore=candidateThreshold)
            val cold = store.search(owner,id,encoder.modelIdentity,encoder.dimensions,queryVectors,exclude,"cold",minimumScore=candidateThreshold)
            val selected = (active + cold).sortedByDescending { it.rankingScore }.distinctBy { it.messageId }.take(8)
            val materialized = raw(owner,id,selected)
            val semantic = materialized.originals
            val complete = store.isComplete(owner,id,encoder.modelIdentity,exclude)
            val decision = LocalSemanticRetrievalRules.route(semantic.maxOfOrNull { it.score },complete && !materialized.incomplete,threshold,candidateThreshold)
            val corrected = corrections.complete(owner,id,semantic)
            val includedCold = semantic.any { source -> cold.any { it.messageId == source.messageId } }
            if (semantic.isNotEmpty() && decision == LocalSemanticRoute.SEMANTIC && !corrected.unresolved) LocalSemanticRetrieval(corrected.originals,if (includedCold) "semantic_cold" else "semantic",null,LocalRetrievalStatus.OK)
            else fallback(owner,id,query,exclude,semantic,when {
                corrected.unresolved -> "correction_unresolved"
                !complete -> "index_pending"
                materialized.incomplete -> "semantic_context_incomplete"
                else -> "score_below_threshold"
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SemanticInputTooLong) { fallback(owner,id,query,exclude,emptyList(),"query_too_long") }
        catch (_: Exception) { fallback(owner,id,query,exclude,emptyList(),"semantic_failed") }
    }
    private fun fallback(owner:String,id:String,query:String,exclude:Set<String>,semantic:List<LocalRecall>,reason:String):LocalSemanticRetrieval {
        val exact = archive.recall(owner,id,query,exclude)
        val cold = archive.recall(owner,id,query,exclude,state="cold")
        val precision = (exact + cold).sortedByDescending { it.score }.distinctBy { it.messageId }
        val byId = precision.associateBy { it.messageId }
        // A source ID alone does not mean its answer-bearing original span has been supplied.
        val supplemented = semantic.map { source -> byId[source.messageId]?.copy(score=source.score) ?: source }
        val sourceIds = semantic.map { it.messageId }.toSet()
        val additional = precision.filter { it.messageId !in sourceIds }
        // Reserve precision slots: eight uncertain vectors must not hide an exact original.
        val selected = if (semantic.isEmpty()) precision else if (additional.isEmpty()) supplemented else
            supplemented.take(3) + additional.take(5) + supplemented.drop(3) + additional.drop(5)
        val corrected = corrections.complete(owner,id,selected.distinctBy { it.messageId }.take(8))
        return LocalSemanticRetrieval(corrected.originals,if (semantic.isEmpty()) "lexical_fallback" else "semantic_then_lexical",reason,
            LocalRetrievalRules.fallback(reason,corrected.unresolved))
    }
    private data class Materialized(val originals:List<LocalRecall>,val incomplete:Boolean)
    private fun raw(owner:String,id:String,hits:List<LocalSemanticHit>):Materialized {
        var used = 0; var incomplete = false
        val originals = hits.mapNotNull { hit ->
            val width = minOf(8192,hit.end - hit.start)
            if (width <= 0 || used + width > 12000) { incomplete = true; null }
            else archive.semanticRecall(owner,id,hit).also {
                used += it.text.length
                if (it.startOffset != hit.start || it.endOffset != hit.end) incomplete = true
            }
        }
        return Materialized(originals,incomplete)
    }
    /** Resumable bounded windows. Foreground return interrupts background work between encoder calls. */
    suspend fun indexBatch(owner: String, id: String, maxChunks: Int = 12): LocalSemanticBatch = withTimeout(45000) { coroutineScope {
        require(maxChunks in 1..16)
        if (!encoder.isAvailable()) return@coroutineScope LocalSemanticBatch(0,0,"model_unavailable")
        val scope = this
        val watcher = launch { ProactiveForeground.changes.first { it }; scope.cancel(CancellationException("前台优先，暂停后台语义索引")) }
        try { background.withLock { withContext(Dispatchers.IO) { process(owner,id,maxChunks) } } }
        finally { watcher.cancel() }
    } }
    private suspend fun process(owner:String,id:String,maximum:Int):LocalSemanticBatch {
        var encoded = 0; var completed = 0; var skipped = 0; var work = 0
        while (work < maximum) {
            currentCoroutineContext().ensureActive()
            val job = store.nextJob(owner,id,encoder.modelIdentity) ?: return LocalSemanticBatch(encoded,completed,null,skipped)
            work++ // Empty or stale originals also consume the bounded background work budget.
            try {
                val original = archive.recognitionOriginal(owner,id,job.messageId) ?: continue
                if (original.sha256 != job.rawSha || original.revision != job.revision || original.indexState !in setOf("active","cold")) continue
                if (original.text.isBlank()) {
                    currentCoroutineContext().ensureActive()
                    if (store.skipNoText(job,original)) skipped++
                    continue
                }
                val total = job.totalChunks ?: encoder.documentChunkCount(original.text)
                require(total > job.nextChunk)
                val chunks = encoder.encodeDocumentChunks(original.text,job.nextChunk,minOf(4,maximum - work + 1))
                currentCoroutineContext().ensureActive()
                val committed = store.commit(job,original,chunks,total,encoder.dimensions)
                if (committed && original.indexState == "cold") archive.ensureColdIndex(owner,id,job.messageId,job.revision,original.text)
                encoded += chunks.size
                work += chunks.size - 1
                if (committed && job.nextChunk + chunks.size == total) completed++
            } catch (cancelled:CancellationException) { throw cancelled }
            catch (_:Exception) { store.fail(job,"encoding_failed"); return LocalSemanticBatch(encoded,completed,"encoding_failed",skipped) }
        }
        return LocalSemanticBatch(encoded,completed,"batch_limit",skipped)
    }
}
