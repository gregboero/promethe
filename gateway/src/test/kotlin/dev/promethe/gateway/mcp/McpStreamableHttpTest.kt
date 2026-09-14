package dev.promethe.gateway.mcp

import dev.promethe.core.JvmOutboundUrlPolicy
import dev.promethe.core.McpProtocol
import dev.promethe.core.McpStreamableHttpTransport
import dev.promethe.core.PinnedJvmOutboundHttpFetcher
import dev.promethe.db.DatabaseFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.*
import java.net.InetAddress
import kotlin.test.*

class McpStreamableHttpTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testWellKnownMcpEndpoint() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            encodeDefaults = true
                        },
                    )
                }
            }
            routing {
                mcpServerRoutes()
            }

            val response = client.get("/.well-known/mcp")
            assertEquals(HttpStatusCode.OK, response.status)

            val responseText = response.bodyAsText()
            val responseJson = json.decodeFromString(JsonObject.serializer(), responseText)

            assertEquals("promethe", responseJson["name"]?.jsonPrimitive?.content)
            assertEquals("1.0.0", responseJson["version"]?.jsonPrimitive?.content)
            assertEquals(McpProtocol.MODERN_VERSION, responseJson["protocolVersion"]?.jsonPrimitive?.content)
            assertEquals(
                listOf(McpProtocol.MODERN_VERSION, McpProtocol.LEGACY_VERSION),
                responseJson["supportedVersions"]?.jsonArray?.map { it.jsonPrimitive.content },
            )

            val capabilities = responseJson["capabilities"]?.jsonObject
            assertNotNull(capabilities)
            val tools = capabilities["tools"]?.jsonObject
            assertNotNull(tools)
            assertEquals(false, tools["listChanged"]?.jsonPrimitive?.boolean)

            assertEquals("/mcp", responseJson["endpoint"]?.jsonPrimitive?.content)
        }

    @Test
    fun `modern MCP discovery is stateless and self describing`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
            routing {
                mcpServerRoutes()
            }

            val response =
                client.post("/mcp") {
                    contentType(ContentType.Application.Json)
                    header(McpProtocol.PROTOCOL_VERSION_HEADER, McpProtocol.MODERN_VERSION)
                    header(McpProtocol.METHOD_HEADER, "server/discover")
                    setBody(modernRequest(id = 1, method = "server/discover"))
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val result = json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonObject
            assertEquals("complete", result["resultType"]?.jsonPrimitive?.content)
            assertEquals(
                listOf(McpProtocol.MODERN_VERSION, McpProtocol.LEGACY_VERSION),
                result["supportedVersions"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertNotNull(result["_meta"]?.jsonObject?.get(McpProtocol.SERVER_INFO_META))
        }

    @Test
    fun `modern MCP rejects missing or inconsistent routing headers`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
            routing {
                mcpServerRoutes()
            }

            val missingMethod =
                client.post("/mcp") {
                    contentType(ContentType.Application.Json)
                    header(McpProtocol.PROTOCOL_VERSION_HEADER, McpProtocol.MODERN_VERSION)
                    setBody(modernRequest(id = 2, method = "tools/list"))
                }
            assertProtocolError(missingMethod, -32020)

            val wrongName =
                client.post("/mcp") {
                    contentType(ContentType.Application.Json)
                    header(McpProtocol.PROTOCOL_VERSION_HEADER, McpProtocol.MODERN_VERSION)
                    header(McpProtocol.METHOD_HEADER, "tools/call")
                    header(McpProtocol.NAME_HEADER, "wrong.tool")
                    setBody(
                        modernRequest(
                            id = 3,
                            method = "tools/call",
                            params = buildJsonObject {
                                put("name", "expected.tool")
                                putJsonObject("arguments") {}
                            },
                        ),
                    )
                }
            assertProtocolError(wrongName, -32020)
        }

    @Test
    fun `modern MCP reports supported versions for an unknown revision`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
            routing {
                mcpServerRoutes()
            }

            val response =
                client.post("/mcp") {
                    contentType(ContentType.Application.Json)
                    header(McpProtocol.PROTOCOL_VERSION_HEADER, "2099-01-01")
                    header(McpProtocol.METHOD_HEADER, "tools/list")
                    setBody(modernRequest(id = 4, method = "tools/list", protocolVersion = "2099-01-01"))
                }

            assertProtocolError(response, -32022)
            val data =
                json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["data"]!!.jsonObject
            assertEquals(
                listOf(McpProtocol.MODERN_VERSION, McpProtocol.LEGACY_VERSION),
                data["supported"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
        }

    @Test
    fun `modern MCP task routes use task id routing and advertise durable support`() {
        val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            testApplication {
                application {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }
                routing {
                    mcpServerRoutes(
                        taskManager = McpTaskManager(DatabaseFactory.createInMemory(), taskScope),
                    )
                }

                val card = json.parseToJsonElement(client.get("/.well-known/mcp").bodyAsText()).jsonObject
                assertNotNull(
                    card["capabilities"]!!.jsonObject["extensions"]!!.jsonObject[McpProtocol.TASKS_EXTENSION],
                )

                val accepted =
                    client.post("/mcp") {
                        contentType(ContentType.Application.Json)
                        header(McpProtocol.PROTOCOL_VERSION_HEADER, McpProtocol.MODERN_VERSION)
                        header(McpProtocol.METHOD_HEADER, "tasks/get")
                        header(McpProtocol.NAME_HEADER, "task-42")
                        setBody(
                            modernRequest(
                                id = 5,
                                method = "tasks/get",
                                params = buildJsonObject { put("taskId", "task-42") },
                                tasks = true,
                            ),
                        )
                    }
                assertEquals(HttpStatusCode.OK, accepted.status)
                val error = json.parseToJsonElement(accepted.bodyAsText()).jsonObject["error"]!!.jsonObject
                assertEquals(-32602, error["code"]?.jsonPrimitive?.int)

                val rejected =
                    client.post("/mcp") {
                        contentType(ContentType.Application.Json)
                        header(McpProtocol.PROTOCOL_VERSION_HEADER, McpProtocol.MODERN_VERSION)
                        header(McpProtocol.METHOD_HEADER, "tasks/get")
                        header(McpProtocol.NAME_HEADER, "another-task")
                        setBody(
                            modernRequest(
                                id = 6,
                                method = "tasks/get",
                                params = buildJsonObject { put("taskId", "task-42") },
                                tasks = true,
                            ),
                        )
                    }
                assertProtocolError(rejected, -32020)
            }
        } finally {
            taskScope.cancel()
        }
    }

    @Test
    fun `MCP rejects browser origins outside the explicit allow-list`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
            routing {
                mcpServerRoutes(setOf("https://console.example"))
            }

            val rejected =
                client.post("/mcp") {
                    header(HttpHeaders.Origin, "https://untrusted.example")
                    contentType(ContentType.Application.Json)
                    setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, rejected.status)

            val allowed =
                client.post("/mcp") {
                    header(HttpHeaders.Origin, "https://console.example")
                    contentType(ContentType.Application.Json)
                    setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
                }
            assertEquals(HttpStatusCode.OK, allowed.status)
        }

    @Test
    fun testMcpStreamableHttpTransport() =
        runBlocking {
            var callCount = 0
            val mockEngine = MockEngine { request ->
                callCount++
                // Verify request headers
                assertEquals("Bearer test-token", request.headers["Authorization"])
                assertEquals("application/json", request.body.contentType?.toString()?.substringBefore(";"))

                val bodyContent = request.body as OutgoingContent.ByteArrayContent
                val bodyText = bodyContent.bytes().decodeToString()
                val requestJson = json.decodeFromString(JsonObject.serializer(), bodyText)

                assertEquals("2.0", requestJson["jsonrpc"]?.jsonPrimitive?.content)
                assertNotNull(requestJson["id"])
                val method = requestJson["method"]?.jsonPrimitive?.content

                val responseJson = when (method) {
                    "server/discover" -> {
                        assertEquals(McpProtocol.MODERN_VERSION, request.headers[McpProtocol.PROTOCOL_VERSION_HEADER])
                        assertEquals("server/discover", request.headers[McpProtocol.METHOD_HEADER])
                        assertNull(request.headers[McpProtocol.SESSION_HEADER])
                        val metadata = requestJson["params"]!!.jsonObject["_meta"]!!.jsonObject
                        assertEquals(
                            McpProtocol.MODERN_VERSION,
                            metadata[McpProtocol.PROTOCOL_VERSION_META]?.jsonPrimitive?.content,
                        )
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", requestJson["id"]?.jsonPrimitive?.int)
                            putJsonObject("result") {
                                put("resultType", "complete")
                                putJsonArray("supportedVersions") { add(McpProtocol.MODERN_VERSION) }
                                putJsonObject("capabilities") {}
                            }
                        }
                    }

                    "tools/list" -> {
                        assertEquals("tools/list", request.headers[McpProtocol.METHOD_HEADER])
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", requestJson["id"]?.jsonPrimitive?.int)
                            putJsonObject("result") {
                                put("resultType", "complete")
                                putJsonArray("tools") {
                                    add(
                                        buildJsonObject {
                                            put("name", "get_forecast")
                                            put("description", "Get weather forecast")
                                            putJsonObject("inputSchema") {}
                                        },
                                    )
                                }
                            }
                        }
                    }

                    "tools/call" -> {
                        assertEquals("tools/call", request.headers[McpProtocol.METHOD_HEADER])
                        assertEquals("get_forecast", request.headers[McpProtocol.NAME_HEADER])
                        val params = requestJson["params"]?.jsonObject
                        assertEquals("get_forecast", params?.get("name")?.jsonPrimitive?.content)
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", requestJson["id"]?.jsonPrimitive?.int)
                            putJsonObject("result") {
                                put("resultType", "complete")
                                putJsonArray("content") {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", "Sunny, 25C")
                                        },
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", requestJson["id"]?.jsonPrimitive?.int)
                            putJsonObject("error") {
                                put("code", -32601)
                                put("message", "Unknown method")
                            }
                        }
                    }
                }

                respond(
                    content = responseJson.toString(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }

            val outboundClient =
                PinnedJvmOutboundHttpFetcher(
                    policy = JvmOutboundUrlPolicy { arrayOf(InetAddress.getByName("93.184.216.34")) },
                    clientFactory = {
                        HttpClient(mockEngine) {
                            followRedirects = false
                        }
                    },
                )
            val transport =
                McpStreamableHttpTransport(
                    baseUrl = "https://mcp.promethe.dev/api",
                    customHeaders = mapOf("Authorization" to "Bearer test-token"),
                    outboundClient = outboundClient,
                )

            val initResult = transport.initialize()
            assertNotNull(initResult)

            val tools = transport.listTools()
            assertEquals(1, tools.size)
            assertEquals("get_forecast", tools[0].toolName)
            assertEquals("Get weather forecast", tools[0].description)

            val callResult = transport.callTool("get_forecast", buildJsonObject {})
            assertEquals("Sunny, 25C", callResult)

            transport.close()
            assertEquals(3, callCount)
        }

    private fun modernRequest(
        id: Int,
        method: String,
        params: JsonObject = buildJsonObject {},
        protocolVersion: String = McpProtocol.MODERN_VERSION,
        tasks: Boolean = false,
    ): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            putJsonObject("params") {
                params.forEach { (key, value) -> put(key, value) }
                putJsonObject("_meta") {
                    put(McpProtocol.PROTOCOL_VERSION_META, protocolVersion)
                    putJsonObject(McpProtocol.CLIENT_INFO_META) {
                        put("name", "test-client")
                        put("version", "1.0.0")
                    }
                    putJsonObject(McpProtocol.CLIENT_CAPABILITIES_META) {
                        if (tasks) {
                            putJsonObject("extensions") {
                                putJsonObject(McpProtocol.TASKS_EXTENSION) {}
                            }
                        }
                    }
                }
            }
        }.toString()

    private suspend fun assertProtocolError(
        response: HttpResponse,
        expectedCode: Int,
    ) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals(expectedCode, error["code"]?.jsonPrimitive?.int)
    }
}
