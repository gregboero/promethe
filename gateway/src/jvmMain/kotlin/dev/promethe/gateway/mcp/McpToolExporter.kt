package dev.promethe.gateway.mcp

import ai.koog.agents.core.tools.ToolBase
import dev.promethe.core.SecureToolExecutor
import dev.promethe.core.ToolApprovalPolicy
import dev.promethe.core.ToolCallOrigin
import dev.promethe.core.ToolExecutionRequest
import dev.promethe.core.ToolRegistry
import dev.promethe.api.PrometheVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * McpToolExporter — converts Promethe's ToolRegistry into MCP protocol responses.
 *
 * This is the shared dispatch layer used by both the HTTP MCP endpoint
 * and the Stdio MCP server mode. It translates JSON-RPC 2.0 method calls
 * into ToolRegistry operations and returns well-formed JSON-RPC responses.
 *
 * Supported methods:
 *   - `initialize` → server capabilities and info
 *   - `notifications/initialized` → no-op acknowledgment
 *   - `tools/list` → enumerate all registered tools
 *   - `tools/call` → execute a specific tool
 *   - `shutdown` → graceful shutdown signal
 */
class McpToolExporter(
    private val secureToolExecutor: SecureToolExecutor? = null,
    private val origin: ToolCallOrigin = ToolCallOrigin.MCP_HTTP,
    private val exposeApprovalRequiredTools: Boolean = secureToolExecutor != null,
    private val taskManager: McpTaskManager = McpTaskManager(),
) {
    companion object {
        const val PROTOCOL_VERSION = "2025-11-05"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Dispatch a parsed JSON-RPC 2.0 request object and return the response.
     * Returns `null` for notifications (requests without an `id` field).
     */
    suspend fun dispatch(
        request: JsonObject,
        sessionId: String = "unknown",
    ): JsonObject? {
        val id: JsonElement = request["id"] ?: return handleNotification(request)
        val method = request["method"]?.jsonPrimitive?.content ?: ""
        val params = request["params"]?.jsonObject ?: JsonObject(emptyMap())

        return try {
            val result = when (method) {
                "initialize" -> handleInitialize()
                "tools/list" -> handleToolsList()
                "tools/call" -> handleToolsCall(params, sessionId)
                "tasks/get" -> taskManager.handleTasksGet(params)
                "tasks/cancel" -> taskManager.handleTasksCancel(params)
                "shutdown" -> handleShutdown()
                else -> throw McpMethodNotFound(method)
            }
            buildJsonRpcResult(id, result)
        } catch (e: McpMethodNotFound) {
            buildJsonRpcError(id, code = -32601, message = "Method not found: ${e.method}")
        } catch (e: McpInvalidParams) {
            buildJsonRpcError(id, code = -32602, message = e.message ?: "Invalid params")
        } catch (e: Exception) {
            buildJsonRpcError(id, code = -32603, message = e.message ?: "Internal error")
        }
    }

    // ── Method handlers ──────────────────────────────────────

    private fun handleInitialize(): JsonObject =
        buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") {
                putJsonObject("tools") {
                    put("listChanged", false)
                }
                putJsonObject("tasks") {}
            }
            putJsonObject("serverInfo") {
                put("name", "promethe")
                put("version", PrometheVersion.CURRENT)
            }
        }

    private suspend fun handleToolsList(): JsonObject {
        val tools =
            ToolRegistry.listTools().filter { tool ->
                exposeApprovalRequiredTools || tool.name !in ToolApprovalPolicy.dangerousTools
            }
        return buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    tools.forEach { tool ->
                        add(toolToMcpJson(tool))
                    }
                },
            )
        }
    }

    private suspend fun handleToolsCall(
        params: JsonObject,
        sessionId: String,
    ): JsonObject {
        val toolName = params["name"]?.jsonPrimitive?.content
            ?: throw McpInvalidParams("Missing required param: 'name'")
        val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())

        ToolRegistry.getTool(toolName) ?: throw McpInvalidParams("Unknown tool: $toolName")
        if (!isToolExposed(toolName, arguments)) {
            throw McpInvalidParams("Tool '$toolName' requires an interactive approval channel")
        }

        val executor = secureToolExecutor
            ?: throw McpInvalidParams("Secure tool execution is unavailable")
        val resultText =
            executor.execute(
                ToolExecutionRequest(
                    toolName = toolName,
                    arguments = arguments,
                    sessionId = sessionId,
                    origin = origin,
                ),
            )

        return buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", resultText)
                        },
                    )
                },
            )
        }
    }

    private fun handleShutdown(): JsonObject = buildJsonObject {}

    private fun handleNotification(request: JsonObject): JsonObject? {
        // Notifications like "notifications/initialized" need no response
        return null
    }

    // ── Tool conversion ──────────────────────────────────────

    private fun toolToMcpJson(tool: ToolBase<*, *>): JsonObject =
        buildJsonObject {
            val desc = tool.descriptor
            put("name", desc.name)
            put("description", desc.description)
            putJsonObject("inputSchema") {
                put("type", "object")
                val allParams = desc.requiredParameters + desc.optionalParameters
                putJsonObject("properties") {
                    allParams.forEach { param ->
                        putJsonObject(param.name) {
                            put("type", param.type.toString().lowercase())
                            put("description", param.description)
                        }
                    }
                }
                if (desc.requiredParameters.isNotEmpty()) {
                    put(
                        "required",
                        buildJsonArray {
                            desc.requiredParameters.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) }
                        },
                    )
                }
            }
        }

    private fun isToolExposed(
        toolName: String,
        arguments: JsonObject,
    ): Boolean = exposeApprovalRequiredTools || !ToolApprovalPolicy.requiresMandatoryApproval(toolName, arguments)

    // ── JSON-RPC 2.0 response builders ───────────────────────

    private fun buildJsonRpcResult(
        id: JsonElement,
        result: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

    private fun buildJsonRpcError(
        id: JsonElement,
        code: Int,
        message: String,
    ): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }

    // ── Exceptions ───────────────────────────────────────────

    private class McpMethodNotFound(
        val method: String,
    ) : Exception("Method not found: $method")

    private class McpInvalidParams(
        message: String,
    ) : Exception(message)
}
