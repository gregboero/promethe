package dev.promethe.core.tools.ai

import kotlin.test.*
import java.io.File

/**
 * Tests for [LocalVectorStore] — in-memory cosine similarity + JSON persistence.
 */
class LocalVectorStoreTest {
    private lateinit var tmpDir: File
    private lateinit var store: LocalVectorStore

    @BeforeTest
    fun setup() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "vec_test_${System.currentTimeMillis()}")
        tmpDir.mkdirs()
        store = LocalVectorStore(tmpDir.absolutePath)
    }

    @AfterTest
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `add stores vector and returns id`() {
        val id = store.add("hello world", floatArrayOf(1f, 0f, 0f))
        assertTrue(id.startsWith("vec_"))
        assertEquals(1, store.count())
    }

    @Test
    fun `search returns results sorted by similarity`() {
        store.add("cat", floatArrayOf(1f, 0f, 0f))
        store.add("dog", floatArrayOf(0.9f, 0.1f, 0f))
        store.add("car", floatArrayOf(0f, 0f, 1f))

        val results = store.search(floatArrayOf(1f, 0f, 0f), topK = 2)
        assertEquals(2, results.size)
        assertEquals("cat", results[0].entry.text, "Exact match should be first")
        assertEquals("dog", results[1].entry.text, "Close match should be second")
    }

    @Test
    fun `search with empty collection returns empty`() {
        val results = store.search(floatArrayOf(1f, 0f, 0f))
        assertTrue(results.isEmpty())
    }

    @Test
    fun `collection filtering works`() {
        store.add("a", floatArrayOf(1f, 0f), collection = "alpha")
        store.add("b", floatArrayOf(0f, 1f), collection = "beta")

        assertEquals(1, store.count("alpha"))
        assertEquals(1, store.count("beta"))
        assertEquals(0, store.count("gamma"))
    }

    @Test
    fun `cosine similarity of identical vectors is 1`() {
        val sim = LocalVectorStore.cosineSimilarity(
            floatArrayOf(1f, 2f, 3f),
            floatArrayOf(1f, 2f, 3f),
        )
        assertEquals(1.0, sim, 1e-6)
    }

    @Test
    fun `cosine similarity of orthogonal vectors is 0`() {
        val sim = LocalVectorStore.cosineSimilarity(
            floatArrayOf(1f, 0f),
            floatArrayOf(0f, 1f),
        )
        assertEquals(0.0, sim, 1e-6)
    }

    @Test
    fun `cosine similarity of opposite vectors is -1`() {
        val sim = LocalVectorStore.cosineSimilarity(
            floatArrayOf(1f, 0f),
            floatArrayOf(-1f, 0f),
        )
        assertEquals(-1.0, sim, 1e-6)
    }

    @Test
    fun `cosine similarity with empty array returns 0`() {
        val sim = LocalVectorStore.cosineSimilarity(floatArrayOf(), floatArrayOf(1f, 2f))
        assertEquals(0.0, sim)
    }

    @Test
    fun `persistence works across instances`() {
        store.add("persistent", floatArrayOf(1f, 2f, 3f))
        assertEquals(1, store.count())

        // Create new instance pointing to same dir
        val store2 = LocalVectorStore(tmpDir.absolutePath)
        assertEquals(1, store2.count(), "Second instance should see persisted data")

        val results = store2.search(floatArrayOf(1f, 2f, 3f), topK = 1)
        assertEquals(1, results.size)
        assertEquals("persistent", results[0].entry.text)
    }

    @Test
    fun `totalCount counts across all collections`() {
        store.add("a", floatArrayOf(1f), collection = "c1")
        store.add("b", floatArrayOf(1f), collection = "c2")
        store.add("c", floatArrayOf(1f), collection = "c1")

        assertEquals(3, store.totalCount())
        assertEquals(2, store.count("c1"))
        assertEquals(1, store.count("c2"))
    }

    @Test
    fun `topK limits results`() {
        repeat(10) { i ->
            store.add("item $i", floatArrayOf(i.toFloat(), 0f))
        }

        val results = store.search(floatArrayOf(5f, 0f), topK = 3)
        assertEquals(3, results.size)
    }
}
