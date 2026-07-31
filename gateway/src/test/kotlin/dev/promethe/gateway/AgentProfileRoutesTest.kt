package dev.promethe.gateway

import dev.promethe.api.AgentProfileRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Integration tests for Agent Profile CRUD routes.
 *
 * Uses FakeDatabase (in-memory) so no SQLite dependency.
 */
class AgentProfileRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.configureApp(db: FakeDatabase) {
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
            route("/api/v1") {
                agentProfileRoutes(db)
            }
        }
    }

    @Test
    fun `GET agents returns empty list initially`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            val response = client.get("/api/v1/agents")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertTrue(body.isEmpty(), "Should return empty list")
        }

    @Test
    fun `POST agents creates profile and GET returns it`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create
            val createResponse = client.post("/api/v1/agents") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"test-1","name":"TestAgent","provider":"openai","model":"gpt-4o","tools":["search"]}""")
            }
            assertEquals(HttpStatusCode.Created, createResponse.status)

            val created = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
            assertEquals("test-1", created["id"]?.jsonPrimitive?.content)
            assertEquals("TestAgent", created["name"]?.jsonPrimitive?.content)

            // Get single
            val getResponse = client.get("/api/v1/agents/test-1")
            assertEquals(HttpStatusCode.OK, getResponse.status)
            val fetched = json.parseToJsonElement(getResponse.bodyAsText()).jsonObject
            assertEquals("TestAgent", fetched["name"]?.jsonPrimitive?.content)
        }

    @Test
    fun `GET agents by unknown id returns 404`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            val response = client.get("/api/v1/agents/nonexistent")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `PUT agents updates existing profile`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create first
            client.post("/api/v1/agents") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"upd-1","name":"Original","provider":"openai","model":"gpt-4o"}""")
            }

            // Update
            val updateResponse = client.put("/api/v1/agents/upd-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Updated","provider":"anthropic","model":"claude-3"}""")
            }
            assertEquals(HttpStatusCode.OK, updateResponse.status)
            val updated = json.parseToJsonElement(updateResponse.bodyAsText()).jsonObject
            assertEquals("Updated", updated["name"]?.jsonPrimitive?.content)
            assertEquals("anthropic", updated["provider"]?.jsonPrimitive?.content)
        }

    @Test
    fun `DELETE agents removes profile`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create
            client.post("/api/v1/agents") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"del-1","name":"ToDelete","provider":"openai","model":"gpt-4o"}""")
            }

            // Delete
            val deleteResponse = client.delete("/api/v1/agents/del-1")
            assertEquals(HttpStatusCode.OK, deleteResponse.status)

            // Verify gone
            val getResponse = client.get("/api/v1/agents/del-1")
            assertEquals(HttpStatusCode.NotFound, getResponse.status)
        }

    @Test
    fun `DELETE system agent returns Forbidden`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create as system agent
            client.post("/api/v1/agents") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"sys-1","name":"System Agent","isSystem":true}""")
            }

            // Try to delete — should fail with 403
            val deleteResponse = client.delete("/api/v1/agents/sys-1")
            assertEquals(HttpStatusCode.Forbidden, deleteResponse.status)
        }

    @Test
    fun `DELETE custom agent succeeds`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create custom (non-system) agent
            client.post("/api/v1/agents") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"custom-1","name":"Custom Agent","isSystem":false}""")
            }

            // Delete should succeed
            val deleteResponse = client.delete("/api/v1/agents/custom-1")
            assertEquals(HttpStatusCode.OK, deleteResponse.status)

            // Verify it's gone
            val getResponse = client.get("/api/v1/agents/custom-1")
            assertEquals(HttpStatusCode.NotFound, getResponse.status)
        }

    // ── Ephemeral Agent Tests ──────────────────────────────────────────────

    @Test
    fun `POST creates ephemeral agent and GET returns ephemeral flag`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create ephemeral agent
            val createResponse = client.post("/api/v1/agents") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"eph-1","name":"Ephemeral Agent","provider":"openai","model":"gpt-4o","ephemeral":true}""")
            }
            assertEquals(HttpStatusCode.Created, createResponse.status)
            val created = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
            assertEquals("eph-1", created["id"]?.jsonPrimitive?.content)
            assertTrue(created["ephemeral"]?.jsonPrimitive?.boolean ?: false, "Created agent should be ephemeral")

            // GET should also return ephemeral=true
            val getResponse = client.get("/api/v1/agents/eph-1")
            assertEquals(HttpStatusCode.OK, getResponse.status)
            val fetched = json.parseToJsonElement(getResponse.bodyAsText()).jsonObject
            assertTrue(fetched["ephemeral"]?.jsonPrimitive?.boolean ?: false, "Fetched agent should be ephemeral")
            assertFalse(fetched["isSystem"]?.jsonPrimitive?.boolean ?: true, "Ephemeral agent should not be system")
        }

    @Test
    fun `DELETE ephemeral agent succeeds`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create ephemeral agent (non-system)
            client.post("/api/v1/agents") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"eph-del","name":"Ephemeral To Delete","ephemeral":true}""")
            }

            // Delete should succeed — ephemeral agents are NOT system-protected
            val deleteResponse = client.delete("/api/v1/agents/eph-del")
            assertEquals(HttpStatusCode.OK, deleteResponse.status)

            // Verify it's gone
            val getResponse = client.get("/api/v1/agents/eph-del")
            assertEquals(HttpStatusCode.NotFound, getResponse.status)
        }

    @Test
    fun `GET agents returns isSystem and ephemeral flags correctly`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create one of each type
            client.post("/api/v1/agents") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"sys-flag","name":"System","isSystem":true,"ephemeral":false}""")
            }
            client.post("/api/v1/agents") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"eph-flag","name":"Ephemeral","isSystem":false,"ephemeral":true}""")
            }
            client.post("/api/v1/agents") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"cust-flag","name":"Custom","isSystem":false,"ephemeral":false}""")
            }

            // GET all agents and verify flags
            val response = client.get("/api/v1/agents")
            assertEquals(HttpStatusCode.OK, response.status)
            val agents = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(3, agents.size, "Should have 3 agents")

            val sysAgent = agents.first { it.jsonObject["id"]?.jsonPrimitive?.content == "sys-flag" }.jsonObject
            assertTrue(sysAgent["isSystem"]?.jsonPrimitive?.boolean ?: false, "System agent isSystem should be true")
            assertFalse(sysAgent["ephemeral"]?.jsonPrimitive?.boolean ?: true, "System agent ephemeral should be false")

            val ephAgent = agents.first { it.jsonObject["id"]?.jsonPrimitive?.content == "eph-flag" }.jsonObject
            assertFalse(ephAgent["isSystem"]?.jsonPrimitive?.boolean ?: true, "Ephemeral agent isSystem should be false")
            assertTrue(ephAgent["ephemeral"]?.jsonPrimitive?.boolean ?: false, "Ephemeral agent ephemeral should be true")

            val custAgent = agents.first { it.jsonObject["id"]?.jsonPrimitive?.content == "cust-flag" }.jsonObject
            assertFalse(custAgent["isSystem"]?.jsonPrimitive?.boolean ?: true, "Custom agent isSystem should be false")
            assertFalse(custAgent["ephemeral"]?.jsonPrimitive?.boolean ?: true, "Custom agent ephemeral should be false")
        }

    // ── System Agent Protection Tests ──────────────────────────────────────

    @Test
    fun `PUT system agent preserves isSystem flag`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create system agent
            client.post("/api/v1/agents") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"sys-put","name":"System Agent","isSystem":true,"provider":"openai","model":"gpt-4o"}""")
            }

            // Update name but keep isSystem=true
            val updateResponse = client.put("/api/v1/agents/sys-put") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Updated System Agent","isSystem":true,"provider":"anthropic","model":"claude-3"}""")
            }
            assertEquals(HttpStatusCode.OK, updateResponse.status)
            val updated = json.parseToJsonElement(updateResponse.bodyAsText()).jsonObject
            assertEquals("Updated System Agent", updated["name"]?.jsonPrimitive?.content)
            assertEquals("anthropic", updated["provider"]?.jsonPrimitive?.content)
            assertTrue(updated["isSystem"]?.jsonPrimitive?.boolean ?: false, "isSystem flag should be preserved after update")

            // Verify via GET as well
            val getResponse = client.get("/api/v1/agents/sys-put")
            assertEquals(HttpStatusCode.OK, getResponse.status)
            val fetched = json.parseToJsonElement(getResponse.bodyAsText()).jsonObject
            assertTrue(fetched["isSystem"]?.jsonPrimitive?.boolean ?: false, "isSystem flag should persist via GET after update")
        }
}
