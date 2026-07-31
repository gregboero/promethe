package dev.promethe.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Stateless HTTP transport for MCP (2026 spec).
 * Each request is an independent HTTP POST — no session, no sticky routing needed.
 * Compatible with standard load balancers (round-robin).
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

    suspend fun initialize(): JsonObject =
        sendRequest(
            "initialize",
            buildJsonObject {
                put("protocolVersion", "2025-11-05")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "promethe")
                    put("version", "1.0.0")
                }
            },
        )

    suspend fun listTools(): List<McpBridge.McpToolInfo> {
        val response = sendRequest("tools/list", buildJsonObject {})
        val tools = response["tools"]?.jsonArray ?: return emptyList()
        return tools.map { t ->
            val obj = t.jsonObject
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
        val response = sendRequest(
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
    ): JsonObject {
        val id = nextId++
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        val response =
            outboundClient.postJsonFollowingRedirects(
                url = baseUrl,
                headers = customHeaders + ("MCP-Protocol-Version" to "2025-11-05"),
                body = json.encodeToString(JsonObject.serializer(), body),
            )
        if (response.status !in 200..299) {
            throw RuntimeException("MCP HTTP error ${response.status}")
        }
        val responseText = response.body
        val responseJson = json.decodeFromString(JsonObject.serializer(), responseText)

        if (responseJson.containsKey("error")) {
            val error = responseJson["error"]!!.jsonObject
            throw RuntimeException("MCP error ${error["code"]}: ${error["message"]?.jsonPrimitive?.content}")
        }

        return responseJson["result"]?.jsonObject ?: JsonObject(emptyMap())
    }
}
