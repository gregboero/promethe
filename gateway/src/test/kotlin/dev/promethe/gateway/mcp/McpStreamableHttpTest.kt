package dev.promethe.gateway.mcp

import dev.promethe.core.JvmOutboundUrlPolicy
import dev.promethe.core.McpStreamableHttpTransport
import dev.promethe.core.PinnedJvmOutboundHttpFetcher
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
            assertEquals("2025-11-05", responseJson["protocolVersion"]?.jsonPrimitive?.content)

            val capabilities = responseJson["capabilities"]?.jsonObject
            assertNotNull(capabilities)
            val tools = capabilities["tools"]?.jsonObject
            assertNotNull(tools)
            assertEquals(false, tools["listChanged"]?.jsonPrimitive?.boolean)

            assertEquals("/mcp", responseJson["endpoint"]?.jsonPrimitive?.content)
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
                    "initialize" -> {
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", requestJson["id"]?.jsonPrimitive?.int)
                            putJsonObject("result") {
                                putJsonObject("capabilities") {}
                                putJsonObject("serverInfo") {
                                    put("name", "mock-server")
                                    put("version", "2.0.0")
                                }
                            }
                        }
                    }

                    "tools/list" -> {
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", requestJson["id"]?.jsonPrimitive?.int)
                            putJsonObject("result") {
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
                        val params = requestJson["params"]?.jsonObject
                        assertEquals("get_forecast", params?.get("name")?.jsonPrimitive?.content)
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", requestJson["id"]?.jsonPrimitive?.int)
                            putJsonObject("result") {
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
}
