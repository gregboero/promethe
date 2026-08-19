package dev.promethe.core

import dev.promethe.api.PrometheVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dual-era MCP Streamable HTTP client.
 *
 * Modern servers use the stateless 2026-07-28 envelope. A legacy initialize
 * handshake is attempted only when the modern discovery probe identifies a
 * pre-2026 endpoint.
 */
class McpStreamableHttpTransport(
    private val baseUrl: String,
    private val customHeaders: Map<String, String> = emptyMap(),
    private val outboundClient: SecureJvmOutboundHttpClient =
        PinnedJvmOutboundHttpFetcher(JvmOutboundUrlPolicy()),
    private val inputRequestHandlers: Map<String, McpInputRequestHandler> = emptyMap(),
    private val maxInputRequiredRounds: Int = DEFAULT_MAX_INPUT_REQUIRED_ROUNDS,
    private val maxInputRequestsPerRound: Int = DEFAULT_MAX_INPUT_REQUESTS_PER_ROUND,
    private val maxTaskPolls: Int = DEFAULT_MAX_TASK_POLLS,
    private val taskPollDelay: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(maxInputRequiredRounds > 0) { "maxInputRequiredRounds must be positive" }
        require(maxInputRequestsPerRound > 0) { "maxInputRequestsPerRound must be positive" }
        require(maxTaskPolls > 0) { "maxTaskPolls must be positive" }
        require(inputRequestHandlers.keys.all { it in SUPPORTED_INPUT_REQUEST_METHODS }) {
            "Unsupported MCP input request handler"
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val nextId = AtomicInteger(1)
    private var era = ProtocolEra.UNKNOWN

    suspend fun initialize(): JsonObject {
        val discovery =
            postRequest(
                method = "server/discover",
                params = buildJsonObject {},
                protocolVersion = McpProtocol.MODERN_VERSION,
                modern = true,
            )
        if (discovery.isSuccess) {
            val result = discovery.resultOrThrow()
            if (result.resultType() != McpProtocol.RESULT_COMPLETE) {
                throw RuntimeException("MCP server/discover must return a complete result")
            }
            val supportedVersions = result["supportedVersions"] as? JsonArray
                ?: throw RuntimeException("MCP server/discover is missing supportedVersions")
            if (supportedVersions.none { it == JsonPrimitive(McpProtocol.MODERN_VERSION) }) {
                throw RuntimeException("MCP server/discover did not confirm ${McpProtocol.MODERN_VERSION}")
            }
            if (result["capabilities"] !is JsonObject) {
                throw RuntimeException("MCP server/discover is missing capabilities")
            }
            era = ProtocolEra.MODERN
            return result
        }
        if (!discovery.permitsLegacyFallback()) {
            throw discovery.toException()
        }

        era = ProtocolEra.LEGACY
        return sendLegacyRequest(
            "initialize",
            buildJsonObject {
                put("protocolVersion", McpProtocol.LEGACY_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "promethe")
                    put("version", PrometheVersion.CURRENT)
                }
            },
        )
    }

    suspend fun listTools(): List<McpBridge.McpToolInfo> {
        val response = sendRequest("tools/list", buildJsonObject {})
        val tools = response["tools"] as? JsonArray
            ?: throw RuntimeException("MCP tools/list response is missing tools")
        return tools.map { tool ->
            val obj = tool as? JsonObject ?: throw RuntimeException("MCP tool entry must be an object")
            McpBridge.McpToolInfo(
                serverId = "",
                toolName =
                    (obj["name"] as? JsonPrimitive)?.content
                        ?.takeIf { it.isNotBlank() }
                        ?: throw RuntimeException("MCP tool entry is missing name"),
                description = obj["description"]?.jsonPrimitive?.content ?: "",
                inputSchema = obj["inputSchema"] as? JsonObject
                    ?: throw RuntimeException("MCP tool entry is missing inputSchema"),
            )
        }
    }

    suspend fun callTool(
        name: String,
        arguments: JsonObject,
    ): String {
        val response =
            sendRequest(
                "tools/call",
                buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                },
            )
        val content = response["content"] as? JsonArray
            ?: throw RuntimeException("MCP tools/call response is missing content")
        return content
            .firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
            ?: response.toString()
    }

    fun close() {
        // The pinned client owns no persistent connection pool.
    }

    private suspend fun sendRequest(
        method: String,
        params: JsonObject,
    ): JsonObject =
        when (era) {
            ProtocolEra.MODERN -> {
                sendModernRequest(method, params)
            }

            ProtocolEra.LEGACY -> {
                sendLegacyRequest(method, params)
            }

            ProtocolEra.UNKNOWN -> {
                error("MCP transport is not initialized")
            }
        }

    private suspend fun sendModernRequest(
        method: String,
        originalParams: JsonObject,
    ): JsonObject {
        var retryParams = originalParams
        repeat(maxInputRequiredRounds + 1) { round ->
            val result =
                postRequest(
                    method = method,
                    params = retryParams,
                    protocolVersion = McpProtocol.MODERN_VERSION,
                    modern = true,
                ).resultOrThrow()
            when (result.resultType()) {
                McpProtocol.RESULT_COMPLETE -> {
                    return result
                }

                McpProtocol.RESULT_INPUT_REQUIRED -> {
                    if (method !in McpProtocol.multiRoundTripMethods) {
                        throw RuntimeException("MCP input_required is not valid for method '$method'")
                    }
                    if (round == maxInputRequiredRounds) {
                        throw RuntimeException("MCP input_required exceeded $maxInputRequiredRounds rounds")
                    }
                    retryParams = buildInputRequiredRetry(originalParams, result)
                }

                McpProtocol.RESULT_TASK -> {
                    if (method != "tools/call") {
                        throw RuntimeException("MCP task result is not valid for method '$method'")
                    }
                    return awaitTask(result)
                }

                else -> {
                    throw RuntimeException("Unsupported MCP result type: ${result.resultType()}")
                }
            }
        }
        error("Unreachable MCP input_required loop")
    }

    private suspend fun buildInputRequiredRetry(
        originalParams: JsonObject,
        result: JsonObject,
    ): JsonObject {
        val inputRequests =
            when (val value = result["inputRequests"]) {
                null -> {
                    JsonObject(emptyMap())
                }

                is JsonObject -> {
                    value
                }

                else -> {
                    throw RuntimeException("MCP inputRequests must be an object")
                }
            }
        val requestState =
            when (val value = result["requestState"]) {
                null -> {
                    null
                }

                is JsonPrimitive -> {
                    value.takeIf { it.isString }
                        ?: throw RuntimeException("MCP requestState must be a string")
                }

                else -> {
                    throw RuntimeException("MCP requestState must be a string")
                }
            }
        if (inputRequests.isEmpty() && requestState == null) {
            throw RuntimeException("MCP input_required must contain inputRequests or requestState")
        }
        if (inputRequests.size > maxInputRequestsPerRound) {
            throw RuntimeException("MCP input_required exceeds $maxInputRequestsPerRound requests in one round")
        }
        val inputResponses = fulfillInputRequests(inputRequests, result)
        return buildJsonObject {
            originalParams.forEach { (key, value) ->
                if (key != "inputResponses" && key != "requestState") put(key, value)
            }
            put("inputResponses", inputResponses)
            requestState?.let { put("requestState", it) }
        }
    }

    private suspend fun awaitTask(initialTask: JsonObject): JsonObject {
        val taskId = initialTask.requiredTaskId()
        var task = initialTask
        val fulfilledInputResponses = mutableMapOf<String, JsonElement>()
        try {
            repeat(maxTaskPolls) {
                if (task.requiredTaskId() != taskId) {
                    throw RuntimeException("MCP task response changed taskId")
                }
                when (task.requiredTaskStatus()) {
                    "completed" -> {
                        return task["result"] as? JsonObject
                            ?: throw RuntimeException("Completed MCP task '$taskId' is missing result")
                    }

                    "failed" -> {
                        val error = task["error"] as? JsonObject
                        val message = error?.get("message")?.jsonPrimitive?.content ?: "Task execution failed"
                        throw RuntimeException("MCP task '$taskId' failed: $message")
                    }

                    "cancelled" -> {
                        throw RuntimeException("MCP task '$taskId' was cancelled")
                    }

                    "input_required" -> {
                        updateTaskInput(taskId, task, fulfilledInputResponses)
                    }

                    "working" -> {}

                    else -> {
                        throw RuntimeException("MCP task '$taskId' has an unsupported status")
                    }
                }

                val pollInterval =
                    (task["pollIntervalMs"] as? JsonPrimitive)
                        ?.content
                        ?.toLongOrNull()
                        ?.coerceIn(MIN_TASK_POLL_INTERVAL_MS, MAX_TASK_POLL_INTERVAL_MS)
                        ?: DEFAULT_TASK_POLL_INTERVAL_MS
                taskPollDelay(pollInterval)
                task = requestTaskState(taskId)
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) { bestEffortCancelTask(taskId) }
            throw error
        }

        bestEffortCancelTask(taskId)
        throw RuntimeException("MCP task '$taskId' exceeded $maxTaskPolls polling attempts")
    }

    private suspend fun updateTaskInput(
        taskId: String,
        task: JsonObject,
        fulfilledInputResponses: MutableMap<String, JsonElement>,
    ) {
        val inputRequests = task["inputRequests"] as? JsonObject
            ?: throw RuntimeException("MCP task '$taskId' requires input but supplied no inputRequests")
        val newRequests = JsonObject(inputRequests.filterKeys { it !in fulfilledInputResponses })
        if (newRequests.isEmpty()) return
        val inputResponses = fulfillInputRequests(newRequests, task)
        inputResponses.forEach { (key, value) -> fulfilledInputResponses[key] = value }
        val acknowledgement =
            postRequest(
                method = "tasks/update",
                params =
                    buildJsonObject {
                        put("taskId", taskId)
                        put("inputResponses", inputResponses)
                    },
                protocolVersion = McpProtocol.MODERN_VERSION,
                modern = true,
            ).resultOrThrow()
        if (acknowledgement.resultType() != McpProtocol.RESULT_COMPLETE) {
            throw RuntimeException("MCP tasks/update must return a complete result")
        }
    }

    private suspend fun requestTask(
        method: String,
        taskId: String,
    ): JsonObject =
        postRequest(
            method = method,
            params = buildJsonObject { put("taskId", taskId) },
            protocolVersion = McpProtocol.MODERN_VERSION,
            modern = true,
        ).resultOrThrow()

    private suspend fun requestTaskState(taskId: String): JsonObject {
        val task = requestTask("tasks/get", taskId)
        if (task.resultType() != McpProtocol.RESULT_COMPLETE) {
            throw RuntimeException("MCP tasks/get must return a complete result")
        }
        if (task.requiredTaskId() != taskId) {
            throw RuntimeException("MCP task response changed taskId")
        }
        return task
    }

    private suspend fun bestEffortCancelTask(taskId: String) {
        runCatching { requestTask("tasks/cancel", taskId) }
    }

    private suspend fun fulfillInputRequests(
        inputRequests: JsonObject,
        result: JsonObject,
    ): JsonObject =
        buildJsonObject {
            inputRequests.forEach { (key, value) ->
                val request = value as? JsonObject
                    ?: throw RuntimeException("MCP input request '$key' must be an object")
                val requestMethod =
                    (request["method"] as? JsonPrimitive)?.content
                        ?: throw RuntimeException("MCP input request '$key' is missing method")
                if (request["params"] !is JsonObject) {
                    throw RuntimeException("MCP input request '$key' is missing params")
                }
                val handler =
                    inputRequestHandlers[requestMethod]
                        ?: throw McpInputRequiredException(
                            result,
                            "MCP input request method '$requestMethod' is not enabled",
                        )
                put(key, handler.fulfill(key, request))
            }
        }

    private fun JsonObject.requiredTaskId(): String =
        (this["taskId"] as? JsonPrimitive)
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: throw RuntimeException("MCP task result is missing taskId")

    private fun JsonObject.requiredTaskStatus(): String =
        (this["status"] as? JsonPrimitive)
            ?.content
            ?: throw RuntimeException("MCP task result is missing status")

    private suspend fun sendLegacyRequest(
        method: String,
        params: JsonObject,
    ): JsonObject = postRequest(method, params, McpProtocol.LEGACY_VERSION, modern = false).resultOrThrow()

    private suspend fun postRequest(
        method: String,
        params: JsonObject,
        protocolVersion: String,
        modern: Boolean,
    ): McpWireResponse {
        val id = nextId.getAndIncrement()
        val requestParams = if (modern) params.withModernMetadata() else params
        val body =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", requestParams)
            }
        val headers =
            buildMap {
                putAll(customHeaders)
                put(McpProtocol.PROTOCOL_VERSION_HEADER, protocolVersion)
                if (modern) {
                    put(McpProtocol.METHOD_HEADER, method)
                    routingName(method, params)?.let { put(McpProtocol.NAME_HEADER, it) }
                }
            }
        val response =
            outboundClient.postJsonFollowingRedirects(
                url = baseUrl,
                headers = headers,
                body = json.encodeToString(JsonObject.serializer(), body),
            )
        val responseJson =
            runCatching { json.decodeFromString(JsonObject.serializer(), response.body) }.getOrNull()
        return McpWireResponse(response.status, responseJson, id)
    }

    private fun JsonObject.withModernMetadata(): JsonObject =
        buildJsonObject {
            forEach { (key, value) ->
                if (key != "_meta") put(key, value)
            }
            putJsonObject("_meta") {
                put(McpProtocol.PROTOCOL_VERSION_META, McpProtocol.MODERN_VERSION)
                putJsonObject(McpProtocol.CLIENT_INFO_META) {
                    put("name", "promethe")
                    put("version", PrometheVersion.CURRENT)
                }
                put(McpProtocol.CLIENT_CAPABILITIES_META, advertisedClientCapabilities())
            }
        }

    private fun advertisedClientCapabilities(): JsonObject =
        buildJsonObject {
            inputRequestHandlers.keys
                .mapNotNull(INPUT_METHOD_CAPABILITIES::get)
                .distinct()
                .sorted()
                .forEach { capability -> putJsonObject(capability) {} }
            putJsonObject("extensions") {
                putJsonObject(McpProtocol.TASKS_EXTENSION) {}
            }
        }

    private fun JsonObject.resultType(): String =
        (get("resultType") as? JsonPrimitive)
            ?.content
            ?: throw RuntimeException("Modern MCP response is missing resultType")

    private fun routingName(
        method: String,
        params: JsonObject,
    ): String? =
        when (method) {
            "tools/call" -> params["name"]?.jsonPrimitive?.content
            "resources/read" -> params["uri"]?.jsonPrimitive?.content
            "prompts/get" -> params["name"]?.jsonPrimitive?.content
            "tasks/get", "tasks/update", "tasks/cancel" -> params["taskId"]?.jsonPrimitive?.content
            else -> null
        }

    private enum class ProtocolEra {
        UNKNOWN,
        MODERN,
        LEGACY,
    }

    private data class McpWireResponse(
        val status: Int,
        val body: JsonObject?,
        val expectedId: Int,
    ) {
        val error: JsonObject?
            get() = body?.get("error") as? JsonObject

        val errorCode: Int?
            get() = error?.get("code")?.jsonPrimitive?.content?.toIntOrNull()

        val isSuccess: Boolean
            get() = status in 200..299 && validEnvelope && error == null && body?.get("result") is JsonObject

        private val validEnvelope: Boolean
            get() {
                val response = body ?: return false
                val hasResult = response["result"] != null
                val hasError = response["error"] != null
                return response["jsonrpc"] == JsonPrimitive("2.0") &&
                    response["id"] == JsonPrimitive(expectedId) &&
                    hasResult.xor(hasError) &&
                    (!hasResult || response["result"] is JsonObject) &&
                    (!hasError || response["error"] is JsonObject)
            }

        fun permitsLegacyFallback(): Boolean {
            if (!validEnvelope) return false
            if (errorCode == -32601) return true
            if (status != 400) return false
            if (errorCode == -32020 || errorCode == -32021) return false
            if (errorCode != -32022) return true
            val supported =
                (error?.get("data") as? JsonObject)
                    ?.get("supported")
                    ?.let { it as? JsonArray }
                    ?.map { it.jsonPrimitive.content }
                    .orEmpty()
            return McpProtocol.LEGACY_VERSION in supported
        }

        fun resultOrThrow(): JsonObject {
            if (!isSuccess) throw toException()
            return body?.get("result") as JsonObject
        }

        fun toException(): RuntimeException {
            if (body != null && !validEnvelope) {
                return RuntimeException("Invalid MCP JSON-RPC response envelope")
            }
            val message = error?.get("message")?.jsonPrimitive?.content
            return if (error != null) {
                RuntimeException("MCP error $errorCode: ${message ?: "Unknown protocol error"}")
            } else {
                RuntimeException("MCP HTTP error $status")
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_INPUT_REQUIRED_ROUNDS = 10
        const val DEFAULT_MAX_INPUT_REQUESTS_PER_ROUND = 16
        const val DEFAULT_MAX_TASK_POLLS = 3_600
        const val DEFAULT_TASK_POLL_INTERVAL_MS = 1_000L
        const val MIN_TASK_POLL_INTERVAL_MS = 10L
        const val MAX_TASK_POLL_INTERVAL_MS = 10_000L

        val INPUT_METHOD_CAPABILITIES =
            mapOf(
                "elicitation/create" to "elicitation",
                "sampling/createMessage" to "sampling",
                "roots/list" to "roots",
            )
        val SUPPORTED_INPUT_REQUEST_METHODS: Set<String> = INPUT_METHOD_CAPABILITIES.keys
    }
}
