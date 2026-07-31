package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

// ══════════════════════════════════════════════════════════════════
// ListAgentsTool — discover available agent profiles
// ══════════════════════════════════════════════════════════════════

class ListAgentsTool(
    private val database: dev.promethe.db.PrometheDatabaseApi,
) : SimpleTool<ListAgentsArgs>(
        argsType = typeToken<ListAgentsArgs>(),
        name = "list_agents",
        description = """List all available agent profiles that can be used with delegate_task.
            |Returns each agent's ID, name, provider, model, persona summary, whether it's a system agent, and its allowed tools.
            |Use this before delegate_task to discover which agent is best suited for a sub-task.
        """.trimMargin(),
    ) {
    override suspend fun execute(args: ListAgentsArgs): String {
        val allProfiles = database.getAllAgentProfiles()
        val profiles = when (args.filter.lowercase().trim()) {
            "system" -> allProfiles.filter { it.isSystem }
            "custom" -> allProfiles.filter { !it.isSystem && !it.ephemeral }
            "ephemeral" -> allProfiles.filter { it.ephemeral }
            "persistent", "permanent" -> allProfiles.filter { !it.ephemeral }
            else -> allProfiles
        }
        if (profiles.isEmpty()) return "[No agent profiles found${if (args.filter.isNotBlank()) " matching filter '${args.filter}'" else ""}]"

        return buildString {
            appendLine("Available agents (${profiles.size}):")
            appendLine("─".repeat(60))
            for (p in profiles) {
                val badge = when {
                    p.isSystem -> "🔒 system"
                    p.ephemeral -> "⏳ ephemeral"
                    else -> "custom"
                }
                val persona = if (p.systemPrompt.isNotBlank()) {
                    p.systemPrompt.take(80).replace("\n", " ") + if (p.systemPrompt.length > 80) "…" else ""
                } else {
                    "general-purpose (no persona restriction)"
                }
                val tools = if (p.tools.isBlank() || p.tools == "[]") "all tools" else p.tools
                val provider = if (p.provider.isBlank()) "system default" else "${p.provider}/${p.model}"

                appendLine("• ${p.id} [$badge] — ${p.name}")
                appendLine("  Provider: $provider | Temp: ${p.temperature} | MaxIter: ${p.maxIterations}")
                appendLine("  Persona: $persona")
                appendLine("  Tools: $tools")
                appendLine()
            }
        }
    }
}

@Serializable
data class ListAgentsArgs(
    @property:LLMDescription("Optional filter: 'system', 'custom', 'ephemeral', 'persistent', or empty for all.")
    val filter: String = "",
)

// ══════════════════════════════════════════════════════════════════
// CreateAgentTool — create a custom agent profile dynamically
// ══════════════════════════════════════════════════════════════════

