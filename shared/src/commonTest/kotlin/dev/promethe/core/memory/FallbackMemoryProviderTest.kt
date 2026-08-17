package dev.promethe.core.memory

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallbackMemoryProviderTest {
    @Test
    fun testFallbackWhenPrimaryUnavailable() =
        runTest {
            val primary = FakeMemoryProvider(name = "primary", available = false)
            val fallback = FakeMemoryProvider(name = "fallback", available = true)
            val provider = FallbackMemoryProvider(primary, fallback)

            // Store should go to fallback
            provider.storeFact(MemoryFact(category = "test", content = "Hello"))
            assertEquals(1, fallback.storedFacts.size, "Fact should be stored in fallback")

            // Recall should use fallback since primary is down
            fallback.storedFacts.add(MemoryFact(id = "1", category = "test", content = "World"))
            val results = provider.recallFacts("World")
            assertTrue(results.isNotEmpty(), "Should recall from fallback")
        }

    @Test
    fun testPrimaryUsedWhenAvailable() =
        runTest {
            val primary = FakeMemoryProvider(name = "primary", available = true)
            val fallback = FakeMemoryProvider(name = "fallback", available = true)
            val provider = FallbackMemoryProvider(primary, fallback)

            // Store goes to both
            provider.storeFact(MemoryFact(category = "test", content = "Hello"))
            assertEquals(1, fallback.storedFacts.size, "Fact should be stored in fallback (always)")
            assertEquals(1, primary.storedFacts.size, "Fact should also be stored in primary")

            // Recall should use primary first
            primary.recallResults = listOf(MemoryFact(id = "p1", category = "test", content = "Primary result"))
            val results = provider.recallFacts("test")
            assertEquals("Primary result", results.first().content)
        }

    @Test
    fun testFallbackNameCombinesBoth() {
        val primary = FakeMemoryProvider(name = "honcho", available = true)
        val fallback = FakeMemoryProvider(name = "embedded-sqlite", available = true)
        val provider = FallbackMemoryProvider(primary, fallback)

        assertEquals("honcho→embedded-sqlite", provider.name)
    }

    @Test
    fun testIsAvailableWhenEitherWorks() =
        runTest {
            val both =
                FallbackMemoryProvider(
                    FakeMemoryProvider("a", available = true),
                    FakeMemoryProvider("b", available = false),
                )
            assertTrue(both.isAvailable())

            val onlyFallback =
                FallbackMemoryProvider(
                    FakeMemoryProvider("a", available = false),
                    FakeMemoryProvider("b", available = true),
                )
            assertTrue(onlyFallback.isAvailable())

            val neither =
                FallbackMemoryProvider(
                    FakeMemoryProvider("a", available = false),
                    FakeMemoryProvider("b", available = false),
                )
            assertFalse(neither.isAvailable())
        }
}

/** Simple fake for testing without DB or network. */
class FakeMemoryProvider(
    override val name: String,
    private val available: Boolean,
) : MemoryProvider {
    val storedFacts = mutableListOf<MemoryFact>()
    var recallResults: List<MemoryFact> = emptyList()

    override suspend fun storeFact(fact: MemoryFact) {
        storedFacts.add(fact)
    }

    override suspend fun recallFacts(
        query: String,
        limit: Int,
        userId: String,
    ) = recallResults.ifEmpty { storedFacts.filter { it.content.contains(query, ignoreCase = true) } }.take(limit)

    override suspend fun getAllFacts(userId: String) = storedFacts.toList()

    override suspend fun deleteFact(id: String) {
        storedFacts.removeAll { it.id == id }
    }

    override suspend fun isAvailable() = available
}
