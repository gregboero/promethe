package dev.promethe.core.memory

import kotlinx.serialization.Serializable

/**
 * Unified memory tier model inspired by TencentDB Agent Memory's 4-tier architecture.
 *
 * L0 = Conversation (raw messages — handled by DB messages table)
 * L1 = Atomic (extracted facts, preferences, constraints)
 * L2 = Scenario (aggregated scene blocks from related memories)
 * L3 = Persona (high-level user profile, long-term preferences)
 */
enum class MemoryTier {
    CONVERSATION, // L0
    ATOMIC, // L1
    SCENARIO, // L2
    PERSONA, // L3
}

@Serializable
data class MemoryFact(
    val id: String = "",
    val userId: String = "default",
    val category: String, // "preference", "project", "personal", "technical"
    val content: String,
    val confidence: Float = 1.0f,
    val sourceSession: String = "",
    val tier: MemoryTier = MemoryTier.ATOMIC,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * Provider interface for pluggable memory backends.
 *
 * Implementations:
 * - [EmbeddedMemoryProvider] — SQLite (default, zero dependencies)
 * - [HonchoMemoryProvider] — Honcho REST API (external service)
 * - [TencentMemoryProvider] — TencentDB Agent Memory REST API (self-hosted or cloud)
 */
interface MemoryProvider {
    /** Store a fact. Upserts if a similar fact already exists (boost confidence). */
    suspend fun storeFact(fact: MemoryFact)

    /** Recall relevant facts for a query. Semantic or keyword-based depending on provider. */
    suspend fun recallFacts(
        query: String,
        limit: Int = 10,
        userId: String = "default",
    ): List<MemoryFact>

    /** Get all stored facts for a user. */
    suspend fun getAllFacts(userId: String = "default"): List<MemoryFact>

    /** Delete a specific fact by id. */
    suspend fun deleteFact(id: String)

    /** Update a fact's fields by id. Returns true if found. Default: delete + re-store. */
    suspend fun updateFact(
        id: String,
        category: String? = null,
        content: String? = null,
        confidence: Float? = null,
    ): Boolean {
        val existing = getAllFacts().find { it.id == id } ?: return false
        deleteFact(id)
        storeFact(
            existing.copy(
                category = category ?: existing.category,
                content = content ?: existing.content,
                confidence = confidence ?: existing.confidence,
            ),
        )
        return true
    }

    /** Check if this provider is available and operational. */
    suspend fun isAvailable(): Boolean

    /** Human-readable provider name for logging/UI. */
    val name: String
}
