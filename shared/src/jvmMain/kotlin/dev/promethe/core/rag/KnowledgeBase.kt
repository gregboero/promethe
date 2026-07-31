package dev.promethe.core.rag

import java.util.UUID

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/** Chunking strategy for document ingestion. */
enum class ChunkingStrategy {
    /** Fixed-size chunks with overlap (fast, no embedding cost during chunking). */
    FIXED,

    /** Semantic boundaries using embedding similarity drops (higher quality, slower). */
    SEMANTIC,
}

/**
 * KnowledgeBase — high-level façade for the RAG system.
 *
 * Orchestrates: ingest(file) → chunk → embed → store
 *               search(query) → embed query → vector search → return chunks
 *
 * Supports:
 * - FIXED chunking (fast, token-based) or SEMANTIC chunking (embedding-based boundaries)
 * - Optional cross-encoder re-ranking for improved precision
 * - Optional semantic caching for reduced latency/cost
 *
 * This is the single entry point that the rest of Prométhé uses.
 */
class KnowledgeBase(
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore,
    private val chunker: DocumentChunker = DocumentChunker(),
    private val semanticChunker: SemanticChunker? = null,
    private val chunkingStrategy: ChunkingStrategy = ChunkingStrategy.FIXED,
    private val ftsSearcher: HybridSearchEngine.FtsSearcher? = (vectorStore as? HybridSearchEngine.FtsSearcher),
    private val reRanker: ReRanker? = null,
    private val vectorWeight: Double = 0.6,
    private val ftsWeight: Double = 0.4,
    private val semanticCache: SemanticCache? = null,
) {
    /** Hybrid search engine — combines vector + FTS5 with RRF fusion + optional re-ranking. */
    private val hybridEngine = HybridSearchEngine(
        embeddingService = embeddingService,
        vectorStore = vectorStore,
        ftsSearcher = ftsSearcher,
        reRanker = reRanker,
        vectorWeight = vectorWeight,
        ftsWeight = ftsWeight,
    )

    // In-memory document registry (source → metadata)
    private val documents = mutableMapOf<String, DocumentInfo>()

    data class DocumentInfo(
        val id: String,
        val filename: String,
        val chunkCount: Int,
        val totalTokens: Int,
        val ingestedAt: Long,
    )

    /**
     * Ingest a document: chunk → embed → store.
     * Returns the document ID and number of chunks created.
     */
    suspend fun ingest(
        content: String,
        filename: String,
        metadata: Map<String, String> = emptyMap(),
    ): DocumentInfo {
        val docId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()

        logger.info { "Ingesting '$filename' (${content.length} chars)..." }

        // 1. Chunk the document (fixed-size or semantic)
        val chunks = when (chunkingStrategy) {
            ChunkingStrategy.SEMANTIC -> {
                val sc = semanticChunker
                    ?: SemanticChunker(embeddingService) // Create on-the-fly if not provided
                sc.chunk(content, filename, metadata + ("doc_id" to docId))
            }

            ChunkingStrategy.FIXED -> {
                chunker.chunk(content, filename, metadata + ("doc_id" to docId))
            }
        }

        if (chunks.isEmpty()) {
            logger.warn { "No chunks generated for '$filename'" }
            return DocumentInfo(docId, filename, 0, 0, startTime)
        }

        // 2. Embed all chunks (batch for efficiency)
        val texts = chunks.map { it.content }
        val embeddings = embeddingService.embedBatch(texts)

        // 3. Store in vector store
        val items = chunks.mapIndexed { i, chunk ->
            VectorItem(
                id = "${docId}_chunk_${chunk.index}",
                embedding = embeddings[i],
                content = chunk.content,
                metadata = chunk.metadata + ("doc_id" to docId),
            )
        }
        vectorStore.upsertBatch(items)

        // 4. Register document
        val totalTokens = chunks.sumOf { it.estimatedTokens }
        val info = DocumentInfo(
            id = docId,
            filename = filename,
            chunkCount = chunks.size,
            totalTokens = totalTokens,
            ingestedAt = startTime,
        )
        documents[docId] = info

        val elapsed = System.currentTimeMillis() - startTime
        logger.info { "Ingested '$filename': ${chunks.size} chunks, ~$totalTokens tokens in ${elapsed}ms" }

        semanticCache?.invalidateAll()

        return info
    }

    /**
     * Search the knowledge base with a natural language query.
     * Returns the top-K most relevant chunks with scores.
     *
     * @param query Natural language query.
     * @param topK Maximum number of results.
     * @param mode Search strategy — HYBRID (default), VECTOR_ONLY, or KEYWORD_ONLY.
     */
    suspend fun search(
        query: String,
        topK: Int = 5,
        mode: HybridSearchEngine.SearchMode = HybridSearchEngine.SearchMode.HYBRID,
    ): List<SearchResult> {
        val startTime = System.currentTimeMillis()

        // Check semantic cache first
        if (semanticCache != null) {
            val cached = semanticCache.get(query)
            if (cached != null) {
                return cached.map { scored ->
                    SearchResult(
                        content = scored.content,
                        score = scored.score,
                        source = scored.metadata["source"] ?: "",
                        heading = scored.metadata["heading"] ?: "",
                        metadata = scored.metadata + ("cache" to "hit"),
                    )
                }.take(topK)
            }
        }

        val results = hybridEngine.search(query, topK, mode)

        // Cache results
        semanticCache?.put(query, results)

        val elapsed = System.currentTimeMillis() - startTime
        logger.debug { "Knowledge search [$mode] for '${query.take(50)}': ${results.size} results in ${elapsed}ms" }

        return results.map { scored ->
            SearchResult(
                content = scored.content,
                score = scored.score,
                source = scored.metadata["source"] ?: "",
                heading = scored.metadata["heading"] ?: "",
                metadata = scored.metadata,
            )
        }
    }

    /**
     * Delete a document and all its chunks from the knowledge base.
     */
    suspend fun deleteDocument(docId: String) {
        // Delete all chunks with this doc_id
        // Use the doc_id to generate chunk IDs and delete them
        val info = documents[docId]
        if (info != null) {
            for (i in 0 until info.chunkCount) {
                vectorStore.delete("${docId}_chunk_$i")
            }
            documents.remove(docId)
            logger.info { "Deleted document '${info.filename}' ($docId, ${info.chunkCount} chunks)" }
        } else {
            // Fallback: try to delete by source metadata
            vectorStore.deleteBySource(docId)
            logger.info { "Deleted document $docId by source" }
        }
    }

    /**
     * List all ingested documents.
     */
    fun listDocuments(): List<DocumentInfo> = documents.values.toList()

    /**
     * Get total vector count across all documents.
     */
    suspend fun totalChunks(): Int = vectorStore.count()

    /**
     * Test embedding + vector store connectivity.
     */
    suspend fun testConnectivity(): ConnectivityResult {
        val embResult = embeddingService.testConnection()
        val storeOk = vectorStore.testConnection()
        return ConnectivityResult(
            embeddingOk = embResult.success,
            embeddingLatencyMs = embResult.latencyMs,
            embeddingDimensions = embResult.dimensions,
            embeddingError = embResult.error,
            vectorStoreOk = storeOk,
            vectorStoreType = vectorStore.storeName,
            embeddingProvider = embeddingService.providerName,
            embeddingModel = embeddingService.modelId,
        )
    }

    /**
     * Shut down resources.
     */
    suspend fun close() {
        embeddingService.close()
        vectorStore.close()
    }

    data class SearchResult(
        val content: String,
        val score: Double,
        val source: String,
        val heading: String,
        val metadata: Map<String, String>,
    )

    data class ConnectivityResult(
        val embeddingOk: Boolean,
        val embeddingLatencyMs: Long,
        val embeddingDimensions: Int,
        val embeddingError: String?,
        val vectorStoreOk: Boolean,
        val vectorStoreType: String,
        val embeddingProvider: String,
        val embeddingModel: String,
    )
}
