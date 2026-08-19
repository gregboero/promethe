package dev.promethe.core

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class McpStreamableHttpTransportTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `modern MCP requests are self-contained and use routing headers`() =
        runTest {
            val outboundClient = RecordingOutboundClient(::modernResponse)
            val transport =
                McpStreamableHttpTransport(
                    baseUrl = "https://mcp.example/rpc",
                    customHeaders = mapOf("Authorization" to "Bearer secret"),
                    outboundClient = outboundClient,
                )

            transport.initialize()
            transport.callTool("weather.get", buildJsonObject { put("city", "Montreal") })

            val discovery = outboundClient.requests[0]
            assertEquals("https://mcp.example/rpc", discovery.url)
            assertEquals("Bearer secret", discovery.headers["Authorization"])
            assertEquals(McpProtocol.MODERN_VERSION, discovery.headers[McpProtocol.PROTOCOL_VERSION_HEADER])
            assertEquals("server/discover", discovery.headers[McpProtocol.METHOD_HEADER])
            assertNull(discovery.headers[McpProtocol.NAME_HEADER])
            assertNull(discovery.headers[McpProtocol.SESSION_HEADER])
            assertModernMetadata(discovery.body)

            val toolCall = outboundClient.requests[1]
            assertEquals("tools/call", toolCall.headers[McpProtocol.METHOD_HEADER])
            assertEquals("weather.get", toolCall.headers[McpProtocol.NAME_HEADER])
            assertNull(toolCall.headers[McpProtocol.SESSION_HEADER])
            assertModernMetadata(toolCall.body)
        }

    @Test
    fun `legacy server fallback performs initialize only after a failed modern probe`() =
        runTest {
            val outboundClient =
                RecordingOutboundClient { request ->
                    val method = request.method()
                    if (method == "server/discover") {
                        OutboundHttpResponse(
                            status = 400,
                            location = null,
                            body = jsonRpcError(request.id(), -32601, "Method not found").toString(),
                        )
                    } else {
                        assertEquals("initialize", method)
                        jsonRpcResult(request.id(), buildJsonObject {}).asHttpResponse()
                    }
                }
            val transport =
                McpStreamableHttpTransport(
                    baseUrl = "https://legacy-mcp.example/rpc",
                    outboundClient = outboundClient,
                )

            transport.initialize()

            assertEquals(2, outboundClient.requests.size)
            val initialize = outboundClient.requests.last()
            assertEquals(McpProtocol.LEGACY_VERSION, initialize.headers[McpProtocol.PROTOCOL_VERSION_HEADER])
            assertNull(initialize.headers[McpProtocol.METHOD_HEADER])
            assertTrue(initialize.body.contains("\"method\":\"initialize\""))
            assertTrue(initialize.body.contains("\"protocolVersion\":\"${McpProtocol.LEGACY_VERSION}\""))
        }

    @Test
    fun `recognized modern version error does not silently downgrade`() =
        runTest {
            val outboundClient =
                RecordingOutboundClient { request ->
                    OutboundHttpResponse(
                        status = 400,
                        location = null,
                        body =
                            jsonRpcError(
                                request.id(),
                                -32022,
                                "Unsupported protocol version",
                                buildJsonObject {
                                    putJsonArray("supported") { add("2027-01-01") }
                                },
                            ).toString(),
                    )
                }
            val transport =
                McpStreamableHttpTransport(
                    baseUrl = "https://modern-mcp.example/rpc",
                    outboundClient = outboundClient,
                )

            assertFailsWith<RuntimeException> { transport.initialize() }
            assertEquals(1, outboundClient.requests.size)
        }

    @Test
    fun `MCP rejects private destinations before opening a client`() =
        runTest {
            var clientOpened = false
            val policy = JvmOutboundUrlPolicy { host -> arrayOf(InetAddress.getByName(host)) }
            val outboundClient =
                PinnedJvmOutboundHttpFetcher(policy) {
                    clientOpened = true
                    error("A blocked destination must not create an HTTP client")
                }
            val transport =
                McpStreamableHttpTransport(
                    baseUrl = "http://169.254.169.254/latest/meta-data",
                    outboundClient = outboundClient,
                )

            assertFailsWith<IllegalArgumentException> {
                transport.initialize()
            }
            assertFalse(clientOpened)
        }

    private fun assertModernMetadata(body: String) {
        val params = json.parseToJsonElement(body).jsonObject["params"]!!.jsonObject
        val metadata = params["_meta"]!!.jsonObject
        assertEquals(
            McpProtocol.MODERN_VERSION,
            metadata[McpProtocol.PROTOCOL_VERSION_META]?.jsonPrimitive?.content,
        )
        assertTrue(metadata[McpProtocol.CLIENT_INFO_META] is JsonObject)
        assertTrue(metadata[McpProtocol.CLIENT_CAPABILITIES_META] is JsonObject)
    }

    private fun modernResponse(request: RecordedRequest): OutboundHttpResponse {
        val result =
            when (request.method()) {
                "server/discover" -> {
                    buildJsonObject {
                        put("resultType", "complete")
                        putJsonArray("supportedVersions") { add(McpProtocol.MODERN_VERSION) }
                    }
                }

                "tools/call" -> {
                    buildJsonObject {
                        put("resultType", "complete")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", "Sunny")
                                },
                            )
                        }
                    }
                }

                else -> {
                    error("Unexpected method ${request.method()}")
                }
            }
        return jsonRpcResult(request.id(), result).asHttpResponse()
    }

    private fun jsonRpcResult(
        id: Int,
        result: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

    private fun jsonRpcError(
        id: Int,
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

    private fun JsonObject.asHttpResponse(): OutboundHttpResponse =
        OutboundHttpResponse(
            status = 200,
            location = null,
            body = toString(),
        )

    private data class RecordedRequest(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    ) {
        fun method(): String = Json.parseToJsonElement(body).jsonObject["method"]!!.jsonPrimitive.content

        fun id(): Int = Json.parseToJsonElement(body).jsonObject["id"]!!.jsonPrimitive.content.toInt()
    }

    private class RecordingOutboundClient(
        private val responder: (RecordedRequest) -> OutboundHttpResponse,
    ) : SecureJvmOutboundHttpClient {
        val requests = mutableListOf<RecordedRequest>()

        override suspend fun get(url: String): OutboundHttpResponse = error("GET not expected")

        override suspend fun getFollowingRedirects(
            url: String,
            headers: Map<String, String>,
            maxRedirects: Int,
        ): OutboundHttpResponse = error("GET not expected")

        override suspend fun postJsonFollowingRedirects(
            url: String,
            headers: Map<String, String>,
            body: String,
            maxRedirects: Int,
        ): OutboundHttpResponse {
            val request = RecordedRequest(url, headers, body)
            requests += request
            return responder(request)
        }
    }
}
