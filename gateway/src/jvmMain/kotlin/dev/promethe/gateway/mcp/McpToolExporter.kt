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
import dev.promethe.core.ToolInputRequestBridge
import dev.promethe.core.ToolInputRequestContext
import dev.promethe.core.ToolJsonSchemaGenerator
import dev.promethe.core.ToolRegistry
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
    private val taskManager: McpTaskManager? = null,
    private val roundTripManager: McpRoundTripManager? = null,
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
        val rawId = request["id"]
        val responseId = rawId?.takeIf { it is JsonPrimitive || it is JsonNull } ?: JsonNull
        val jsonRpc = request["jsonrpc"] as? JsonPrimitive
        val methodValue = request["method"] as? JsonPrimitive
        if (jsonRpc?.content != "2.0" || methodValue?.isString != true || methodValue.content.isBlank()) {
            return buildJsonRpcError(responseId, code = -32600, message = "Invalid Request")
        }
        if (rawId != null && rawId !is JsonPrimitive && rawId !is JsonNull) {
            return buildJsonRpcError(JsonNull, code = -32600, message = "Invalid Request")
        }
        val id: JsonElement = rawId ?: return handleNotification(request)
        val method = methodValue.content
        val rawParams = request["params"]
        if (rawParams != null && rawParams !is JsonObject) {
            return buildJsonRpcError(id, code = -32602, message = "Invalid params")
        }
        val params = rawParams ?: JsonObject(emptyMap())
        val requestedVersion = protocolVersion ?: LEGACY_PROTOCOL_VERSION
        if (requestedVersion !in McpProtocol.supportedVersions) {
            return unsupportedProtocolVersion(id, requestedVersion)
        }
        val modern = requestedVersion == PROTOCOL_VERSION

        return try {
            val result = when (method) {
                "server/discover" -> {
                    if (modern) handleDiscover() else throw McpMethodNotFound(method)
                }

                "initialize" -> {
                    if (!modern) handleInitialize() else throw McpMethodNotFound(method)
                }

                "tools/list" -> {
                    handleToolsList(modern)
                }

                "tools/call" -> {
                    handleToolsCall(
                        params = params,
                        sessionId = sessionId,
                        modern = modern,
                        clientSupportsTasks = modern && clientSupportsTasks(params),
                    )
                }

                "tasks/get" -> {
                    if (modern) {
                        modernTaskManager(method, params).handleTasksGet(params, sessionId)
                    } else {
                        throw McpMethodNotFound(method)
                    }
                }

                "tasks/update" -> {
                    if (modern) {
                        modernTaskManager(method, params).handleTasksUpdate(params, sessionId)
                    } else {
                        throw McpMethodNotFound(method)
                    }
                }

                "tasks/cancel" -> {
                    if (modern) {
                        modernTaskManager(method, params).handleTasksCancel(params, sessionId)
                    } else {
                        throw McpMethodNotFound(method)
                    }
                }

                "shutdown" -> {
                    if (!modern) handleShutdown() else throw McpMethodNotFound(method)
                }

                else -> {
                    throw McpMethodNotFound(method)
                }
            }
            buildJsonRpcResult(id, if (modern) result.withModernEnvelope() else result)
        } catch (e: McpMethodNotFound) {
            buildJsonRpcError(id, code = -32601, message = "Method not found: ${e.method}")
        } catch (e: McpInvalidParams) {
            buildJsonRpcError(id, code = -32602, message = e.message ?: "Invalid params")
        } catch (e: IllegalArgumentException) {
            buildJsonRpcError(id, code = -32602, message = e.message ?: "Invalid params")
        } catch (_: McpMissingTaskCapability) {
            buildJsonRpcError(
                id = id,
                code = -32003,
                message = "Missing required client capability",
                data = requiredTaskCapability(),
            )
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
                if (taskManager != null) {
                    putJsonObject("extensions") {
                        putJsonObject(McpProtocol.TASKS_EXTENSION) {}
                    }
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
        modern: Boolean,
        clientSupportsTasks: Boolean,
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
        val request =
            ToolExecutionRequest(
                toolName = toolName,
                arguments = arguments,
                sessionId = sessionId,
                origin = origin,
            )
        val requestState = (params["requestState"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val inputResponses = params["inputResponses"] as? JsonObject
        if (("requestState" in params && requestState == null) || ("inputResponses" in params && inputResponses == null)) {
            throw McpInvalidParams("requestState must be a string and inputResponses must be an object")
        }
        if (!modern && (requestState != null || inputResponses != null)) {
            throw McpInvalidParams("MCP multi-round trips require protocol ${McpProtocol.MODERN_VERSION}")
        }
        val clientInputMethods = clientInputMethods(params)
        val roundTrips = roundTripManager
        if (requestState != null || inputResponses != null) {
            return requireNotNull(roundTrips) { "MCP multi-round-trip execution is unavailable" }
                .executeOrResume(
                    ownerSessionId = sessionId,
                    toolName = toolName,
                    arguments = arguments,
                    clientInputMethods = clientInputMethods,
                    requestState = requestState,
                    inputResponses = inputResponses,
                ) {
                    executeTool(
                        executor = executor,
                        request = request,
                        inputBridge = ToolInputRequestBridge { requested -> requestInput(requested) },
                    )
                }
        }
        val manager = taskManager
        if (clientSupportsTasks && manager != null && manager.isTaskEligible(toolName)) {
            val task =
                manager.createTask(
                    ownerSessionId = sessionId,
                    toolName = toolName,
                    runId = request.runId,
                ) {
                    executeTool(
                        executor = executor,
                        request = request,
                        inputBridge = ToolInputRequestBridge { inputRequests -> requestInput(inputRequests) },
                    )
                }
            return manager.buildCreateTaskResult(task)
        }

        if (modern && roundTrips != null) {
            return roundTrips.executeOrResume(
                ownerSessionId = sessionId,
                toolName = toolName,
                arguments = arguments,
                clientInputMethods = clientInputMethods,
                requestState = null,
                inputResponses = null,
            ) {
                executeTool(
                    executor = executor,
                    request = request,
                    inputBridge = ToolInputRequestBridge { requested -> requestInput(requested) },
                )
            }
        }

        return executeTool(executor, request)
    }

    private suspend fun executeTool(
        executor: SecureToolExecutor,
        request: ToolExecutionRequest,
        inputBridge: ToolInputRequestBridge? = null,
    ): JsonObject {
        val resultText =
            if (inputBridge != null) {
                withContext(ToolInputRequestContext(inputBridge)) { executor.execute(request) }
            } else {
                executor.execute(request)
            }
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

    private fun modernTaskManager(
        method: String,
        params: JsonObject,
    ): McpTaskManager {
        val manager = taskManager ?: throw McpMethodNotFound(method)
        if (!clientSupportsTasks(params)) throw McpMissingTaskCapability()
        return manager
    }

    private fun clientSupportsTasks(params: JsonObject): Boolean =
        (
            (
                (params["_meta"] as? JsonObject)
                    ?.get(McpProtocol.CLIENT_CAPABILITIES_META) as? JsonObject
            )
                ?.get("extensions") as? JsonObject
        )
            ?.get(McpProtocol.TASKS_EXTENSION) is JsonObject

    private fun clientInputMethods(params: JsonObject): Set<String> {
        val capabilities =
            (params["_meta"] as? JsonObject)
                ?.get(McpProtocol.CLIENT_CAPABILITIES_META) as? JsonObject
                ?: return emptySet()
        return McpProtocol.inputMethodCapabilities
            .filterValues { capability -> capabilities[capability] is JsonObject }
            .keys
    }

    private fun requiredTaskCapability(): JsonObject =
        buildJsonObject {
            putJsonObject("requiredCapabilities") {
                putJsonObject("extensions") {
                    putJsonObject(McpProtocol.TASKS_EXTENSION) {}
                }
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
            put("inputSchema", ToolJsonSchemaGenerator.inputSchema(desc))
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

    private class McpMissingTaskCapability : Exception()
}
