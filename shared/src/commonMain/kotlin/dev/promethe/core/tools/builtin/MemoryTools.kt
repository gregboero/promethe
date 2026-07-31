package dev.promethe.core.tools.builtin

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.MemoryLayer
import dev.promethe.core.memory.MemoryFact
import dev.promethe.core.memory.MemoryTier
import kotlinx.serialization.Serializable

// ── memory_save ─────────────────────────────────────────────

@Serializable
data class MemorySaveArgs(
    @property:LLMDescription("The fact or information to remember.")
    val content: String,
    @property:LLMDescription("Category: 'preference', 'project', 'personal', or 'technical'.")
    val category: String = "general",
)

/**
 * Allows the agent to explicitly save a fact to persistent memory.
 * This memory persists across sessions and conversations.
 */
class MemorySaveTool(
    private val memoryLayer: MemoryLayer,
) : SimpleTool<MemorySaveArgs>(
        argsType = typeToken<MemorySaveArgs>(),
        name = "memory_save",
        description =
            """Save a fact or piece of information to persistent memory.
        |Use this to remember user preferences, project context, technical decisions,
        |or any important information that should persist across conversations.
        |Categories: preference, project, personal, technical, general.
            """.trimMargin(),
    ) {
    override suspend fun execute(args: MemorySaveArgs): String {
        val fact =
            MemoryFact(
                category = args.category,
                content = args.content,
                tier = MemoryTier.ATOMIC,
                confidence = 1.0f,
            )
        memoryLayer.storeFact(fact)
        return "[Memory] Saved: '${args.content.take(80)}...' (category: ${args.category})"
    }
}

// ── memory_search ───────────────────────────────────────────

@Serializable
data class MemorySearchArgs(
    @property:LLMDescription("Search query to find relevant memories.")
    val query: String,
    @property:LLMDescription("Maximum number of results to return.")
    val limit: Int = 10,
)

/**
 * Search persistent memory for relevant facts matching a query.
 */
class MemorySearchTool(
    private val memoryLayer: MemoryLayer,
) : SimpleTool<MemorySearchArgs>(
        argsType = typeToken<MemorySearchArgs>(),
        name = "memory_search",
        description =
            """Search persistent memory for facts related to a query.
        |Use this to recall user preferences, past decisions, project context,
        |or any previously stored information.
            """.trimMargin(),
    ) {
    override suspend fun execute(args: MemorySearchArgs): String {
        val facts = memoryLayer.recallFacts(args.query, args.limit)
        if (facts.isEmpty()) {
            return "[Memory] No facts found matching '${args.query}'"
        }
        return buildString {
            appendLine("[Memory] Found ${facts.size} fact(s):")
            facts.forEachIndexed { i, fact ->
                appendLine("  ${i + 1}. [${fact.category}] ${fact.content}")
            }
        }
    }
}

// ── memory_forget ───────────────────────────────────────────

@Serializable
data class MemoryForgetArgs(
    @property:LLMDescription("The ID of the memory fact to delete, or 'all' to clear everything.")
    val factId: String,
)

/**
 * Delete a specific memory fact or clear all memory.
 */
class MemoryForgetTool(
    private val memoryLayer: MemoryLayer,
) : SimpleTool<MemoryForgetArgs>(
        argsType = typeToken<MemoryForgetArgs>(),
        name = "memory_forget",
        description =
            """Delete a specific fact from persistent memory by its ID.
        |Use this when the user asks to forget something or when information is outdated.
            """.trimMargin(),
    ) {
    override suspend fun execute(args: MemoryForgetArgs): String =
        if (args.factId == "all") {
            val allFacts = memoryLayer.getAllFacts()
            allFacts.forEach { memoryLayer.deleteFact(it.id) }
            "[Memory] Cleared all ${allFacts.size} facts from memory."
        } else {
            memoryLayer.deleteFact(args.factId)
            "[Memory] Deleted fact: ${args.factId}"
        }
}

// ── memory_list ─────────────────────────────────────────────

@Serializable
data class MemoryListArgs(
    @property:LLMDescription("Optional category filter: 'preference', 'project', 'personal', 'technical', or empty for all.")
    val category: String = "",
)

/**
 * List all stored memory facts, optionally filtered by category.
 */
class MemoryListTool(
    private val memoryLayer: MemoryLayer,
) : SimpleTool<MemoryListArgs>(
        argsType = typeToken<MemoryListArgs>(),
        name = "memory_list",
        description =
            """List all facts stored in persistent memory, optionally filtered by category.
        |Use to review what the agent remembers about the user and their projects.
            """.trimMargin(),
    ) {
    override suspend fun execute(args: MemoryListArgs): String {
        val allFacts = memoryLayer.getAllFacts()
        val filtered =
            if (args.category.isNotBlank()) {
                allFacts.filter { it.category == args.category }
            } else {
                allFacts
            }

        if (filtered.isEmpty()) {
            val suffix = if (args.category.isNotBlank()) " in category '${args.category}'" else ""
            return "[Memory] No facts stored$suffix."
        }

        return buildString {
            appendLine("[Memory] ${filtered.size} fact(s)${if (args.category.isNotBlank()) " (${args.category})" else ""}:")
            val grouped = filtered.groupBy { it.category }
            grouped.forEach { (cat, facts) ->
                appendLine("  ## $cat")
                facts.forEach { f ->
                    appendLine("    - [${f.id.take(8)}] ${f.content}")
                }
            }
        }
    }
}
