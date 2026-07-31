package dev.promethe.gateway

import dev.promethe.core.AgentExecutionOrigin
import dev.promethe.core.AgentExecutionRequest
import dev.promethe.core.AgentExecutionPort
import dev.promethe.core.ToolCallOrigin

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Internal A2A-aligned execution bridge.
 *
 * All entry points (webhooks, goals, scheduler, ACP) should use this instead of
 * calling `agent.executeLoop()` directly. This ensures:
 *
 * 1. Session is created/resolved in the database
 * 2. Events are emitted to [AgentEventBus] for the Monitor WebSocket
 * 3. All calls go through a single code path, consistent with A2A protocol semantics
 *
 * This is NOT the full A2A JSON-RPC protocol — it's an internal helper that mirrors
 * the execution logic from [PrometheA2A.createExecutor] without the transport overhead.
 */
class A2AInternalClient(
    private val executionService: AgentExecutionPort,
) {
    /**
     * Execute a user message through the agent loop with proper A2A-aligned semantics.
     *
     * @param sessionId Unique session identifier (e.g., "telegram-12345", "slack-C1234")
     * @param text User message text
     * @param channelHint Optional channel name for logging ("telegram", "slack", etc.)
     * @return The last response from the agent, or "No response" if none produced
     */
    suspend fun execute(
        sessionId: String,
        text: String,
        channelHint: String = "internal",
        origin: ToolCallOrigin = ToolCallOrigin.AGENT,
    ): String {
        if (text.isBlank()) return "No response"
        logger.debug { "A2AInternal [$channelHint] → session=$sessionId, text=${text.take(100)}" }

        return try {
            executionService.executeToCompletion(
                AgentExecutionRequest(
                    sessionId = sessionId,
                    text = text,
                    origin = origin.toExecutionOrigin(),
                    channelHint = channelHint,
                ),
            )
        } catch (e: Exception) {
            logger.error(e) { "A2AInternal [$channelHint] execution failed for session $sessionId" }
            "Error: ${e.message}"
        }
    }

    private fun ToolCallOrigin.toExecutionOrigin(): AgentExecutionOrigin =
        when (this) {
            ToolCallOrigin.A2A -> AgentExecutionOrigin.A2A

            ToolCallOrigin.ACP -> AgentExecutionOrigin.ACP

            ToolCallOrigin.OPENAI_COMPAT -> AgentExecutionOrigin.OPENAI_COMPAT

            ToolCallOrigin.CHANNEL -> AgentExecutionOrigin.CHANNEL

            ToolCallOrigin.VOICE -> AgentExecutionOrigin.VOICE

            ToolCallOrigin.AUTONOMY -> AgentExecutionOrigin.GOAL

            ToolCallOrigin.SCHEDULER -> AgentExecutionOrigin.SCHEDULER

            ToolCallOrigin.MCP_HTTP,
            ToolCallOrigin.MCP_STDIO,
            ToolCallOrigin.AGENT,
            -> AgentExecutionOrigin.INTERNAL
        }
}
