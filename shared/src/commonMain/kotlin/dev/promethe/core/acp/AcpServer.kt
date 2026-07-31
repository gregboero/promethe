package dev.promethe.core.acp

import dev.promethe.core.ToolRegistry

/**
 * ACP Server — exposes Prométhé as an ACP-compatible agent.
 *
 * Responsibility: Build AcpAgentCard from runtime state (tools, capabilities).
 *
 * Execution is handled by A2AInternalClient in the gateway module,
 * ensuring all entry points (ACP, webhooks, chat, goals) go through
 * the same unified pipeline.
 */
class AcpServer(
    private val baseUrl: String = "http://localhost:8080",
) {
    /**
     * Build a dynamic AcpAgentCard from current runtime state.
     * Tools registered in ToolRegistry become ACP capabilities.
     */
    fun buildAgentCard(): AcpAgentCard {
        val capabilities = mutableListOf<AcpCapability>()

        // Core capabilities
        capabilities.add(
            AcpCapability(
                id = "chat",
                name = "General Chat",
                description = "Conversational AI with multi-turn context",
                tags = listOf("chat", "conversation"),
            ),
        )
        capabilities.add(
            AcpCapability(
                id = "execute",
                name = "Task Execution",
                description = "Execute complex tasks using available tools",
                tags = listOf("task", "execution", "tools"),
            ),
        )

        // Map registered tools to ACP capabilities
        ToolRegistry.toolsSnapshot().forEach { tool ->
            capabilities.add(
                AcpCapability(
                    id = "tool-${tool.name}",
                    name = tool.name,
                    description = "Tool: ${tool.name}",
                    tags = listOf("tool"),
                ),
            )
        }

        return AcpAgentCard(
            id = "promethe",
            name = "Prométhé",
            description = "Autonomous AI agent with ${capabilities.size} capabilities",
            version = "1.0.0",
            url = baseUrl,
            capabilities = capabilities,
        )
    }
}
