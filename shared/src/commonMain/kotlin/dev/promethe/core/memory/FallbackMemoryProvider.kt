package dev.promethe.core.memory

import dev.promethe.core.Log

/**
 * Wraps a primary provider with a fallback.
 * If the primary is unavailable or fails, delegates to the fallback transparently.
 */
class FallbackMemoryProvider(
    private val primary: MemoryProvider,
    private val fallback: MemoryProvider,
) : MemoryProvider {
    private val logger = Log.create("FallbackMemoryProvider")
    override val name: String = "${primary.name}→${fallback.name}"

    override suspend fun storeFact(fact: MemoryFact) {
        // Always store in fallback (local DB) for durability
        fallback.storeFact(fact)
        // Also try to store in primary (best-effort)
        try {
            if (primary.isAvailable()) {
                primary.storeFact(fact)
            }
        } catch (e: Exception) {
            // Primary failed — fact is safe in fallback
            logger.warn(e) { "Primary memory store failed, fact saved to fallback only" }
        }
    }

    override suspend fun recallFacts(
        query: String,
        limit: Int,
        userId: String,
    ): List<MemoryFact> {
        return try {
            if (primary.isAvailable()) {
                val results = primary.recallFacts(query, limit, userId)
                if (results.isNotEmpty()) return results
            }
            // Primary unavailable or returned nothing — use fallback
            fallback.recallFacts(query, limit, userId)
        } catch (e: Exception) {
            logger.warn(e) { "Primary memory recall failed, using fallback" }
            fallback.recallFacts(query, limit, userId)
        }
    }

    override suspend fun getAllFacts(userId: String): List<MemoryFact> =
        try {
            if (primary.isAvailable()) {
                primary.getAllFacts(userId)
            } else {
                fallback.getAllFacts(userId)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Primary getAllFacts failed, using fallback" }
            fallback.getAllFacts(userId)
        }

    override suspend fun deleteFact(id: String) {
        fallback.deleteFact(id)
        try {
            if (primary.isAvailable()) primary.deleteFact(id)
        } catch (e: Exception) {
            // best-effort
            logger.warn(e) { "Primary memory delete failed for fact '$id'" }
        }
    }

    override suspend fun isAvailable(): Boolean = primary.isAvailable() || fallback.isAvailable()
}
