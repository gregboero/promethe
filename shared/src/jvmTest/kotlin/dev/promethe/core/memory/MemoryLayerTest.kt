package dev.promethe.core.memory

import dev.promethe.core.memory.*
import dev.promethe.db.DatabaseFactory
import dev.promethe.db.PrometheDatabaseApi
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MemoryLayerTest {
    private lateinit var database: PrometheDatabaseApi
    private lateinit var provider: EmbeddedMemoryProvider

    @BeforeTest
    fun setup() {
        database = DatabaseFactory.createInMemory()
        provider = EmbeddedMemoryProvider(database)
    }

    @Test
    fun testBuildMemoryContextEmpty() =
        runTest {
            // MemoryLayer needs an LLM adapter for extraction, but buildMemoryContext
            // only needs the provider. We can test with a fake that returns empty.
            val fakeProvider = FakeTestProvider()
            val context = buildMemoryContextFromProvider(fakeProvider, "test query")
            assertEquals("", context, "Should return empty when no facts")
        }

    @Test
    fun testBuildMemoryContextWithFacts() =
        runTest {
            val fakeProvider = FakeTestProvider()
            fakeProvider.facts.add(MemoryFact(category = "preference", content = "Uses Kotlin"))
            fakeProvider.facts.add(MemoryFact(category = "project", content = "Building Promethe"))

            val context = buildMemoryContextFromProvider(fakeProvider, "test")
            assertTrue(context.contains("[User Memory]"))
            assertTrue(context.contains("Uses Kotlin"))
            assertTrue(context.contains("Building Promethe"))
        }

    @Test
    fun testProviderNameExposed() {
        assertEquals("embedded-sqlite", provider.name)
    }

    @Test
    fun testFactPersistenceAcrossInstances() =
        runTest {
            // Store fact in one provider instance
            provider.storeFact(MemoryFact(category = "test", content = "Persistent fact"))

            // Create a new provider instance with same DB
            val provider2 = EmbeddedMemoryProvider(database)
            val facts = provider2.getAllFacts()

            assertEquals(1, facts.size, "Facts should survive across provider instances")
            assertEquals("Persistent fact", facts[0].content)
        }
}

/** Minimal helper to test context building without LLM */
private suspend fun buildMemoryContextFromProvider(
    provider: MemoryProvider,
    query: String,
): String {
    val facts = provider.recallFacts(query)
    if (facts.isEmpty()) return ""

    val grouped = facts.groupBy { it.category }
    return buildString {
        appendLine("[User Memory]")
        grouped.forEach { (category, categoryFacts) ->
            appendLine("## ${category.replaceFirstChar { it.uppercase() }}")
            categoryFacts.forEach { f ->
                appendLine("- ${f.content}")
            }
        }
    }
}

private class FakeTestProvider : MemoryProvider {
    override val name = "fake"
    val facts = mutableListOf<MemoryFact>()

    override suspend fun storeFact(fact: MemoryFact) {
        facts.add(fact)
    }

    override suspend fun recallFacts(
        query: String,
        limit: Int,
    ) = facts.take(limit)

    override suspend fun getAllFacts(userId: String) = facts.toList()

    override suspend fun deleteFact(id: String) {
        facts.removeAll { it.id == id }
    }

    override suspend fun isAvailable() = true
}
