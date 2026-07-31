package dev.promethe.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryProviderModelsTest {
    @Test
    fun testMemoryTierValues() {
        val tiers = MemoryTier.entries
        assertEquals(4, tiers.size)
        assertTrue(tiers.contains(MemoryTier.CONVERSATION))
        assertTrue(tiers.contains(MemoryTier.ATOMIC))
        assertTrue(tiers.contains(MemoryTier.SCENARIO))
        assertTrue(tiers.contains(MemoryTier.PERSONA))
    }

    @Test
    fun testMemoryFactDefaults() {
        val fact = MemoryFact(category = "test", content = "Hello")
        assertEquals("", fact.id)
        assertEquals("default", fact.userId)
        assertEquals("test", fact.category)
        assertEquals("Hello", fact.content)
        assertEquals(1.0f, fact.confidence)
        assertEquals("", fact.sourceSession)
        assertEquals(MemoryTier.ATOMIC, fact.tier)
        assertEquals(0L, fact.createdAt)
        assertEquals(0L, fact.updatedAt)
    }

    @Test
    fun testMemoryFactCopy() {
        val original =
            MemoryFact(
                id = "1",
                category = "preference",
                content = "Likes Kotlin",
                confidence = 0.8f,
            )
        val boosted = original.copy(confidence = 0.9f, updatedAt = 12345L)
        assertEquals("1", boosted.id)
        assertEquals("Likes Kotlin", boosted.content)
        assertEquals(0.9f, boosted.confidence)
        assertEquals(12345L, boosted.updatedAt)
    }

    @Test
    fun testMemoryFactEquality() {
        val a = MemoryFact(id = "1", category = "test", content = "Hello")
        val b = MemoryFact(id = "1", category = "test", content = "Hello")
        assertEquals(a, b)
    }
}
