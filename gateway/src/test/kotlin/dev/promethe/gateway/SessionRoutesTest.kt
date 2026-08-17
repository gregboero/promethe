package dev.promethe.gateway

import dev.promethe.api.*
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
 * Integration tests for SessionRoutes — CRUD, message history,
 * internal-session filtering, and idempotent delete.
 *
 * Uses [FakeDatabase] so no real SQLite is needed.
 */
class SessionRoutesTest {
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
                sessionRoutes(db)
            }
        }
    }

    // ── 1. GET sessions returns empty list initially ─────────────

    @Test
    fun `GET sessions returns empty list initially`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            val response = client.get("/api/v1/sessions")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<SessionListResponse>(response.bodyAsText())
            assertTrue(body.sessions.isEmpty(), "Sessions list should be empty initially")
        }

    // ── 2. POST sessions creates new session ─────────────────────

    @Test
    fun `POST sessions creates new session`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            val response = client.post("/api/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody("""{}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)

            val info = json.decodeFromString<SessionInfo>(response.bodyAsText())
            assertTrue(info.id.startsWith("session-"), "Auto-generated id should start with 'session-'")
            assertTrue(info.createdAt > 0, "createdAt should be a positive timestamp")
            assertEquals(0, info.messageCount)
        }

    // ── 3. POST sessions with custom id ──────────────────────────

    @Test
    fun `POST sessions with custom id`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            val response = client.post("/api/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"my-custom-session"}""")
            }
            assertEquals(HttpStatusCode.Created, response.status)

            val info = json.decodeFromString<SessionInfo>(response.bodyAsText())
            assertEquals("my-custom-session", info.id)
        }

    // ── 4. GET sessions returns created sessions ─────────────────

    @Test
    fun `GET sessions returns created sessions`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create two sessions
            client.post("/api/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"sess-1"}""")
            }
            client.post("/api/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"sess-2"}""")
            }

            val response = client.get("/api/v1/sessions")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<SessionListResponse>(response.bodyAsText())
            assertEquals(2, body.sessions.size, "Should return 2 created sessions")

            val ids = body.sessions.map { it.id }.toSet()
            assertTrue("sess-1" in ids, "Should contain sess-1")
            assertTrue("sess-2" in ids, "Should contain sess-2")
        }

    // ── 5. GET sessions filters internal sessions ────────────────

    @Test
    fun `GET sessions filters internal sessions`() =
        testApplication {
            val db = FakeDatabase()

            // Pre-populate with internal sessions that should be filtered
            db.insertSessionOrIgnore("sub-123", 1000L, null)
            db.insertSessionOrIgnore("profile-abc", 1001L, null)
            db.insertSessionOrIgnore("a2a-task-1", 1002L, null)
            db.insertSessionOrIgnore("ctx-xyz", 1003L, null)
            // A session with "parent" in metadata should also be filtered
            db.insertSessionOrIgnore("child-session", 1004L, """{"parent":"main-session"}""")
            // A normal session that should NOT be filtered
            db.insertSessionOrIgnore("user-session", 1005L, null)

            configureApp(db)

            val response = client.get("/api/v1/sessions")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<SessionListResponse>(response.bodyAsText())
            assertEquals(1, body.sessions.size, "Only the non-internal session should remain")
            assertEquals("user-session", body.sessions.single().id)
        }

    // ── 6. DELETE session removes it ─────────────────────────────

    @Test
    fun `DELETE session removes it`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create a session
            client.post("/api/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"to-delete"}""")
            }

            // Delete it
            val delResponse = client.delete("/api/v1/sessions/to-delete")
            assertEquals(HttpStatusCode.OK, delResponse.status)

            val delBody = json.decodeFromString<Map<String, String>>(delResponse.bodyAsText())
            assertEquals("deleted", delBody["status"])

            // Verify it's gone
            val listResponse = client.get("/api/v1/sessions")
            val body = json.decodeFromString<SessionListResponse>(listResponse.bodyAsText())
            assertTrue(body.sessions.none { it.id == "to-delete" }, "Deleted session should not appear")
        }

    // ── 7. GET messages for empty session ────────────────────────

    @Test
    fun `GET messages for empty session returns empty list`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            val response = client.get("/api/v1/sessions/nonexistent/messages")
            assertEquals(HttpStatusCode.OK, response.status)

            val events = json.decodeFromString<List<ChatEvent>>(response.bodyAsText())
            assertTrue(events.isEmpty(), "Messages for a non-existent session should be empty")
        }

    @Test
    fun `GET messages restores only conversation roles with original timestamps`() =
        testApplication {
            val db = FakeDatabase()
            db.insertSessionOrIgnore("weather-session", 900L, null)
            db.insertMessage("weather-session", "user", "Weather tomorrow?", 1_000L)
            db.insertMessage("weather-session", "system", "Observation: raw search result", 1_100L)
            db.insertMessage("weather-session", "assistant", "Tomorrow will be 21 C.", 1_200L)
            configureApp(db)

            val response = client.get("/api/v1/sessions/weather-session/messages")
            assertEquals(HttpStatusCode.OK, response.status)

            val responseBody = response.bodyAsText()
            val events = json.decodeFromString<List<ChatEvent>>(responseBody)
            assertEquals(listOf("user", "response"), events.map { it.type })
            assertEquals(listOf("Weather tomorrow?", "Tomorrow will be 21 C."), events.map { it.content })
            assertEquals(listOf(1_000L, 1_200L), events.map { it.timestamp })
            assertFalse(responseBody.contains("Observation:"))
        }

    // ── 8. DELETE non-existent session returns 200 ───────────────

    @Test
    fun `DELETE non-existent session returns 200`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            val response = client.delete("/api/v1/sessions/does-not-exist")
            assertEquals(HttpStatusCode.OK, response.status, "Delete should be idempotent — 200 even if missing")

            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("deleted", body["status"])
        }
}
