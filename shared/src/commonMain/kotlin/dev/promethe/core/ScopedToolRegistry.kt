package dev.promethe.core

import ai.koog.agents.core.tools.ToolBase

/**
 * ScopedToolRegistry — filters tools available to a specific agent
 * based on its profile's `tools` whitelist.
 *
 * Rules:
 * - Empty whitelist → all tools are available (no restriction)
 * - Non-empty whitelist → only listed tool names are exposed
 * - Always includes "delegate_task" and "get_subtask_result" for orchestration
 *
 * This class wraps the global [ToolRegistry] and returns a filtered view.
 * It does NOT duplicate tool instances — it just filters references.
 */
class ScopedToolRegistry(
    private val allowedToolNames: List<String>,
) {
    companion object {
        /** Tools that are always available regardless of scope. */
        private val ALWAYS_ALLOWED =
            setOf(
                "delegate_task",
                "get_subtask_result",
                "execute_command",
                "artifact_read",
            )

        /**
         * Create a ScopedToolRegistry from a tool whitelist.
         */
        fun fromToolList(tools: List<String>): ScopedToolRegistry = ScopedToolRegistry(tools)

        /**
         * Create an unrestricted registry (all tools allowed).
         */
        fun unrestricted(): ScopedToolRegistry = ScopedToolRegistry(emptyList())
    }

    /** Whether this scope allows all tools (no filtering). */
    val isUnrestricted: Boolean = allowedToolNames.isEmpty()

    /** The effective whitelist including always-allowed tools. */
    private val effectiveWhitelist: Set<String> =
        if (allowedToolNames.isEmpty()) {
            emptySet() // empty = unrestricted
        } else {
            allowedToolNames.toSet() + ALWAYS_ALLOWED
        }

    /**
     * Check if a specific tool is allowed in this scope.
     */
    fun isAllowed(toolName: String): Boolean {
        if (isUnrestricted) return true
        return toolName in effectiveWhitelist
    }

    /**
     * Filter a list of tools to only those allowed in this scope.
     */
    fun filter(tools: List<ToolBase<*, *>>): List<ToolBase<*, *>> {
        if (isUnrestricted) return tools
        return tools.filter { it.name in effectiveWhitelist }
    }

    /**
     * Get all allowed tools from the global ToolRegistry.
     */
    suspend fun listTools(): List<ToolBase<*, *>> {
        val allTools = ToolRegistry.listTools()
        return filter(allTools)
    }

    /**
     * Get a specific tool if allowed, null otherwise.
     */
    suspend fun getTool(name: String): ToolBase<*, *>? {
        if (!isAllowed(name)) return null
        return ToolRegistry.getTool(name)
    }

    /**
     * Build the tools description block for the system prompt,
     * only including tools this agent is allowed to use.
     */
    suspend fun buildToolsBlock(): String {
        val tools = listTools()
        if (tools.isEmpty()) return "No tools available."

        return tools.joinToString("\n\n") { tool ->
            val desc = tool.descriptor
            val params =
                (desc.requiredParameters + desc.optionalParameters)
                    .joinToString(", ") { p -> "${p.name}: ${p.type}" }
            "- **${desc.name}**: ${desc.description}\n  Parameters: ($params)"
        }
    }

    override fun toString(): String =
        if (isUnrestricted) {
            "ScopedToolRegistry(unrestricted)"
        } else {
            "ScopedToolRegistry(allowed=${effectiveWhitelist.size} tools: ${effectiveWhitelist.take(5)}...)"
        }
}
