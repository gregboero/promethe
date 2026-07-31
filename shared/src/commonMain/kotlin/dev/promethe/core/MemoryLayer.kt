package dev.promethe.core

import dev.promethe.core.memory.MemoryFact
import dev.promethe.core.memory.MemoryProvider
import dev.promethe.core.memory.MemoryTier
import kotlinx.serialization.json.*

/**
 * MemoryLayer — 4-tier memory system inspired by TencentDB Agent Memory.
 *
 * Tier 0: Conversation (raw messages — handled by DB messages table)
 * Tier 1: Atomic Facts (extracted preferences, decisions, context)
 * Tier 2: Scenario (aggregated from related sessions)
 * Tier 3: Persona (high-level user profile)
 *
 * Delegates storage/retrieval to a pluggable [MemoryProvider]:
 * - EmbeddedMemoryProvider (SQLite, default)
 * - HonchoMemoryProvider (external Honcho service)
 * - TencentMemoryProvider (TencentDB Agent Memory)
 * - FallbackMemoryProvider (primary + fallback chain)
 */
class MemoryLayer(
    private val database: dev.promethe.db.PrometheDatabaseApi,
    private val llmAdapter: KoogLlmAdapter,
    private val provider: MemoryProvider,
) {
    private val logger = Log.create("MemoryLayer")
    private val inferenceService = LlmInferenceService(llmAdapter)

    /**
     * Extract facts from a completed session's messages.
     * Called after executeLoop completes successfully.
     */
    suspend fun extractFacts(sessionId: String): List<MemoryFact> {
        val messages = database.getMessagesForSession(sessionId)
        if (messages.size < 4) return emptyList()

        val conversation = messages.joinToString("\n") { "[${it.role}] ${it.content}" }

        val extractionInstruction = """Analyze the supplied conversation and extract important FACTS about the user.

Focus on:
- User preferences (language, tools, frameworks, coding style)
- Project context (project names, architecture decisions, tech stack)
- Personal info the user shared (name, role, timezone)
- Technical decisions made

Return a JSON array of objects with "category" and "fact" fields.
Categories: "preference", "project", "personal", "technical"
Return ONLY the JSON array, nothing else. If no facts, return []."""

        return try {
            val response =
                inferenceService.complete(
                    systemInstruction = extractionInstruction,
                    messages = listOf("user" to "CONVERSATION:\n${conversation.take(4000)}"),
                ).content
            parseFacts(response, sessionId)
        } catch (e: Exception) {
            logger.warn(e) { "Fact extraction failed for session $sessionId" }
            emptyList()
        }
    }

    /**
     * Retrieve relevant facts for a given query via the memory provider.
     */
    suspend fun recallFacts(
        query: String,
        limit: Int = 10,
    ): List<MemoryFact> = provider.recallFacts(query, limit)

    /**
     * Get all stored facts.
     */
    suspend fun getAllFacts(): List<MemoryFact> = provider.getAllFacts()

    /**
     * Store a fact via the memory provider.
     */
    suspend fun storeFact(fact: MemoryFact) {
        provider.storeFact(fact)
    }

    /**
     * Build a memory context block for the system prompt.
     * Queries relevant facts and formats them by category.
     */
    suspend fun buildMemoryContext(query: String): String {
        val facts = recallFacts(query)
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

    /** The active memory provider name (for logging/UI). */
    val providerName: String get() = provider.name

    /**
     * Delete a specific fact by ID.
     */
    suspend fun deleteFact(id: String) {
        provider.deleteFact(id)
    }

    /**
     * Update a specific fact by ID. Returns true if found and updated.
     */
    suspend fun updateFact(
        id: String,
        category: String? = null,
        content: String? = null,
        confidence: Float? = null,
    ): Boolean = provider.updateFact(id, category, content, confidence)

    // ── Internal ──

    private suspend fun parseFacts(
        response: String,
        sessionId: String,
    ): List<MemoryFact> {
        val jsonStart = response.indexOf('[')
        val jsonEnd = response.lastIndexOf(']')
        if (jsonStart == -1 || jsonEnd == -1) return emptyList()

        return try {
            val jsonStr = response.substring(jsonStart, jsonEnd + 1)
            val elements = Json.parseToJsonElement(jsonStr).jsonArray
            val facts =
                elements.map { element ->
                    val obj = element.jsonObject
                    MemoryFact(
                        category = obj["category"]?.jsonPrimitive?.content ?: "general",
                        content = obj["fact"]?.jsonPrimitive?.content ?: "",
                        sourceSession = sessionId,
                        tier = MemoryTier.ATOMIC,
                    )
                }
            // Persist extracted facts via provider
            facts.forEach { fact -> storeFact(fact) }
            facts
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse facts JSON from LLM response" }
            emptyList()
        }
    }
}
