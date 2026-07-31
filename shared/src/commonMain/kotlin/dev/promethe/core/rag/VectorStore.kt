package dev.promethe.core.rag

/**
 * VectorStore — provider-agnostic interface for vector storage and retrieval.
 *
 * Implementations: SqliteVecStore (embedded), ChromaVectorStore, QdrantVectorStore,
 * PineconeVectorStore, MilvusVectorStore.
 */
interface VectorStore {
    /** Store type name for logging/config. */
    val storeName: String

    /** Insert or update a vector with metadata. */
    suspend fun upsert(
        id: String,
        embedding: FloatArray,
        content: String,
        metadata: Map<String, String> = emptyMap(),
    )

    /** Batch upsert for efficient ingestion. */
    suspend fun upsertBatch(items: List<VectorItem>)

    /** Search for the top-K most similar vectors to the query embedding. */
    suspend fun search(
        queryEmbedding: FloatArray,
        topK: Int = 5,
        filter: Map<String, String> = emptyMap(),
    ): List<ScoredResult>

    /** Delete a vector by ID. */
    suspend fun delete(id: String)

    /** Delete all vectors for a given document source. */
    suspend fun deleteBySource(source: String)

    /** Count total stored vectors. */
    suspend fun count(): Int

    /** Test the connection to the vector store. */
    suspend fun testConnection(): Boolean

    /** Clean up resources. */
    suspend fun close()
}

data class VectorItem(
    val id: String,
    val embedding: FloatArray,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
)

data class ScoredResult(
    val id: String,
    val content: String,
    val score: Double,
    val metadata: Map<String, String> = emptyMap(),
)
