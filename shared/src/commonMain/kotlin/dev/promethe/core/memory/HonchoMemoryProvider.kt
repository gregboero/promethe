package dev.promethe.core.memory

import dev.promethe.core.HonchoClient

/**
 * Memory provider backed by Honcho (Plastic Labs).
 * Honcho is an external service — requires a running Honcho instance.
 *
 * @see <a href="https://github.com/plastic-labs/honcho">Honcho GitHub</a>
 *
 * Architecture:
 * - storeFact() → syncs profile data to Honcho via syncProfile()
 * - recallFacts() → retrieves semantic context via fetchContext()
 * - Falls back gracefully if Honcho is unavailable
 */
class HonchoMemoryProvider(
    private val honchoClient: HonchoClient,
    private val sessionId: String = "current",
) : MemoryProvider {
    override val name: String = "honcho"

    /** Accumulates facts locally before syncing to Honcho in batch. */
    private val pendingFacts = mutableListOf<MemoryFact>()

    override suspend fun storeFact(fact: MemoryFact) {
        pendingFacts.add(fact)
        // Batch sync to Honcho: send accumulated facts as profile context
        val profileContext = pendingFacts.joinToString("\n") { "[${it.category}] ${it.content}" }
        honchoClient.syncProfile(sessionId, "", profileContext)
    }

    override suspend fun recallFacts(
        query: String,
        limit: Int,
    ): List<MemoryFact> {
        val context = honchoClient.fetchContext(sessionId, query)
        if (context.isBlank()) return emptyList()

        // Honcho returns a text context block — wrap as a single SCENARIO-level fact
        return listOf(
            MemoryFact(
                id = "honcho-context",
                category = "context",
                content = context,
                tier = MemoryTier.SCENARIO,
            ),
        )
    }

    override suspend fun getAllFacts(userId: String): List<MemoryFact> {
        // Honcho doesn't expose a "list all facts" API — return pending local facts
        return pendingFacts.toList()
    }

    override suspend fun deleteFact(id: String) {
        pendingFacts.removeAll { it.id == id }
    }

    override suspend fun isAvailable(): Boolean = honchoClient.isAvailable()
}
