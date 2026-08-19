package dev.promethe.core

import dev.promethe.api.PrometheVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var nextId = 1
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
            era = ProtocolEra.MODERN
            return discovery.resultOrThrow()
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
        val tools = response["tools"]?.jsonArray ?: return emptyList()
        return tools.map { tool ->
            val obj = tool.jsonObject
            McpBridge.McpToolInfo(
                serverId = "",
                toolName = obj["name"]?.jsonPrimitive?.content ?: "",
                description = obj["description"]?.jsonPrimitive?.content ?: "",
                inputSchema = obj["inputSchema"]?.jsonObject,
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
        return response["content"]?.jsonArray
            ?.firstOrNull()?.jsonObject
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
                postRequest(method, params, McpProtocol.MODERN_VERSION, modern = true).resultOrThrow()
            }

            ProtocolEra.LEGACY -> {
                sendLegacyRequest(method, params)
            }

            ProtocolEra.UNKNOWN -> {
                error("MCP transport is not initialized")
            }
        }

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
        val id = nextId++
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
        return McpWireResponse(response.status, responseJson)
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
                putJsonObject(McpProtocol.CLIENT_CAPABILITIES_META) {}
            }
        }

    private fun routingName(
        method: String,
        params: JsonObject,
    ): String? =
        when (method) {
            "tools/call" -> params["name"]?.jsonPrimitive?.content
            "resources/read" -> params["uri"]?.jsonPrimitive?.content
            "prompts/get" -> params["name"]?.jsonPrimitive?.content
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
    ) {
        val error: JsonObject?
            get() = body?.get("error") as? JsonObject

        val errorCode: Int?
            get() = error?.get("code")?.jsonPrimitive?.content?.toIntOrNull()

        val isSuccess: Boolean
            get() = status in 200..299 && error == null && body?.get("result") is JsonObject

        fun permitsLegacyFallback(): Boolean {
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
            val message = error?.get("message")?.jsonPrimitive?.content
            return if (error != null) {
                RuntimeException("MCP error $errorCode: ${message ?: "Unknown protocol error"}")
            } else {
                RuntimeException("MCP HTTP error $status")
            }
        }
    }
}
