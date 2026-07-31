package dev.promethe.gateway

import dev.promethe.core.acp.AcpAgentCard
import dev.promethe.core.acp.AcpResponse
import dev.promethe.core.acp.AcpStatus
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
 * Integration tests for AcpRoutes — agent card discovery, health check,
 * and capability invocation (success + failure paths) via a stubbed
 * [AcpExecutor] so no LLM is needed.
 */
class AcpRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class RecordingExecutor(
        private val reply: String,
    ) : AcpExecutor {
        var lastSessionId: String? = null
        var lastText: String? = null
        var lastChannelHint: String? = null

        override suspend fun execute(
            sessionId: String,
            text: String,
            channelHint: String,
        ): String {
            lastSessionId = sessionId
            lastText = text
            lastChannelHint = channelHint
            return reply
        }
    }

    private fun ApplicationTestBuilder.configureApp(executor: AcpExecutor) {
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
            acpRoutes(executor)
        }
    }

    // ── 1. Agent Card discovery ──────────────────────────────────

    @Test
    fun `GET well-known acp json returns the agent card`() =
        testApplication {
            configureApp(RecordingExecutor("unused"))

            val response = client.get("/.well-known/acp.json")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())

            val card = json.decodeFromString<AcpAgentCard>(response.bodyAsText())
            assertTrue(card.name.isNotBlank(), "Agent card must have a name")
            assertTrue(card.capabilities.isNotEmpty(), "Agent card must advertise capabilities")
        }

    // ── 2. Health check ──────────────────────────────────────────

    @Test
    fun `GET acp health reports ok and protocol version`() =
        testApplication {
            configureApp(RecordingExecutor("unused"))

            val response = client.get("/acp/health")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("ok", body["status"]?.jsonPrimitive?.content)
            assertEquals("ACP/1.0", body["protocol"]?.jsonPrimitive?.content)
        }

    // ── 3. Invoke — success path ─────────────────────────────────

    @Test
    fun `POST acp invoke returns COMPLETED with agent output`() =
        testApplication {
            val executor = RecordingExecutor("Hello from the agent")
            configureApp(executor)

            val response =
                client.post("/acp/invoke") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"capabilityId":"chat","input":"hi there","contextId":"ctx-42"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)

            val acp = json.decodeFromString<AcpResponse>(response.bodyAsText())
            assertEquals(AcpStatus.COMPLETED, acp.status)
            assertEquals("Hello from the agent", acp.output)
            assertNull(acp.error)

            assertEquals("acp-ctx-42", executor.lastSessionId, "Session id must derive from contextId")
            assertEquals("acp", executor.lastChannelHint)
            assertFalse(
                executor.lastText.orEmpty().contains("[ACP capability:"),
                "chat capability must not add a capability banner to the prompt",
            )
        }

    // ── 4. Invoke — non-chat capability banner ───────────────────

    @Test
    fun `POST acp invoke with non-chat capability prefixes the prompt`() =
        testApplication {
            val executor = RecordingExecutor("done")
            configureApp(executor)

            client.post("/acp/invoke") {
                contentType(ContentType.Application.Json)
                setBody("""{"capabilityId":"summarize","input":"long text"}""")
            }

            val prompt = executor.lastText.orEmpty()
            assertTrue(prompt.contains("[ACP capability: summarize]"), "Got prompt: $prompt")
            assertTrue(prompt.contains("long text"))
        }

    // ── 5. Invoke — failure path ─────────────────────────────────

    @Test
    fun `POST acp invoke maps agent error to FAILED and 500`() =
        testApplication {
            configureApp(RecordingExecutor("Error: model exploded"))

            val response =
                client.post("/acp/invoke") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"capabilityId":"chat","input":"hi"}""")
                }
            assertEquals(HttpStatusCode.InternalServerError, response.status)

            val acp = json.decodeFromString<AcpResponse>(response.bodyAsText())
            assertEquals(AcpStatus.FAILED, acp.status)
            assertEquals("Error: model exploded", acp.error)
        }

    // ── 6. Invoke — blank agent reply falls back to Done ─────────

    @Test
    fun `POST acp invoke with blank reply falls back to Done`() =
        testApplication {
            configureApp(RecordingExecutor(""))

            val response =
                client.post("/acp/invoke") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"capabilityId":"chat","input":"hi"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)

            val acp = json.decodeFromString<AcpResponse>(response.bodyAsText())
            assertEquals("Done.", acp.output)
            assertEquals(AcpStatus.COMPLETED, acp.status)
        }
}
