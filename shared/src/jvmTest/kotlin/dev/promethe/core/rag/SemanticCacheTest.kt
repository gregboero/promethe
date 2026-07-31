package dev.promethe.core.rag

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SemanticCacheTest {
    // Use a deterministic embedding service for testing
    private class FixedEmbeddingService(
        private val dims: Int = 8,
    ) : EmbeddingService {
        override val providerName = "fixed-test"
        override val modelId = "fixed"
        override val dimensions = dims

        // Return embedding based on query hash for deterministic behavior
        override suspend fun embed(text: String): FloatArray {
            val hash = text.hashCode()
            return FloatArray(dims) { i -> ((hash shr (i % 32)) and 1).toFloat() }
        }

        override suspend fun embedBatch(texts: List<String>) = texts.map { embed(it) }

        override suspend fun testConnection() = EmbeddingTestResult(true, 0, dims)

        override suspend fun close() {}
    }

    @Test
    fun `cache miss returns null`() =
        runTest {
            val cache = SemanticCache(FixedEmbeddingService(), similarityThreshold = 0.95)
            val result = cache.get("some query")
            assertNull(result)
            assertEquals(1, cache.misses)
        }

    @Test
    fun `exact same query is cache hit`() =
        runTest {
            val cache = SemanticCache(FixedEmbeddingService(), similarityThreshold = 0.95)
            val results = listOf(ScoredResult("doc1", "content", 0.9))

            cache.put("hello world", results)
            val cached = cache.get("hello world")

            assertNotNull(cached)
            assertEquals(1, cached.size)
            assertEquals("doc1", cached[0].id)
            assertEquals(1, cache.hits)
        }

    @Test
    fun `different query is cache miss`() =
        runTest {
            val cache = SemanticCache(FixedEmbeddingService(), similarityThreshold = 0.99)
            val results = listOf(ScoredResult("doc1", "content", 0.9))

            cache.put("hello world", results)
            val cached = cache.get("completely different query about databases")

            assertNull(cached)
        }

    @Test
    fun `LRU eviction when at capacity`() =
        runTest {
            val cache = SemanticCache(FixedEmbeddingService(), maxEntries = 2)

            cache.put("query1", listOf(ScoredResult("d1", "c1", 0.9)))
            cache.put("query2", listOf(ScoredResult("d2", "c2", 0.8)))
            cache.put("query3", listOf(ScoredResult("d3", "c3", 0.7))) // should evict query1

            val stats = cache.stats()
            assertEquals(2, stats["size"])
        }

    @Test
    fun `invalidateAll clears cache`() =
        runTest {
            val cache = SemanticCache(FixedEmbeddingService())
            cache.put("q1", listOf(ScoredResult("d1", "c1", 0.9)))
            cache.invalidateAll()
            assertNull(cache.get("q1"))
        }

    @Test
    fun `stats reports correctly`() =
        runTest {
            val cache = SemanticCache(FixedEmbeddingService())
            cache.put("q1", listOf(ScoredResult("d1", "c1", 0.9)))
            cache.get("q1") // hit
            cache.get("q2") // miss

            val stats = cache.stats()
            assertEquals(1L, stats["hits"])
            assertEquals(1L, stats["misses"])
        }
}
