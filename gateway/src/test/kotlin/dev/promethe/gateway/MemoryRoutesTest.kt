package dev.promethe.gateway

import dev.promethe.core.AgentConfig
import dev.promethe.core.KoogLlmAdapter
import dev.promethe.core.MemoryLayer
import dev.promethe.core.memory.EmbeddedMemoryProvider
import dev.promethe.db.DatabaseFactory
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
 * Integration tests for MemoryRoutes — CRUD on memory facts and the status
 * endpoint, backed by the real EmbeddedMemoryProvider on an in-memory SQLite.
 */
class MemoryRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.configureApp(): MemoryLayer {
        val database = DatabaseFactory.createInMemory()
        val memoryLayer =
            MemoryLayer(
                database = database,
                llmAdapter = KoogLlmAdapter(AgentConfig(), database),
                provider = EmbeddedMemoryProvider(database),
            )
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
                memoryRoutes(memoryLayer)
            }
        }
        return memoryLayer
    }

    // ── 1. Empty list initially ──────────────────────────────────

    @Test
    fun `GET facts returns empty list initially`() =
        testApplication {
            configureApp()

            val response = client.get("/api/v1/memory/facts")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<MemoryFactsResponse>(response.bodyAsText())
            assertEquals(0, body.total)
            assertTrue(body.facts.isEmpty())
        }

    // ── 2. Create then list ──────────────────────────────────────

    @Test
    fun `POST fact creates it and GET returns it`() =
        testApplication {
            configureApp()

            val created =
                client.post("/api/v1/memory/facts") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"category":"preference","content":"Prefers Kotlin over Java"}""")
                }
            assertEquals(HttpStatusCode.Created, created.status)

            val list = client.get("/api/v1/memory/facts")
            val body = json.decodeFromString<MemoryFactsResponse>(list.bodyAsText())
            assertEquals(1, body.total)
            assertEquals("preference", body.facts[0].category)
            assertEquals("Prefers Kotlin over Java", body.facts[0].content)
            assertEquals("ATOMIC", body.facts[0].tier, "Default tier should be ATOMIC")
        }

    // ── 3. Invalid tier falls back to ATOMIC ─────────────────────

    @Test
    fun `POST fact with invalid tier defaults to ATOMIC`() =
        testApplication {
            configureApp()

            val created =
                client.post("/api/v1/memory/facts") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"category":"misc","content":"x","tier":"not-a-tier"}""")
                }
            assertEquals(HttpStatusCode.Created, created.status)

            val body = json.decodeFromString<MemoryFactsResponse>(client.get("/api/v1/memory/facts").bodyAsText())
            assertEquals("ATOMIC", body.facts[0].tier)
        }

    // ── 4. Update existing / unknown ─────────────────────────────

    @Test
    fun `PUT updates existing fact and 404s on unknown id`() =
        testApplication {
            configureApp()

            client.post("/api/v1/memory/facts") {
                contentType(ContentType.Application.Json)
                setBody("""{"category":"project","content":"Original content"}""")
            }
            val id = json.decodeFromString<MemoryFactsResponse>(client.get("/api/v1/memory/facts").bodyAsText()).facts[0].id

            val updated =
                client.put("/api/v1/memory/facts/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"Updated content"}""")
                }
            assertEquals(HttpStatusCode.OK, updated.status)

            val after = json.decodeFromString<MemoryFactsResponse>(client.get("/api/v1/memory/facts").bodyAsText())
            assertEquals("Updated content", after.facts[0].content)
            assertEquals("project", after.facts[0].category, "Unspecified fields must be preserved")

            val missing =
                client.put("/api/v1/memory/facts/nonexistent-id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"content":"whatever"}""")
                }
            assertEquals(HttpStatusCode.NotFound, missing.status)
        }

    // ── 5. Delete ────────────────────────────────────────────────

    @Test
    fun `DELETE removes the fact`() =
        testApplication {
            configureApp()

            client.post("/api/v1/memory/facts") {
                contentType(ContentType.Application.Json)
                setBody("""{"category":"tmp","content":"To be deleted"}""")
            }
            val id = json.decodeFromString<MemoryFactsResponse>(client.get("/api/v1/memory/facts").bodyAsText()).facts[0].id

            val deleted = client.delete("/api/v1/memory/facts/$id")
            assertEquals(HttpStatusCode.OK, deleted.status)

            val after = json.decodeFromString<MemoryFactsResponse>(client.get("/api/v1/memory/facts").bodyAsText())
            assertEquals(0, after.total)
        }

    // ── 6. Status endpoint ───────────────────────────────────────

    @Test
    fun `GET status reports provider and fact count`() =
        testApplication {
            configureApp()

            client.post("/api/v1/memory/facts") {
                contentType(ContentType.Application.Json)
                setBody("""{"category":"a","content":"one"}""")
            }

            val response = client.get("/api/v1/memory/status")
            assertEquals(HttpStatusCode.OK, response.status)

            val status = json.decodeFromString<MemoryStatusResponse>(response.bodyAsText())
            assertEquals("embedded-sqlite", status.provider)
            assertEquals(1, status.factCount)
        }

    // ── 7. Search query filters ──────────────────────────────────

    @Test
    fun `GET facts with query returns matching facts`() =
        testApplication {
            configureApp()

            client.post("/api/v1/memory/facts") {
                contentType(ContentType.Application.Json)
                setBody("""{"category":"lang","content":"User codes in Kotlin"}""")
            }
            client.post("/api/v1/memory/facts") {
                contentType(ContentType.Application.Json)
                setBody("""{"category":"food","content":"Likes poutine"}""")
            }

            val response = client.get("/api/v1/memory/facts?q=Kotlin")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<MemoryFactsResponse>(response.bodyAsText())
            assertEquals("Kotlin", body.query)
            assertTrue(body.facts.all { it.content.contains("Kotlin") }, "Only matching facts expected, got: ${body.facts}")
        }
}
