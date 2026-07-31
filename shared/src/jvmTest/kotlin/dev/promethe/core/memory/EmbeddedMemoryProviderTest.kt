package dev.promethe.core.memory

import dev.promethe.db.DatabaseFactory
import dev.promethe.db.PrometheDatabaseApi
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class EmbeddedMemoryProviderTest {
    private lateinit var database: PrometheDatabaseApi
    private lateinit var provider: EmbeddedMemoryProvider

    @BeforeTest
    fun setup() {
        database = DatabaseFactory.createInMemory()
        provider = EmbeddedMemoryProvider(database)
    }

    @Test
    fun testIsAvailableAlwaysTrue() =
        runTest {
            assertTrue(provider.isAvailable(), "Embedded provider should always be available")
        }

    @Test
    fun testStoreAndRetrieveFact() =
        runTest {
            val fact =
                MemoryFact(
                    category = "preference",
                    content = "User prefers Kotlin over Java",
                    confidence = 0.9f,
                    sourceSession = "test-session-1",
                )
            provider.storeFact(fact)

            val allFacts = provider.getAllFacts()
            assertEquals(1, allFacts.size)
            assertEquals("preference", allFacts[0].category)
            assertEquals("User prefers Kotlin over Java", allFacts[0].content)
        }

    @Test
    fun testRecallFactsByQuery() =
        runTest {
            provider.storeFact(MemoryFact(category = "preference", content = "User prefers Kotlin"))
            provider.storeFact(MemoryFact(category = "project", content = "Project uses React frontend"))
            provider.storeFact(MemoryFact(category = "personal", content = "User name is Greg"))

            val results = provider.recallFacts("Kotlin")
            assertTrue(results.isNotEmpty(), "Should find facts matching 'Kotlin'")
            assertTrue(results.any { it.content.contains("Kotlin") })
        }

    @Test
    fun testUpsertBoostsConfidence() =
        runTest {
            val fact =
                MemoryFact(
                    category = "preference",
                    content = "User prefers dark mode",
                    confidence = 0.5f,
                )
            provider.storeFact(fact)
            provider.storeFact(fact) // Same fact again → should boost confidence

            val allFacts = provider.getAllFacts()
            assertEquals(1, allFacts.size, "Should not duplicate — upsert")
            assertTrue(allFacts[0].confidence > 0.5f, "Confidence should be boosted")
        }

    @Test
    fun testDeleteFact() =
        runTest {
            provider.storeFact(MemoryFact(category = "test", content = "Fact to delete"))
            val allFacts = provider.getAllFacts()
            assertEquals(1, allFacts.size)

            provider.deleteFact(allFacts[0].id)

            val afterDelete = provider.getAllFacts()
            assertEquals(0, afterDelete.size, "Fact should be deleted")
        }

    @Test
    fun testMultipleFactsDifferentCategories() =
        runTest {
            provider.storeFact(MemoryFact(category = "preference", content = "Likes Vim"))
            provider.storeFact(MemoryFact(category = "project", content = "Uses Gradle"))
            provider.storeFact(MemoryFact(category = "personal", content = "Lives in Montreal"))
            provider.storeFact(MemoryFact(category = "technical", content = "Prefers coroutines over threads"))

            val allFacts = provider.getAllFacts()
            assertEquals(4, allFacts.size)

            val categories = allFacts.map { it.category }.toSet()
            assertEquals(setOf("preference", "project", "personal", "technical"), categories)
        }

    @Test
    fun testProviderName() {
        assertEquals("embedded-sqlite", provider.name)
    }
}
