package dev.promethe.core.rag

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * ReRanker — re-scores and re-orders search results based on query-document relevance.
 *
 * After initial retrieval (vector + FTS5 + RRF), a re-ranker provides a second-pass
 * scoring that is typically more accurate but more expensive.
 */
interface ReRanker {
    /**
     * Re-rank candidates based on relevance to the query.
     *
     * @param query The original search query.
     * @param candidates Initial retrieval results to re-rank.
     * @param topK Max results to return after re-ranking.
     * @return Re-ranked results, ordered best-first with updated scores.
     */
    suspend fun rerank(
        query: String,
        candidates: List<ScoredResult>,
        topK: Int,
    ): List<ScoredResult>
}

/**
 * EmbeddingReRanker — lightweight re-ranker using the existing EmbeddingService.
 *
 * Strategy: embed the query, embed each candidate, compute cross-similarity.
 * This is a "bi-encoder" approximation of a cross-encoder — much cheaper than
 * a dedicated cross-encoder model but still provides meaningful re-ordering.
 *
 * The key insight: initial retrieval uses chunk embeddings computed at ingestion time,
 * but re-ranking computes fresh embeddings of the actual retrieved content paired
 * with the query, giving a more contextual similarity signal.
 */
class EmbeddingReRanker(
    private val embeddingService: EmbeddingService,
    /** Weight given to the re-ranker score vs original score. 0.0 = original only, 1.0 = reranker only. */
    private val rerankerWeight: Double = 0.7,
) : ReRanker {
    override suspend fun rerank(
        query: String,
        candidates: List<ScoredResult>,
        topK: Int,
    ): List<ScoredResult> {
        if (candidates.isEmpty()) return emptyList()

        val startTime = System.currentTimeMillis()

        // Embed query
        val queryEmbedding = embeddingService.embed(query)

        // Batch embed all candidate contents for efficiency
        val contents = candidates.map { it.content }
        val contentEmbeddings = embeddingService.embedBatch(contents)

        // Compute cross-similarity scores
        val reranked = candidates.mapIndexed { index, candidate ->
            val crossSimilarity = cosineSimilarity(queryEmbedding, contentEmbeddings[index])
            val originalScore = candidate.score

            // Blend original retrieval score with cross-similarity
            val blendedScore = (1 - rerankerWeight) * originalScore + rerankerWeight * crossSimilarity

            candidate.copy(
                score = blendedScore,
                metadata = candidate.metadata + mapOf(
                    "reranker_cross_sim" to "%.4f".format(crossSimilarity),
                    "reranker_original" to "%.4f".format(originalScore),
                    "reranker_blended" to "%.4f".format(blendedScore),
                ),
            )
        }

        val result = reranked.sortedByDescending { it.score }.take(topK)

        val elapsed = System.currentTimeMillis() - startTime
        logger.debug { "ReRank: ${candidates.size} → ${result.size} candidates in ${elapsed}ms" }

        return result
    }

    private fun cosineSimilarity(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA) * Math.sqrt(normB)
        return if (denom > 0) dot / denom else 0.0
    }
}
