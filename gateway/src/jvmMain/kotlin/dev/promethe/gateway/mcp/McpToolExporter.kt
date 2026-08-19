package dev.promethe.gateway.mcp

import ai.koog.agents.core.tools.ToolBase
import dev.promethe.api.PrometheVersion
import dev.promethe.core.McpProtocol
import dev.promethe.core.PolicyEffect
import dev.promethe.core.PolicyKernel
import dev.promethe.core.SecureToolExecutor
import dev.promethe.core.ToolApprovalPolicy
import dev.promethe.core.ToolCallOrigin
import dev.promethe.core.ToolExecutionRequest
import dev.promethe.core.ToolRegistry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * McpToolExporter — converts Promethe's ToolRegistry into MCP protocol responses.
 *
 * This is the shared dispatch layer used by both the HTTP MCP endpoint
 * and the Stdio MCP server mode. It translates JSON-RPC 2.0 method calls
 * into ToolRegistry operations and returns well-formed JSON-RPC responses.
 *
 * Supported protocol eras:
 *   - 2026-07-28 stateless requests with `server/discover`
 *   - 2025-11-25 legacy requests with `initialize`
 *
 * Shared methods:
 *   - `tools/list` → enumerate all registered tools
 *   - `tools/call` → execute a specific tool
 */
class McpToolExporter(
    private val secureToolExecutor: SecureToolExecutor? = null,
    private val origin: ToolCallOrigin = ToolCallOrigin.MCP_HTTP,
    private val exposeApprovalRequiredTools: Boolean = secureToolExecutor != null,
    private val taskManager: McpTaskManager = McpTaskManager(),
    private val policyKernel: PolicyKernel = PolicyKernel(),
) {
    companion object {
        const val PROTOCOL_VERSION = McpProtocol.MODERN_VERSION
        const val LEGACY_PROTOCOL_VERSION = McpProtocol.LEGACY_VERSION
        private const val DISCOVERY_TTL_MS = 60_000
        private const val TOOL_CATALOG_TTL_MS = 30_000
    }

    /**
     * Dispatch a parsed JSON-RPC 2.0 request object and return the response.
     * Returns `null` for notifications (requests without an `id` field).
     */
    suspend fun dispatch(
        request: JsonObject,
        sessionId: String = "unknown",
        protocolVersion: String? = McpProtocol.requestedVersion(request),
    ): JsonObject? {
        val id: JsonElement = request["id"] ?: return handleNotification(request)
        val method = (request["method"] as? JsonPrimitive)?.content.orEmpty()
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        val requestedVersion = protocolVersion ?: LEGACY_PROTOCOL_VERSION
        if (requestedVersion !in McpProtocol.supportedVersions) {
            return unsupportedProtocolVersion(id, requestedVersion)
        }
        val modern = requestedVersion == PROTOCOL_VERSION

        return try {
            val result = when (method) {
                "server/discover" -> if (modern) handleDiscover() else throw McpMethodNotFound(method)
                "initialize" -> if (!modern) handleInitialize() else throw McpMethodNotFound(method)
                "tools/list" -> handleToolsList(modern)
                "tools/call" -> handleToolsCall(params, sessionId)
                "tasks/get" -> if (!modern) taskManager.handleTasksGet(params) else throw McpMethodNotFound(method)
                "tasks/cancel" -> if (!modern) taskManager.handleTasksCancel(params) else throw McpMethodNotFound(method)
                "shutdown" -> if (!modern) handleShutdown() else throw McpMethodNotFound(method)
                else -> throw McpMethodNotFound(method)
            }
            buildJsonRpcResult(id, if (modern) result.withModernEnvelope() else result)
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
            put("protocolVersion", LEGACY_PROTOCOL_VERSION)
            putJsonObject("capabilities") {
                putJsonObject("tools") {
                    put("listChanged", false)
                }
            }
            putJsonObject("serverInfo") {
                put("name", "promethe")
                put("version", PrometheVersion.CURRENT)
            }
        }

    private fun handleDiscover(): JsonObject =
        buildJsonObject {
            putJsonArray("supportedVersions") {
                McpProtocol.supportedVersions.forEach { add(it) }
            }
            putJsonObject("capabilities") {
                putJsonObject("tools") {
                    put("listChanged", false)
                }
            }
            put("instructions", "Promethe exposes policy-controlled agent tools.")
            put("ttlMs", DISCOVERY_TTL_MS)
            put("cacheScope", "private")
        }

    private suspend fun handleToolsList(modern: Boolean): JsonObject {
        val tools =
            ToolRegistry.listTools()
                .filter { tool -> isToolExposed(tool.name, JsonObject(emptyMap())) }
                .sortedBy { tool -> tool.name }
        return buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    tools.forEach { tool ->
                        add(toolToMcpJson(tool))
                    }
                },
            )
            if (modern) {
                put("ttlMs", TOOL_CATALOG_TTL_MS)
                put("cacheScope", "private")
            }
        }
    }

    private suspend fun handleToolsCall(
        params: JsonObject,
        sessionId: String,
    ): JsonObject {
        val toolName = (params["name"] as? JsonPrimitive)?.content
            ?: throw McpInvalidParams("Missing required param: 'name'")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

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
            put("isError", false)
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
                put("\$schema", "https://json-schema.org/draft/2020-12/schema")
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
                put("additionalProperties", false)
            }
        }

    private fun isToolExposed(
        toolName: String,
        arguments: JsonObject,
    ): Boolean {
        val request =
            ToolExecutionRequest(
                toolName = toolName,
                arguments = arguments,
                origin = origin,
            )
        return when (policyKernel.evaluate(request, ToolApprovalPolicy.contractFor(toolName)).effect) {
            PolicyEffect.ALLOW -> true
            PolicyEffect.REQUIRE_APPROVAL -> exposeApprovalRequiredTools
            PolicyEffect.DENY -> false
        }
    }

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
        data: JsonObject? = null,
    ): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
                data?.let { put("data", it) }
            }
        }

    private fun unsupportedProtocolVersion(
        id: JsonElement,
        requestedVersion: String,
    ): JsonObject =
        buildJsonRpcError(
            id = id,
            code = -32022,
            message = "Unsupported protocol version",
            data =
                buildJsonObject {
                    putJsonArray("supported") {
                        McpProtocol.supportedVersions.forEach { add(it) }
                    }
                    put("requested", requestedVersion)
                },
        )

    private fun JsonObject.withModernEnvelope(): JsonObject =
        buildJsonObject {
            this@withModernEnvelope.forEach { (key, value) -> put(key, value) }
            if ("resultType" !in this@withModernEnvelope) put("resultType", "complete")
            putJsonObject("_meta") {
                putJsonObject(McpProtocol.SERVER_INFO_META) {
                    put("name", "promethe")
                    put("version", PrometheVersion.CURRENT)
                }
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
