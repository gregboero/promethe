package dev.promethe.core

import dev.promethe.api.ToolCallOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@OptIn(ExperimentalCoroutinesApi::class)
class McpElicitationBrokerTest {
    @Test
    fun `owner response resumes secured MCP elicitation with typed content`() =
        runTest {
            val broker =
                McpElicitationBroker(
                    timeoutMs = 10_000,
                    clock = { 100 },
                    nextId = { "input-1" },
                )
            val response =
                async {
                    withContext(ToolInvocationContext(invocation("chat-session"))) {
                        broker.handlerFor("calendar", "Calendar").fulfill("confirmation", formRequest())
                    }
                }
            runCurrent()

            val pending = broker.listPending().single()
            assertEquals("input-1", pending.id)
            assertEquals("chat-session", pending.sessionId)
            assertEquals("Calendar", pending.serverName)
            assertEquals(
                McpElicitationBroker.ResponseResult.ACCEPTED,
                broker.respond(
                    "input-1",
                    "accept",
                    buildJsonObject {
                        put("name", "Ada")
                        put("confirmed", true)
                    },
                ),
            )

            val result = response.await()
            assertEquals("accept", result["action"]?.jsonPrimitive?.content)
            assertEquals("Ada", result["content"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
            assertTrue(broker.listPending().isEmpty())
        }

    @Test
    fun `invalid content is rejected without consuming pending request`() =
        runTest {
            val broker = McpElicitationBroker(10_000, nextId = { "input-2" })
            val response =
                async {
                    withContext(ToolInvocationContext(invocation("session-a"))) {
                        broker.handlerFor("server", "Server").fulfill("key", formRequest())
                    }
                }
            runCurrent()

            assertEquals(
                McpElicitationBroker.ResponseResult.INVALID_CONTENT,
                broker.respond("input-2", "accept", buildJsonObject { put("confirmed", "yes") }),
            )
            assertEquals(1, broker.listPending().size)
            assertEquals(McpElicitationBroker.ResponseResult.ACCEPTED, broker.respond("input-2", "decline", null))
            assertEquals("decline", response.await()["action"]?.jsonPrimitive?.content)
        }

    @Test
    fun `numeric bounds are enforced without consuming pending request`() =
        runTest {
            val broker = McpElicitationBroker(10_000, nextId = { "input-score" })
            val request =
                formRequest(
                    properties =
                        buildJsonObject {
                            putJsonObject("score") {
                                put("type", "integer")
                                put("minimum", 1)
                                put("maximum", 5)
                            }
                        },
                    required = listOf("score"),
                )
            val response =
                async {
                    withContext(ToolInvocationContext(invocation("session-a"))) {
                        broker.handlerFor("server", "Server").fulfill("key", request)
                    }
                }
            runCurrent()

            assertEquals(
                McpElicitationBroker.ResponseResult.INVALID_CONTENT,
                broker.respond("input-score", "accept", buildJsonObject { put("score", 6) }),
            )
            assertEquals(1, broker.listPending().size)
            assertEquals(
                McpElicitationBroker.ResponseResult.ACCEPTED,
                broker.respond("input-score", "accept", buildJsonObject { put("score", 4) }),
            )
            assertEquals(4, response.await()["content"]?.jsonObject?.get("score")?.jsonPrimitive?.content?.toInt())
        }

    @Test
    fun `sensitive elicitation schema fails closed before reaching owner UI`() =
        runTest {
            val broker = McpElicitationBroker(10_000)
            val request =
                formRequest(
                    properties =
                        buildJsonObject {
                            putJsonObject("api_token") {
                                put("type", "string")
                                put("title", "API token")
                            }
                        },
                    required = listOf("api_token"),
                )

            assertFailsWith<IllegalArgumentException> {
                withContext(ToolInvocationContext(invocation("session-a"))) {
                    broker.handlerFor("server", "Server").fulfill("key", request)
                }
            }
            assertTrue(broker.listPending().isEmpty())
        }

    @Test
    fun `elicitation timeout returns cancel and removes pending request`() =
        runTest {
            val broker = McpElicitationBroker(1_000, nextId = { "input-timeout" })
            val response =
                async {
                    withContext(ToolInvocationContext(invocation("session-a"))) {
                        broker.handlerFor("server", "Server").fulfill("key", formRequest())
                    }
                }
            runCurrent()
            assertEquals(1, broker.listPending().size)

            advanceTimeBy(1_001)
            runCurrent()

            assertEquals("cancel", response.await()["action"]?.jsonPrimitive?.content)
            assertTrue(broker.listPending().isEmpty())
        }

    @Test
    fun `response at or after expiry is rejected while tool receives cancel`() =
        runTest {
            var now = 100L
            val broker =
                McpElicitationBroker(
                    timeoutMs = 1_000,
                    clock = { now },
                    nextId = { "input-expired" },
                )
            val response =
                async {
                    withContext(ToolInvocationContext(invocation("session-a"))) {
                        broker.handlerFor("server", "Server").fulfill("key", formRequest())
                    }
                }
            runCurrent()
            now = 1_100

            assertEquals(
                McpElicitationBroker.ResponseResult.NOT_FOUND,
                broker.respond(
                    "input-expired",
                    "accept",
                    buildJsonObject {
                        put("name", "Ada")
                        put("confirmed", true)
                    },
                ),
            )
            assertEquals("cancel", response.await()["action"]?.jsonPrimitive?.content)
            assertTrue(broker.listPending().isEmpty())
        }

    @Test
    fun `unsupported sensitive schema keywords fail closed`() =
        runTest {
            val broker = McpElicitationBroker(10_000)
            val request =
                formRequest(
                    properties =
                        buildJsonObject {
                            putJsonObject("value") {
                                put("type", "string")
                                put("format", "password")
                            }
                        },
                    required = listOf("value"),
                )

            assertFailsWith<IllegalArgumentException> {
                withContext(ToolInvocationContext(invocation("session-a"))) {
                    broker.handlerFor("server", "Server").fulfill("key", request)
                }
            }
            assertTrue(broker.listPending().isEmpty())
        }

    @Test
    fun `camel case and separated secret field names fail closed`() =
        runTest {
            val broker = McpElicitationBroker(10_000)

            listOf("apiKey", "private-key", "verificationCode").forEach { sensitiveName ->
                val request =
                    formRequest(
                        properties =
                            buildJsonObject {
                                putJsonObject(sensitiveName) { put("type", "string") }
                            },
                        required = listOf(sensitiveName),
                    )
                assertFailsWith<IllegalArgumentException> {
                    withContext(ToolInvocationContext(invocation("session-a"))) {
                        broker.handlerFor("server", "Server").fulfill("key", request)
                    }
                }
            }
            assertTrue(broker.listPending().isEmpty())
        }

    private fun invocation(sessionId: String) =
        ToolExecutionRequest(
            toolName = "mcp_server_tool",
            arguments = JsonObject(emptyMap()),
            sessionId = sessionId,
            origin = ToolCallOrigin.AGENT,
        )

    private fun formRequest(
        properties: JsonObject =
            buildJsonObject {
                putJsonObject("name") { put("type", "string") }
                putJsonObject("confirmed") { put("type", "boolean") }
            },
        required: List<String> = listOf("name", "confirmed"),
    ): JsonObject =
        buildJsonObject {
            put("method", "elicitation/create")
            putJsonObject("params") {
                put("mode", "form")
                put("message", "Continue?")
                putJsonObject("requestedSchema") {
                    put("type", "object")
                    put("properties", properties)
                    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
}
