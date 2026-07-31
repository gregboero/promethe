package dev.promethe.core

import dev.promethe.core.Log

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ── Args ────────────────────────────────────────────────────────

@Serializable
data class McpToolCallArgs(
    @property:LLMDescription("JSON string with the arguments to pass to the MCP tool.")
    val arguments: String = "{}",
)

// ── McpProxyTool ────────────────────────────────────────────────

/**
 * McpProxyTool — a Koog-compatible tool that proxies calls to an MCP server.
 *
 * When the LLM calls this tool, it:
 * 1. Parses the arguments as JSON
 * 2. Routes through McpBridge to the correct MCP server
 * 3. Returns the MCP server's response
 *
 * Each MCP tool gets its own McpProxyTool instance, named "mcp_{server}_{tool}".
 */
class McpProxyTool(
    name: String,
    description: String,
    private val serverId: String,
    private val originalToolName: String,
    private val inputSchema: JsonObject?,
    private val bridge: McpBridge,
) : SimpleTool<McpToolCallArgs>(
        argsType = typeToken<McpToolCallArgs>(),
        name = name,
        description = description,
    ) {
    private val logger = Log.create("McpProxyTool")

    override suspend fun execute(args: McpToolCallArgs): String {
        val jsonArgs =
            try {
                Json.parseToJsonElement(args.arguments).jsonObject
            } catch (e: Exception) {
                logger.debug(e) { "Failed to parse MCP arguments as JSON, using empty object" }
                buildJsonObject { }
            }

        return bridge.executeToolCall(
            serverId = serverId,
            toolName = originalToolName,
            arguments = jsonArgs,
        )
    }
}
