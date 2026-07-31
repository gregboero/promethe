package dev.promethe.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * McpSseTransport — connects to an MCP server over HTTP/SSE.
 *
 * Protocol flow:
 *   1. GET /sse → receive endpoint URL from SSE event
 *   2. POST to endpoint with JSON-RPC 2.0 messages
 *   3. Receive responses via SSE stream
 *
 * Simplified version: uses HTTP POST for request/response
 * (many MCP SSE servers also support a simpler HTTP mode).
 */
class McpSseTransport(
    private val baseUrl: String,
    private val outboundClient: SecureJvmOutboundHttpClient =
        PinnedJvmOutboundHttpFetcher(JvmOutboundUrlPolicy()),
) {
    private val requestId = AtomicInteger(0)
    private val mutex = Mutex()
    private val jsonParser =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private var sessionEndpoint: String? = null

    /**
     * Initialize the SSE connection and discover the message endpoint.
     */
    suspend fun initialize(): JsonObject {
        val sseResponse =
            outboundClient.getFollowingRedirects(
                url = baseUrl,
                headers = mapOf("Accept" to "text/event-stream"),
            )
        if (sseResponse.status in 200..299) {
            val endpointLine = sseResponse.body.lineSequence().find { it.startsWith("data:") }
            val advertisedEndpoint = endpointLine?.removePrefix("data:")?.trim().orEmpty()
            if (advertisedEndpoint.isNotEmpty()) {
                sessionEndpoint = URI(baseUrl).resolve(advertisedEndpoint).toString()
            }
        } else {
            logger.debug { "SSE discovery returned HTTP ${sseResponse.status}; using the configured MCP URL" }
        }
        sessionEndpoint = sessionEndpoint ?: baseUrl

        // Send initialize request
        val params =
            buildJsonObject {
                put("protocolVersion", "2025-11-05")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "promethe")
                    put("version", "1.0.0")
                }
            }
        return sendRequest("initialize", params)
    }

    /**
     * Discover available tools from the MCP server.
     */
    suspend fun listTools(): List<McpBridge.McpToolInfo> {
        val result = sendRequest("tools/list", buildJsonObject {})
        val toolsArray = result["tools"]?.jsonArray ?: return emptyList()

        return toolsArray.map { toolElement ->
            val tool = toolElement.jsonObject
            McpBridge.McpToolInfo(
                serverId = "",
                toolName = tool["name"]?.jsonPrimitive?.content ?: "",
                description = tool["description"]?.jsonPrimitive?.content ?: "",
                inputSchema = tool["inputSchema"]?.jsonObject,
            )
        }
    }

    /**
     * Call a tool on the MCP server.
     */
    suspend fun callTool(
        name: String,
        arguments: JsonObject,
    ): String {
        val params =
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            }
        val result = sendRequest("tools/call", params)

        val content = result["content"]?.jsonArray
        if (content != null && content.isNotEmpty()) {
            return content.joinToString("\n") { element ->
                val obj = element.jsonObject
                when (obj["type"]?.jsonPrimitive?.content) {
                    "text" -> obj["text"]?.jsonPrimitive?.content ?: ""
                    "image" -> "[image: ${obj["mimeType"]?.jsonPrimitive?.content}]"
                    else -> element.toString()
                }
            }
        }
        return result.toString()
    }

    fun close() {
        // The pinned client owns no persistent connection pool.
    }

    // ── JSON-RPC 2.0 over HTTP POST ───────────────────────

    private suspend fun sendRequest(
        method: String,
        params: JsonObject,
    ): JsonObject =
        mutex.withLock {
            val id = requestId.incrementAndGet()
            val endpoint = sessionEndpoint ?: baseUrl

            val request =
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    put("params", params)
                }

            val response =
                outboundClient.postJsonFollowingRedirects(
                    url = endpoint,
                    headers = mapOf("MCP-Protocol-Version" to "2025-11-05"),
                    body = request.toString(),
                )
            if (response.status !in 200..299) {
                throw RuntimeException("MCP HTTP error ${response.status}")
            }
            val responseJson = jsonParser.parseToJsonElement(response.body).jsonObject

            val error = responseJson["error"]
            if (error != null && error is JsonObject) {
                val code = error["code"]?.jsonPrimitive?.int ?: -1
                val message = error["message"]?.jsonPrimitive?.content ?: "Unknown MCP error"
                throw RuntimeException("MCP error ($code): $message")
            }

            responseJson["result"]?.jsonObject ?: buildJsonObject {}
        }
}