class CreateAgentTool(
    private val database: dev.promethe.db.PrometheDatabaseApi,
    private val onAgentCreated: (suspend (dev.promethe.db.AgentProfileRow) -> Unit)? = null,
) : SimpleTool<CreateAgentArgs>(
        argsType = typeToken<CreateAgentArgs>(),
        name = "create_agent",
        description = """Create a new agent profile that can be used with delegate_task.
            |The agent will have its own persona (system prompt), provider/model preferences, and tool restrictions.
            |
            |EPHEMERAL MODE: Set ephemeral=true for temporary task-specific agents.
            |These are auto-created for a task and can be promoted to permanent later.
            |
            |INHERITANCE: Set parentProfileId to inherit settings from an existing agent.
            |The new agent will copy the parent's provider/model/tools/temperature,
            |then override only the fields you explicitly set.
            |
            |WORKFLOW:
            |1. list_agents → find a suitable parent (or use "promethe" as base)
            |2. create_agent(ephemeral=true, parentProfileId="researcher", systemPrompt="...")
            |3. delegate_task(profileId="<new-id>", task="...")
            |4. If the user wants to keep it: promote_agent(id="<new-id>")
            |5. If not: it will be auto-cleaned or call delete it
        """.trimMargin(),
    ) {
    override suspend fun execute(args: CreateAgentArgs): String {
        // Validate ID
        var id = args.id.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9_-]"), "").trim('-')
        if (id.isBlank()) return "[ERROR] Agent ID cannot be blank"
        if (id.length < 2) return "[ERROR] Agent ID must be at least 2 characters"

        if (args.ephemeral) {
            // Append a unique suffix to prevent collisions for ephemeral sub-agents
            val uniqueSuffix = java.util.UUID.randomUUID().toString().take(8)
            id = "$id-$uniqueSuffix"
        } else {
            // For non-ephemeral agents, check for duplicates
            val existing = database.getAgentProfile(id)
            if (existing != null) return "[ERROR] Agent '$id' already exists. Use a different ID."
        }

        // Resolve parent profile for inheritance
        val parent = if (args.parentProfileId.isNotBlank()) {
            database.getAgentProfile(args.parentProfileId)
                ?: return "[ERROR] Parent agent '${args.parentProfileId}' not found."
        } else {
            null
        }

        // Inherit from parent, override with explicit values
        val finalProvider = args.provider.ifBlank { parent?.provider ?: "" }
        val finalModel = args.model.ifBlank { parent?.model ?: "" }
        val finalTools = args.tools.ifBlank { parent?.tools ?: "" }
        val finalTemp = if (args.temperature >= 0.0) args.temperature else (parent?.temperature ?: 0.2)
        val finalMaxIter = if (args.maxIterations > 0) args.maxIterations else (parent?.maxIterations ?: 10)
        val finalSystemPrompt = args.systemPrompt.ifBlank { parent?.systemPrompt ?: "" }
        val finalReasoning = if (args.reasoningEffort.isNotBlank()) {
            try {
                dev.promethe.api.ReasoningEffort.valueOf(args.reasoningEffort.uppercase())
            } catch (e: Exception) {
                dev.promethe.api.ReasoningEffort.AUTO
            }
        } else {
            parent?.reasoningEffort ?: dev.promethe.api.ReasoningEffort.AUTO
        }

        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()

        // Create the profile row
        val profileRow = dev.promethe.db.AgentProfileRow(
            id = id,
            name = args.name.ifBlank { id.replaceFirstChar { c -> c.uppercase() } },
            provider = finalProvider,
            model = finalModel,
            systemPrompt = finalSystemPrompt,
            tools = finalTools,
            maxIterations = finalMaxIter.coerceIn(1, 50),
            temperature = finalTemp.coerceIn(0.0, 2.0),
            reasoningEffort = finalReasoning,
            isSystem = false,
            ephemeral = args.ephemeral,
            createdAt = now,
            updatedAt = now,
        )

        // Persist in DB
        database.insertAgentProfile(profileRow)

        // Hot-register in A2A registry (no restart needed!)
        try {
            onAgentCreated?.invoke(profileRow)
        } catch (e: Exception) {
            // Non-fatal: agent is in DB but not yet in A2A registry until next restart
            Log.create("CreateAgentTool").warn { "A2A hot-registration failed for '$id': ${e.message}" }
        }

        val mode = if (args.ephemeral) "⏳ EPHEMERAL" else "PERMANENT"
        val inherited = if (parent != null) " (inherited from '${args.parentProfileId}')" else ""
        val a2aStatus = if (onAgentCreated != null) "✅ A2A registered" else "⚠️ A2A pending (restart)"

        return buildString {
            appendLine("[Agent Created — $mode]$inherited")
            appendLine("• ID: $id")
            appendLine("• Name: ${args.name.ifBlank { id }}")
            appendLine("• Provider: ${finalProvider.ifBlank { "system default" }}")
            appendLine("• Model: ${finalModel.ifBlank { "system default" }}")
            appendLine("• Persona: ${finalSystemPrompt.take(100).ifBlank { "(none)" }}")
            appendLine("• Tools: ${finalTools.ifBlank { "all" }}")
            appendLine("• Max iterations: $finalMaxIter")
            appendLine("• Status: $a2aStatus")
            appendLine()
            appendLine("Use: delegate_task(profileId=\"$id\", task=\"...\")")
            if (args.ephemeral) {
                appendLine("To keep permanently: promote_agent(id=\"$id\")")
            }
        }
    }
}

