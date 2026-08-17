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
    suspend fun extractFacts(
        sessionId: String,
        provider: String? = null,
        model: String? = null,
        memoryNamespace: String = "default",
    ): List<MemoryFact> {
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
                    provider = provider,
                    model = model,
                ).content
            parseFacts(response, sessionId, memoryNamespace)
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
        userId: String = "default",
    ): List<MemoryFact> = provider.recallFacts(query, limit, userId)

    /**
     * Get all stored facts.
     */
    suspend fun getAllFacts(userId: String = "default"): List<MemoryFact> = provider.getAllFacts(userId)

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
    suspend fun buildMemoryContext(
        query: String,
        userIds: List<String> = listOf("default"),
    ): String {
        val scopes = userIds.distinct()
        val facts =
            scopes
                .flatMap { userId -> recallFacts(query, userId = userId) }
                .distinctBy { fact -> Triple(fact.userId, fact.id, fact.content) }
        if (facts.isEmpty()) return ""

        return buildString {
            scopes.forEach { scope ->
                val scopedFacts = facts.filter { it.userId == scope }
                if (scopedFacts.isNotEmpty()) {
                    appendLine(if (scope == "default") "[User Memory]" else "[Project Memory]")
                    scopedFacts.groupBy { it.category }.forEach { (category, categoryFacts) ->
                        appendLine("## ${category.replaceFirstChar { it.uppercase() }}")
                        categoryFacts.forEach { fact -> appendLine("- ${fact.content}") }
                    }
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

    suspend fun deleteFactInNamespace(
        id: String,
        userId: String,
    ): Boolean {
        if (provider.getAllFacts(userId).none { it.id == id }) return false
        provider.deleteFact(id)
        return true
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
        memoryNamespace: String,
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
                        userId = memoryNamespace,
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
