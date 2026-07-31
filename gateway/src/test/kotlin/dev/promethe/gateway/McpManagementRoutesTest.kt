package dev.promethe.gateway

import dev.promethe.core.McpBridge
import dev.promethe.core.security.SecretCipher
import dev.promethe.db.DatabaseFactory
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpManagementRoutesTest {
    @Test
    fun `MCP listing redacts persisted headers and environment values`() =
        testApplication {
            val database = DatabaseFactory.createInMemory()
            val bridge = McpBridge()
            val cipher = SecretCipher.fromBase64Key(Base64.getEncoder().encodeToString(ByteArray(32) { 4 }))
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
                route("/api/v1") { mcpManagementRoutes(bridge, database, cipher) }
            }

            val secret = "Bearer top-secret-value"
            val created =
                client.post("/api/v1/mcp/servers") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"id":"calendar","name":"Calendar","transport":"streamable-http","url":"https://mcp.example.test","headers":{"Authorization":"$secret"},"env":{"MCP_TOKEN":"top-secret-value"}}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, created.status)

            val listing = client.get("/api/v1/mcp/servers").bodyAsText()
            assertFalse(listing.contains("top-secret-value"))
            assertTrue(listing.contains("\"Authorization\":\"***\""))
            assertTrue(listing.contains("\"MCP_TOKEN\":\"***\""))
        }
}
