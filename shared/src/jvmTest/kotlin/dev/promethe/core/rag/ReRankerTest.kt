package dev.promethe.core.rag

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReRankerTest {
    // A mock EmbeddingService that returns specific predictable embeddings
    private class MockEmbeddingService : EmbeddingService {
        override val providerName = "mock"
        override val modelId = "mock-model"
        override val dimensions = 3

        override suspend fun embed(text: String): FloatArray =
            when (text) {
                "kotlin" -> floatArrayOf(1.0f, 0.0f, 0.0f)
                "kotlin guide" -> floatArrayOf(0.9f, 0.1f, 0.0f)
                "java guide" -> floatArrayOf(0.0f, 1.0f, 0.0f)
                else -> floatArrayOf(0.0f, 0.0f, 1.0f)
            }

        override suspend fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

        override suspend fun testConnection() = EmbeddingTestResult(true, 0, dimensions)

        override suspend fun close() {}
    }

    @Test
    fun `EmbeddingReRanker reorders candidates`() =
        runTest {
            val reranker = EmbeddingReRanker(MockEmbeddingService(), rerankerWeight = 1.0) // 100% reranker weight

            // Initial candidates where "java guide" has a higher initial score but is less semantically similar to "kotlin"
            val candidates = listOf(
                ScoredResult("doc1", "java guide", 0.9),
                ScoredResult("doc2", "kotlin guide", 0.5),
            )

            val reranked = reranker.rerank("kotlin", candidates, 2)

            assertEquals(2, reranked.size)
            // "kotlin guide" should now be first because its cross-similarity to "kotlin" is higher
            assertEquals("doc2", reranked[0].id)
            assertEquals("doc1", reranked[1].id)

            val doc2Sim = reranked[0].metadata["reranker_cross_sim"]?.replace(',', '.')?.toDouble() ?: 0.0
            val doc1Sim = reranked[1].metadata["reranker_cross_sim"]?.replace(',', '.')?.toDouble() ?: 0.0
            assertTrue(doc2Sim > doc1Sim, "Cross similarity for kotlin guide should be higher")
        }

    @Test
    fun `EmbeddingReRanker respects topK`() =
        runTest {
            val reranker = EmbeddingReRanker(MockEmbeddingService())

            val candidates = listOf(
                ScoredResult("doc1", "kotlin guide", 0.9),
                ScoredResult("doc2", "java guide", 0.8),
                ScoredResult("doc3", "other text", 0.7),
            )

            val reranked = reranker.rerank("kotlin", candidates, 2)

            assertEquals(2, reranked.size, "Should only return topK results")
        }
}
