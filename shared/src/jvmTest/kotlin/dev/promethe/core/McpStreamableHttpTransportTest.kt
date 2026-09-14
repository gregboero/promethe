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
    fun `modern MCP fulfills input required and retries the original request`() =
        runTest {
            var toolCallCount = 0
            val outboundClient =
                RecordingOutboundClient { request ->
                    when (request.method()) {
                        "server/discover" -> {
                            jsonRpcResult(
                                request.id(),
                                modernDiscoveryResult(),
                            ).asHttpResponse()
                        }

                        "tools/call" -> {
                            toolCallCount++
                            val params = json.parseToJsonElement(request.body).jsonObject["params"]!!.jsonObject
                            assertEquals("weather.get", params["name"]?.jsonPrimitive?.content)
                            assertEquals("Montreal", params["arguments"]?.jsonObject?.get("city")?.jsonPrimitive?.content)
                            if (toolCallCount == 1) {
                                jsonRpcResult(
                                    request.id(),
                                    buildJsonObject {
                                        put("resultType", McpProtocol.RESULT_INPUT_REQUIRED)
                                        putJsonObject("inputRequests") {
                                            putJsonObject("confirm") {
                                                put("method", "elicitation/create")
                                                putJsonObject("params") { put("message", "Continue?") }
                                            }
                                        }
                                        put("requestState", "sealed-state")
                                    },
                                ).asHttpResponse()
                            } else {
                                assertEquals("sealed-state", params["requestState"]?.jsonPrimitive?.content)
                                val response = params["inputResponses"]!!.jsonObject["confirm"]!!.jsonObject
                                assertEquals("accept", response["action"]?.jsonPrimitive?.content)
                                jsonRpcResult(
                                    request.id(),
                                    buildJsonObject {
                                        put("resultType", McpProtocol.RESULT_COMPLETE)
                                        putJsonArray("content") {
                                            add(
                                                buildJsonObject {
                                                    put("type", "text")
                                                    put("text", "Sunny")
                                                },
                                            )
                                        }
                                    },
                                ).asHttpResponse()
                            }
                        }

                        else -> {
                            error("Unexpected method ${request.method()}")
                        }
                    }
                }
            val handledRequests = mutableListOf<String>()
            val transport =
                McpStreamableHttpTransport(
                    baseUrl = "https://mcp.example/rpc",
                    outboundClient = outboundClient,
                    inputRequestHandlers =
                        mapOf(
                            "elicitation/create" to
                                McpInputRequestHandler { key, request ->
                                    handledRequests += "$key:${request["method"]?.jsonPrimitive?.content}"
                                    buildJsonObject { put("action", "accept") }
                                },
                        ),
                )

            transport.initialize()
            assertEquals("Sunny", transport.callTool("weather.get", buildJsonObject { put("city", "Montreal") }))

            assertEquals(listOf("confirm:elicitation/create"), handledRequests)
            assertEquals(listOf(1, 2, 3), outboundClient.requests.map { it.id() })
            val firstCallMeta =
                json.parseToJsonElement(outboundClient.requests[1].body).jsonObject["params"]!!.jsonObject["_meta"]!!.jsonObject
            assertTrue(firstCallMeta[McpProtocol.CLIENT_CAPABILITIES_META]!!.jsonObject.containsKey("elicitation"))
        }

    @Test
    fun `modern MCP input required fails closed without a handler`() =
        runTest {
            val outboundClient =
                RecordingOutboundClient { request ->
                    val result =
                        if (request.method() == "server/discover") {
                            modernDiscoveryResult()
                        } else {
                            buildJsonObject {
                                put("resultType", McpProtocol.RESULT_INPUT_REQUIRED)
                                putJsonObject("inputRequests") {
                                    putJsonObject("confirm") {
                                        put("method", "elicitation/create")
                                        putJsonObject("params") { put("message", "Continue?") }
                                    }
                                }
                            }
                        }
                    jsonRpcResult(request.id(), result).asHttpResponse()
                }
            val transport = McpStreamableHttpTransport("https://mcp.example/rpc", outboundClient = outboundClient)

            transport.initialize()

            assertFailsWith<McpInputRequiredException> {
                transport.callTool("weather.get", buildJsonObject {})
            }
            assertEquals(2, outboundClient.requests.size)
        }

    @Test
    fun `modern MCP bounds state-only input required retries`() =
        runTest {
            val outboundClient =
                RecordingOutboundClient { request ->
                    val result =
                        if (request.method() == "server/discover") {
                            modernDiscoveryResult()
                        } else {
                            buildJsonObject {
                                put("resultType", McpProtocol.RESULT_INPUT_REQUIRED)
                                put("requestState", "round-${request.id()}")
                            }
                        }
                    jsonRpcResult(request.id(), result).asHttpResponse()
                }
            val transport =
                McpStreamableHttpTransport(
                    baseUrl = "https://mcp.example/rpc",
                    outboundClient = outboundClient,
                    maxInputRequiredRounds = 2,
                )

            transport.initialize()

            val failure =
                assertFailsWith<RuntimeException> {
                    transport.callTool("weather.get", buildJsonObject {})
                }
            assertTrue(failure.message.orEmpty().contains("exceeded 2 rounds"))
            assertEquals(listOf(1, 2, 3, 4), outboundClient.requests.map { it.id() })
        }

    @Test
    fun `modern MCP rejects malformed input required state`() =
        runTest {
            val outboundClient =
                RecordingOutboundClient { request ->
                    val result =
                        if (request.method() == "server/discover") {
                            modernDiscoveryResult()
                        } else {
                            buildJsonObject {
                                put("resultType", McpProtocol.RESULT_INPUT_REQUIRED)
                                put("requestState", 42)
                            }
                        }
                    jsonRpcResult(request.id(), result).asHttpResponse()
                }
            val transport = McpStreamableHttpTransport("https://mcp.example/rpc", outboundClient = outboundClient)

            transport.initialize()

            val failure =
                assertFailsWith<RuntimeException> {
                    transport.callTool("weather.get", buildJsonObject {})
                }
            assertTrue(failure.message.orEmpty().contains("requestState must be a string"))
        }

    @Test
    fun `modern MCP rejects a response with the wrong request id`() =
        runTest {
            val outboundClient =
                RecordingOutboundClient { request ->
                    jsonRpcResult(request.id() + 1, modernDiscoveryResult()).asHttpResponse()
                }
            val transport = McpStreamableHttpTransport("https://mcp.example/rpc", outboundClient = outboundClient)

            val failure = assertFailsWith<RuntimeException> { transport.initialize() }

            assertTrue(failure.message.orEmpty().contains("Invalid MCP JSON-RPC response envelope"))
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

    @Test
    fun `modern MCP advertises tasks and polls a durable tool result`() =
        runTest {
            var taskPolls = 0
            val outboundClient =
                RecordingOutboundClient { request ->
                    val result =
                        when (request.method()) {
                            "server/discover" -> {
                                modernDiscoveryResult()
                            }

                            "tools/call" -> {
                                buildJsonObject {
                                    put("resultType", McpProtocol.RESULT_TASK)
                                    put("taskId", "task-42")
                                    put("status", "working")
                                    put("createdAt", "2026-08-19T00:00:00Z")
                                    put("lastUpdatedAt", "2026-08-19T00:00:00Z")
                                    put("ttlMs", 60_000)
                                    put("pollIntervalMs", 25)
                                }
                            }

                            "tasks/get" -> {
                                taskPolls++
                                assertEquals("task-42", request.headers[McpProtocol.NAME_HEADER])
                                val params = json.parseToJsonElement(request.body).jsonObject["params"]!!.jsonObject
                                assertEquals("task-42", params["taskId"]?.jsonPrimitive?.content)
                                buildJsonObject {
                                    put("resultType", McpProtocol.RESULT_COMPLETE)
                                    put("taskId", "task-42")
                                    put("status", "completed")
                                    put("createdAt", "2026-08-19T00:00:00Z")
                                    put("lastUpdatedAt", "2026-08-19T00:00:01Z")
                                    put("ttlMs", 60_000)
                                    putJsonObject("result") {
                                        putJsonArray("content") {
                                            add(
                                                buildJsonObject {
                                                    put("type", "text")
                                                    put("text", "Task complete")
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            else -> {
                                error("Unexpected method ${request.method()}")
                            }
                        }
                    jsonRpcResult(request.id(), result).asHttpResponse()
                }
            val delays = mutableListOf<Long>()
            val transport =
                McpStreamableHttpTransport(
                    baseUrl = "https://mcp.example/rpc",
                    outboundClient = outboundClient,
                    taskPollDelay = { delays += it },
                )

            transport.initialize()
            assertEquals("Task complete", transport.callTool("web_crawl", buildJsonObject {}))

            assertEquals(1, taskPolls)
            assertEquals(listOf(25L), delays)
            val capabilities =
                json.parseToJsonElement(outboundClient.requests[1].body).jsonObject["params"]!!.jsonObject["_meta"]!!
                    .jsonObject[McpProtocol.CLIENT_CAPABILITIES_META]!!.jsonObject
            assertTrue(capabilities["extensions"]!!.jsonObject.containsKey(McpProtocol.TASKS_EXTENSION))
        }

    @Test
    fun `modern MCP updates task input and cancels after bounded polling`() =
        runTest {
            var pollCount = 0
            var updateCount = 0
            var cancelCount = 0
            val outboundClient =
                RecordingOutboundClient { request ->
                    val result =
                        when (request.method()) {
                            "server/discover" -> {
                                modernDiscoveryResult()
                            }

                            "tools/call" -> {
                                taskState(McpProtocol.RESULT_TASK, "working")
                            }

                            "tasks/get" -> {
                                pollCount++
                                if (pollCount <= 2) {
                                    taskState(McpProtocol.RESULT_COMPLETE, "input_required") {
                                        putJsonObject("inputRequests") {
                                            putJsonObject("confirm") {
                                                put("method", "elicitation/create")
                                                putJsonObject("params") { put("message", "Continue?") }
                                            }
                                        }
                                    }
                                } else {
                                    taskState(McpProtocol.RESULT_COMPLETE, "working")
                                }
                            }

                            "tasks/update" -> {
                                updateCount++
                                val params = json.parseToJsonElement(request.body).jsonObject["params"]!!.jsonObject
                                assertEquals("accept", params["inputResponses"]!!.jsonObject["confirm"]!!.jsonObject["action"]?.jsonPrimitive?.content)
                                buildJsonObject { put("resultType", McpProtocol.RESULT_COMPLETE) }
                            }

                            "tasks/cancel" -> {
                                cancelCount++
                                buildJsonObject { put("resultType", McpProtocol.RESULT_COMPLETE) }
                            }

                            else -> {
                                error("Unexpected method ${request.method()}")
                            }
                        }
                    jsonRpcResult(request.id(), result).asHttpResponse()
                }
            val transport =
                McpStreamableHttpTransport(
                    baseUrl = "https://mcp.example/rpc",
                    outboundClient = outboundClient,
                    inputRequestHandlers =
                        mapOf(
                            "elicitation/create" to
                                McpInputRequestHandler { _, _ ->
                                    buildJsonObject { put("action", "accept") }
                                },
                        ),
                    maxTaskPolls = 3,
                    taskPollDelay = {},
                )

            transport.initialize()
            val failure =
                assertFailsWith<RuntimeException> {
                    transport.callTool("web_crawl", buildJsonObject {})
                }

            assertTrue(failure.message.orEmpty().contains("exceeded 3 polling attempts"))
            assertEquals(1, updateCount)
            assertEquals(1, cancelCount)
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
                    modernDiscoveryResult()
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

    private fun modernDiscoveryResult(): JsonObject =
        buildJsonObject {
            put("resultType", McpProtocol.RESULT_COMPLETE)
            putJsonArray("supportedVersions") { add(McpProtocol.MODERN_VERSION) }
            putJsonObject("capabilities") {}
        }

    private fun taskState(
        resultType: String,
        status: String,
        content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): JsonObject =
        buildJsonObject {
            put("resultType", resultType)
            put("taskId", "task-input")
            put("status", status)
            put("createdAt", "2026-08-19T00:00:00Z")
            put("lastUpdatedAt", "2026-08-19T00:00:00Z")
            put("ttlMs", 60_000)
            put("pollIntervalMs", 25)
            content()
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