@Serializable
data class CreateAgentArgs(
    @property:LLMDescription("Unique ID for the agent (lowercase, dashes allowed). e.g., 'code-reviewer', 'translator-fr'.")
    val id: String,
    @property:LLMDescription("Human-readable name for the agent. e.g., 'Code Reviewer', 'French Translator'. Empty = auto from ID.")
    val name: String = "",
    @property:LLMDescription("LLM provider key. Empty = inherit from parent or system default. e.g., 'openai', 'anthropic', 'google'.")
    val provider: String = "",
    @property:LLMDescription("Model name. Empty = inherit from parent or system default. e.g., 'gpt-4o', 'claude-sonnet-4-20250514'.")
    val model: String = "",
    @property:LLMDescription("System prompt / persona for this agent. Defines its behavior and expertise. Empty = inherit from parent.")
    val systemPrompt: String = "",
    @property:LLMDescription("Comma-separated list of allowed tool names. Empty = inherit from parent or allow ALL tools.")
    val tools: String = "",
    @property:LLMDescription("Maximum number of reasoning iterations before forcing a response. 0 = inherit from parent.")
    val maxIterations: Int = 0,
    @property:LLMDescription("LLM temperature (0.0 = deterministic, 2.0 = very creative). Negative = inherit from parent.")
    val temperature: Double = -1.0,
    @property:LLMDescription("Reasoning effort for reasoning models. One of: 'AUTO', 'LOW', 'MEDIUM', 'HIGH'. Empty = inherit from parent.")
    val reasoningEffort: String = "",
    @property:LLMDescription("If true, creates a temporary agent for this task only. Can be promoted later with promote_agent.")
    val ephemeral: Boolean = false,
    @property:LLMDescription(
        "ID of an existing agent to inherit settings from. The new agent copies its config, then your overrides apply.",
    )
    val parentProfileId: String = "",
)

// ══════════════════════════════════════════════════════════════════
// PromoteAgentTool — convert ephemeral agent to permanent
// ══════════════════════════════════════════════════════════════════

class PromoteAgentTool(
    private val database: dev.promethe.db.PrometheDatabaseApi,
) : SimpleTool<PromoteAgentArgs>(
        argsType = typeToken<PromoteAgentArgs>(),
        name = "promote_agent",
        description = """Promote an ephemeral (temporary) agent to a permanent agent.
            |Use this when the user wants to keep a task-specific agent for future use.
            |The agent's ephemeral flag will be set to false, making it appear in the regular agent list.
        """.trimMargin(),
    ) {
    override suspend fun execute(args: PromoteAgentArgs): String {
        val profile = database.getAgentProfile(args.id)
            ?: return "[ERROR] Agent '${args.id}' not found."

        if (!profile.ephemeral) {
            return "[INFO] Agent '${args.id}' is already a permanent agent. Nothing to do."
        }

        // Update: set ephemeral = false, optionally rename
        val newName = args.newName.ifBlank { profile.name }
        database.insertAgentProfile(
            profile.copy(
                name = newName,
                ephemeral = false,
                updatedAt = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            ),
        )

        return buildString {
            appendLine("[Agent Promoted ✅]")
            appendLine("• ${args.id} is now a PERMANENT agent")
            appendLine("• Name: $newName")
            appendLine("• It will appear in the regular agent list and can be selected in the UI.")
        }
    }
}

@Serializable
data class PromoteAgentArgs(
    @property:LLMDescription("The ID of the ephemeral agent to promote to permanent.")
    val id: String,
    @property:LLMDescription("Optional new name for the promoted agent. Leave empty to keep the current name.")
    val newName: String = "",
)

// ══════════════════════════════════════════════════════════════════
// CleanupEphemeralAgentsTool — remove all expired ephemeral agents
// ══════════════════════════════════════════════════════════════════

class CleanupEphemeralAgentsTool(
    private val database: dev.promethe.db.PrometheDatabaseApi,
) : SimpleTool<CleanupEphemeralArgs>(
        argsType = typeToken<CleanupEphemeralArgs>(),
        name = "cleanup_ephemeral_agents",
        description = """Remove all ephemeral (temporary) agents that were created more than N hours ago.
            |Default: removes agents older than 24 hours.
            |System and permanent agents are never affected.
        """.trimMargin(),
    ) {
    override suspend fun execute(args: CleanupEphemeralArgs): String {
        val cutoff = kotlin.time.Clock.System.now().toEpochMilliseconds() - (args.olderThanHours * 3600_000L)
        val allProfiles = database.getAllAgentProfiles()
        val toDelete = allProfiles.filter { it.ephemeral && it.createdAt < cutoff }

        if (toDelete.isEmpty()) {
            return "[INFO] No expired ephemeral agents to clean up."
        }

        var deleted = 0
        for (profile in toDelete) {
            database.deleteAgentProfile(profile.id)
            deleted++
        }

        return "[Cleanup Complete] Removed $deleted ephemeral agent(s) older than ${args.olderThanHours}h: ${toDelete.map { it.id }}"
    }
}

@Serializable
data class CleanupEphemeralArgs(
    @property:LLMDescription("Remove ephemeral agents created more than this many hours ago. Default: 24.")
    val olderThanHours: Int = 24,
)
