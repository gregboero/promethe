package dev.promethe.core.memory

import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.db.UserFactRow

/**
 * Default memory provider — stores facts in local SQLite via PrometheDatabaseApi.
 * Zero external dependencies. Facts survive restarts.
 *
 * Uses existing DB infrastructure:
 * - Table: user_facts (already created in V1 schema)
 * - CRUD: insertUserFact, getAllUserFacts, searchUserFacts, deleteUserFact
 * - Search: FTS5 index on user_facts (added in V2 migration)
 */
class EmbeddedMemoryProvider(
    private val database: PrometheDatabaseApi,
) : MemoryProvider {
    override val name: String = "embedded-sqlite"

    override suspend fun storeFact(fact: MemoryFact) {
        // Check for existing similar fact → upsert with confidence boost
        val existing =
            database.getAllUserFacts(fact.userId).find { existing ->
                existing.category == fact.category &&
                    existing.fact.equals(fact.content, ignoreCase = true)
            }

        if (existing != null) {
            // Boost confidence and update timestamp
            val boosted =
                existing.copy(
                    confidence = minOf(existing.confidence + 0.1, 1.0),
                    updatedAt = System.currentTimeMillis(),
                )
            database.deleteUserFact(existing.id)
            database.insertUserFact(boosted)
        } else {
            database.insertUserFact(
                UserFactRow(
                    userId = fact.userId,
                    category = fact.category,
                    fact = fact.content,
                    confidence = fact.confidence.toDouble(),
                    sourceSession = fact.sourceSession,
                    createdAt = fact.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    updatedAt = fact.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun recallFacts(
        query: String,
        limit: Int,
    ): List<MemoryFact> {
        val results = database.searchUserFacts(query)
        return results
            .sortedByDescending { it.confidence }
            .take(limit)
            .map { it.toMemoryFact() }
    }

    override suspend fun getAllFacts(userId: String): List<MemoryFact> = database.getAllUserFacts(userId).map { it.toMemoryFact() }

    override suspend fun deleteFact(id: String) {
        val intId = id.toIntOrNull() ?: return
        database.deleteUserFact(intId)
    }

    override suspend fun isAvailable(): Boolean = true
}

// ── Mapper ──

private fun UserFactRow.toMemoryFact() =
    MemoryFact(
        id = id.toString(),
        userId = userId,
        category = category,
        content = fact,
        confidence = confidence.toFloat(),
        sourceSession = sourceSession,
        tier = MemoryTier.ATOMIC,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
