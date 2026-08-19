package dev.promethe.gateway

import dev.promethe.api.ToolCallOrigin
import dev.promethe.core.McpElicitationBroker
import dev.promethe.core.ToolExecutionRequest
import dev.promethe.core.ToolInvocationContext
import dev.promethe.gateway.auth.OwnerAuthService
import dev.promethe.db.DatabaseFactory
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class McpElicitationRoutesTest {
    private val apiKey = "pk-prom-mcp-input-local-key"
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `MCP input routes reject unauthenticated requests`() =
        testApplication {
            configureRoutes(McpElicitationBroker(10_000))

            assertEquals(HttpStatusCode.Unauthorized, client.get("/approval/mcp/pending").status)
            val response =
                client.post("/approval/mcp/unknown") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"action":"cancel"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `authenticated owner can answer pending MCP form without exposing session id`() =
        testApplication {
            coroutineScope {
                val broker = McpElicitationBroker(10_000, nextId = { "input-route" })
                configureRoutes(broker)
                val result =
                    async {
                        withContext(ToolInvocationContext(invocation("private-session"))) {
                            broker.handlerFor("calendar", "Calendar").fulfill("confirm", formRequest())
                        }
                    }
                awaitPending(broker)

                val listing =
                    client.get("/approval/mcp/pending") {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                    }
                assertEquals(HttpStatusCode.OK, listing.status)
                val listingBody = listing.bodyAsText()
                assertFalse(listingBody.contains("private-session"))
                val item = json.parseToJsonElement(listingBody).jsonObject["pending"]!!.jsonArray.single().jsonObject
                assertEquals("Calendar", item["serverName"]?.jsonPrimitive?.content)

                val response =
                    client.post("/approval/mcp/input-route") {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                        contentType(ContentType.Application.Json)
                        setBody("""{"action":"accept","content":{"confirmed":true}}""")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(true, result.await()["content"]?.jsonObject?.get("confirmed")?.jsonPrimitive?.content?.toBoolean())
            }
        }

    @Test
    fun `invalid MCP form response remains pending`() =
        testApplication {
            coroutineScope {
                val broker = McpElicitationBroker(10_000, nextId = { "input-invalid" })
                configureRoutes(broker)
                val result =
                    async {
                        withContext(ToolInvocationContext(invocation("session-a"))) {
                            broker.handlerFor("calendar", "Calendar").fulfill("confirm", formRequest())
                        }
                    }
                awaitPending(broker)

                val invalid =
                    client.post("/approval/mcp/input-invalid") {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                        contentType(ContentType.Application.Json)
                        setBody("""{"action":"accept","content":{"confirmed":"yes"}}""")
                    }
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
                assertEquals(1, broker.listPending().size)

                val declined =
                    client.post("/approval/mcp/input-invalid") {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                        contentType(ContentType.Application.Json)
                        setBody("""{"action":"decline"}""")
                    }
                assertEquals(HttpStatusCode.OK, declined.status)
                assertEquals("decline", result.await()["action"]?.jsonPrimitive?.content)
            }
        }

    private fun io.ktor.server.testing.ApplicationTestBuilder.configureRoutes(broker: McpElicitationBroker) {
        val auth = OwnerAuthService(DatabaseFactory.createInMemory(), sessionTtlMinutes = 15)
        val security =
            GatewaySecurityConfig(
                bindHost = "127.0.0.1",
                remoteAccessEnabled = false,
                allowedOrigins = emptySet(),
                remoteSessionTtlMinutes = 15,
            )
        application {
            install(ContentNegotiation) { json(json) }
            AuthMiddleware.install(this, apiKey, security, auth)
            routing { mcpElicitationRoutes(broker) }
        }
    }

    private suspend fun awaitPending(broker: McpElicitationBroker) {
        withTimeout(2_000) {
            while (broker.listPending().isEmpty()) delay(10)
        }
    }

    private fun invocation(sessionId: String) =
        ToolExecutionRequest(
            toolName = "mcp_calendar_confirm",
            arguments = JsonObject(emptyMap()),
            sessionId = sessionId,
            origin = ToolCallOrigin.AGENT,
        )

    private fun formRequest(): JsonObject =
        buildJsonObject {
            put("method", "elicitation/create")
            putJsonObject("params") {
                put("mode", "form")
                put("message", "Continue?")
                putJsonObject("requestedSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("confirmed") { put("type", "boolean") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("confirmed")) }
                }
            }
        }
}
