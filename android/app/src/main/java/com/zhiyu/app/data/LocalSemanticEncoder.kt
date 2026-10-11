package com.zhiyu.app.data

/** Real local model contract. Callers must persist modelIdentity with each index. */
interface LocalSemanticEncoder {
    val modelIdentity: String
    val dimensions: Int
    fun isAvailable(): Boolean
    fun documentChunkCount(text: String): Int
    suspend fun encodeDocument(text: String): FloatArray
    suspend fun encodeQuery(query: String): FloatArray
    suspend fun encodeDocumentChunks(text: String, startChunk: Int = 0, maxChunks: Int = 8): List<SemanticChunk>
    suspend fun encodeQueryChunks(query: String): List<SemanticChunk>
}

data class SemanticChunk(val chunkIndex: Int, val tokenStart: Int, val tokenEndExclusive: Int,
    val textStart: Int, val textEndExclusive: Int, val embedding: FloatArray)

class SemanticEncodingUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)
class SemanticInputTooLong(message: String) : IllegalArgumentException(message)
