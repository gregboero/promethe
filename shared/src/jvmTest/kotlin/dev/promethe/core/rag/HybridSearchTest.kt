package dev.promethe.core.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HybridSearchTest {
    @Test
    fun `RRF fusion combines two ranked lists correctly`() {
        val engine = HybridSearchEngine(
            embeddingService = NoOpEmbeddingService(),
            vectorStore = NoOpVectorStore(),
            rrfK = 60,
            vectorWeight = 0.6,
            ftsWeight = 0.4,
        )

        val vecResults = listOf(
            ScoredResult("doc_a", "content A", 0.95),
            ScoredResult("doc_b", "content B", 0.80),
            ScoredResult("doc_c", "content C", 0.70),
        )
        val ftsResults = listOf(
            ScoredResult("doc_c", "content C", 5.0),
            ScoredResult("doc_a", "content A", 3.0),
            ScoredResult("doc_d", "content D", 1.0),
        )

        val fused = engine.reciprocalRankFusion(vecResults, ftsResults, topK = 10)

        // doc_a appears in both lists → highest RRF score
        // doc_a: vec rank=1, fts rank=2 → 0.6/(60+1) + 0.4/(60+2) = 0.00984 + 0.00645 = 0.01629
        // doc_c: vec rank=3, fts rank=1 → 0.6/(60+3) + 0.4/(60+1) = 0.00952 + 0.00656 = 0.01608
        assertTrue(fused[0].id == "doc_a" || fused[0].id == "doc_c")
        assertEquals(4, fused.size) // 4 unique docs
    }

    @Test
    fun `RRF with disjoint lists merges all documents`() {
        val engine = HybridSearchEngine(
            embeddingService = NoOpEmbeddingService(),
            vectorStore = NoOpVectorStore(),
        )

        val vecResults = listOf(
            ScoredResult("v1", "vec only 1", 0.9),
            ScoredResult("v2", "vec only 2", 0.8),
        )
        val ftsResults = listOf(
            ScoredResult("f1", "fts only 1", 5.0),
            ScoredResult("f2", "fts only 2", 3.0),
        )

        val fused = engine.reciprocalRankFusion(vecResults, ftsResults, topK = 10)
        assertEquals(4, fused.size)
        assertTrue(fused.all { it.score > 0.0 })
    }

    @Test
    fun `RRF topK limits output`() {
        val engine = HybridSearchEngine(
            embeddingService = NoOpEmbeddingService(),
            vectorStore = NoOpVectorStore(),
        )

        val vecResults = (1..10).map { ScoredResult("doc_$it", "content $it", 1.0 - it * 0.05) }
        val ftsResults = (5..15).map { ScoredResult("doc_$it", "content $it", 10.0 - it) }

        val fused = engine.reciprocalRankFusion(vecResults, ftsResults, topK = 3)
        assertEquals(3, fused.size)
    }

    @Test
    fun `RRF metadata includes rank info`() {
        val engine = HybridSearchEngine(
            embeddingService = NoOpEmbeddingService(),
            vectorStore = NoOpVectorStore(),
        )

        val vecResults = listOf(ScoredResult("doc_x", "content X", 0.9))
        val ftsResults = listOf(ScoredResult("doc_x", "content X", 5.0))

        val fused = engine.reciprocalRankFusion(vecResults, ftsResults, topK = 5)
        assertEquals(1, fused.size)
        assertEquals("1", fused[0].metadata["vec_rank"])
        assertEquals("1", fused[0].metadata["fts_rank"])
        assertTrue(fused[0].metadata.containsKey("rrf_score"))
    }

    // ── Test doubles ──

    private class NoOpEmbeddingService : EmbeddingService {
        override val providerName = "noop"
        override val modelId = "noop"
        override val dimensions = 768

        override suspend fun embed(text: String) = FloatArray(768)

        override suspend fun embedBatch(texts: List<String>) = texts.map { FloatArray(768) }

        override suspend fun testConnection() = EmbeddingTestResult(true, 0, 768, null)

        override suspend fun close() {}
    }

    private class NoOpVectorStore : VectorStore {
        override val storeName = "noop"

        override suspend fun upsert(
            id: String,
            embedding: FloatArray,
            content: String,
            metadata: Map<String, String>,
        ) {}

        override suspend fun upsertBatch(items: List<VectorItem>) {}

        override suspend fun search(
            queryEmbedding: FloatArray,
            topK: Int,
            filter: Map<String, String>,
        ) = emptyList<ScoredResult>()

        override suspend fun delete(id: String) {}

        override suspend fun deleteBySource(source: String) {}

        override suspend fun count() = 0

        override suspend fun testConnection() = true

        override suspend fun close() {}
    }
}
