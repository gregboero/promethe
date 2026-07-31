package dev.promethe.core.rag

/**
 * HybridSearchEngine — combines FTS5 keyword search + vector similarity search
 * using Reciprocal Rank Fusion (RRF) for optimal retrieval quality.
 *
 * RRF formula: score(d) = w_vec * 1/(k + rank_vec(d)) + w_fts * 1/(k + rank_fts(d))
 *
 * This is the SOTA approach for RAG pipelines in 2026:
 * - Vector catches semantic similarity ("What are the benefits?" matches "advantages include...")
 * - FTS5 catches exact keyword matches ("Kotlin 2.4" matches only when those exact tokens appear)
 * - RRF fusion needs no score normalization — it works purely on rank positions
 */
private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

class HybridSearchEngine(
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore,
    private val ftsSearcher: FtsSearcher? = null,
    private val reRanker: ReRanker? = null,
    private val rrfK: Int = 60,
    private val vectorWeight: Double = 0.6,
    private val ftsWeight: Double = 0.4,
) {
    /**
     * Search mode determines which retrieval strategies to use.
     */
    enum class SearchMode {
        /** Vector similarity only (original behavior). */
        VECTOR_ONLY,

        /** FTS5 keyword matching only. */
        KEYWORD_ONLY,

        /** Both vector + FTS5 fused with Reciprocal Rank Fusion. */
        HYBRID,
    }

    /**
     * Interface for FTS keyword search — implemented by SqliteVecStore.
     * Separated so non-SQLite vector stores can also plug into hybrid search
     * by providing their own FTS implementation.
     */
    interface FtsSearcher {
        /**
         * Keyword search using FTS5.
         * @param query Raw text query (will be tokenized by FTS5).
         * @param topK Max results to return.
         * @param filter Optional metadata filters.
         * @return Results ordered by BM25 rank (best first).
         */
        suspend fun keywordSearch(
            query: String,
            topK: Int = 20,
            filter: Map<String, String> = emptyMap(),
        ): List<ScoredResult>
    }

    /**
     * Execute a hybrid search with the specified mode.
     *
     * @param query Natural language query text.
     * @param topK Number of results to return.
     * @param mode Search strategy to use.
     * @param filter Optional metadata filters applied to both retrievers.
     * @return Fused and ranked results.
     */
    suspend fun search(
        query: String,
        topK: Int = 5,
        mode: SearchMode = SearchMode.HYBRID,
        filter: Map<String, String> = emptyMap(),
    ): List<ScoredResult> {
        val startTime = System.currentTimeMillis()

        // Over-fetch if re-ranking (reranker will trim to topK)
        val fetchK = if (reRanker != null) topK * 3 else topK

        val results = when (mode) {
            SearchMode.VECTOR_ONLY -> vectorOnly(query, fetchK, filter)
            SearchMode.KEYWORD_ONLY -> keywordOnly(query, fetchK, filter)
            SearchMode.HYBRID -> hybrid(query, fetchK, filter)
        }

        // Optional second-pass re-ranking
        val reranked = if (reRanker != null && results.isNotEmpty()) {
            reRanker.rerank(query, results, topK)
        } else {
            results.take(topK)
        }

        val elapsed = System.currentTimeMillis() - startTime
        logger.debug {
            "HybridSearch [$mode${if (reRanker != null) "+rerank" else ""}]: '${query.take(50)}' → ${reranked.size} results in ${elapsed}ms"
        }
        return reranked
    }

    // ── Private retrieval strategies ──

    private suspend fun vectorOnly(
        query: String,
        topK: Int,
        filter: Map<String, String>,
    ): List<ScoredResult> {
        val queryEmbedding = embeddingService.embed(query)
        return vectorStore.search(queryEmbedding, topK, filter)
    }

    private suspend fun keywordOnly(
        query: String,
        topK: Int,
        filter: Map<String, String>,
    ): List<ScoredResult> {
        val searcher = ftsSearcher
            ?: throw IllegalStateException("FTS searcher not available — cannot use KEYWORD_ONLY mode")
        return searcher.keywordSearch(query, topK, filter)
    }

    private suspend fun hybrid(
        query: String,
        topK: Int,
        filter: Map<String, String>,
    ): List<ScoredResult> {
        // If no FTS searcher is configured, fallback to vector-only
        val searcher = ftsSearcher
        if (searcher == null) {
            logger.debug { "No FTS searcher configured — falling back to vector-only" }
            return vectorOnly(query, topK, filter)
        }

        // Retrieve candidates from both pipelines (over-fetch for better fusion)
        val fetchK = topK * 4 // Over-fetch to ensure good RRF coverage

        val queryEmbedding = embeddingService.embed(query)

        // Run both searches (could be parallel with coroutines, but SQLite is single-writer)
        val vecResults = vectorStore.search(queryEmbedding, fetchK, filter)
        val ftsResults = searcher.keywordSearch(query, fetchK, filter)

        logger.debug {
            "Hybrid candidates: ${vecResults.size} vector, ${ftsResults.size} FTS"
        }

        // Fuse with RRF
        return reciprocalRankFusion(vecResults, ftsResults, topK)
    }

    // ── Reciprocal Rank Fusion ──

    /**
     * Combines two ranked result lists using Reciprocal Rank Fusion.
     *
     * RRF is rank-based, not score-based, so it works even when
     * the two retrieval systems use completely different scoring scales
     * (e.g., cosine similarity [0,1] vs BM25 [-∞, 0]).
     *
     * Formula: RRF(d) = w_vec / (k + rank_vec(d)) + w_fts / (k + rank_fts(d))
     *
     * @param vecResults Results from vector search, ordered best-first.
     * @param ftsResults Results from FTS5 search, ordered best-first.
     * @param topK Number of results to return after fusion.
     * @return Fused results with RRF scores, ordered best-first.
     */
    internal fun reciprocalRankFusion(
        vecResults: List<ScoredResult>,
        ftsResults: List<ScoredResult>,
        topK: Int,
    ): List<ScoredResult> {
        // Build rank maps (id → 1-based rank position)
        val vecRanks = vecResults.mapIndexed { index, r -> r.id to (index + 1) }.toMap()
        val ftsRanks = ftsResults.mapIndexed { index, r -> r.id to (index + 1) }.toMap()

        // Collect all unique document IDs
        val allIds = (vecRanks.keys + ftsRanks.keys)

        // Build content/metadata lookup from both result sets
        val contentMap = mutableMapOf<String, ScoredResult>()
        for (r in vecResults) contentMap.putIfAbsent(r.id, r)
        for (r in ftsResults) contentMap.putIfAbsent(r.id, r)

        // Compute RRF scores
        val rrfScores = allIds.map { id ->
            val vecScore = vecRanks[id]?.let { rank -> vectorWeight / (rrfK + rank) } ?: 0.0
            val ftsScore = ftsRanks[id]?.let { rank -> ftsWeight / (rrfK + rank) } ?: 0.0
            val totalScore = vecScore + ftsScore

            val original = contentMap[id]!!
            ScoredResult(
                id = id,
                content = original.content,
                score = totalScore,
                metadata = original.metadata + buildMap {
                    vecRanks[id]?.let { put("vec_rank", it.toString()) }
                    ftsRanks[id]?.let { put("fts_rank", it.toString()) }
                    put("rrf_score", "%.6f".format(totalScore))
                },
            )
        }

        return rrfScores.sortedByDescending { it.score }.take(topK)
    }
}
