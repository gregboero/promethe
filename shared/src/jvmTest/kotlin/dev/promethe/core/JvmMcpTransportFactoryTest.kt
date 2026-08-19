package dev.promethe.core

import dev.promethe.api.ToolCallOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
class JvmMcpTransportFactoryTest {
    @Test
    fun `bridge transport factory preserves secured invocation for MCP elicitation`() =
        runTest {
            val broker = McpElicitationBroker(10_000, nextId = { "factory-input" })
            val factory =
                JvmMcpTransportFactory(broker) { config, handlers ->
                    assertEquals("calendar", config.id)
                    assertEquals(setOf("elicitation/create"), handlers.keys)
                    fakeTransport(handlers.getValue("elicitation/create"))
                }
            val bridge = McpBridge().also { it.setTransportFactory(factory) }
            bridge.registerServer(
                McpBridge.McpServerConfig(
                    id = "calendar",
                    name = "Calendar",
                    transport = "streamable-http",
                    url = "https://mcp.example.test",
                ),
            )
            assertEquals(1, bridge.connectServer("calendar").getOrThrow().size)

            val toolResult =
                async {
                    withContext(ToolInvocationContext(invocation("owner-session"))) {
                        bridge.executeToolCall("calendar", "confirm", buildJsonObject {})
                    }
                }
            runCurrent()
            val pending = broker.listPending().single()
            assertEquals("owner-session", pending.sessionId)
            assertEquals("Calendar", pending.serverName)

            assertEquals(
                McpElicitationBroker.ResponseResult.ACCEPTED,
                broker.respond(
                    pending.id,
                    "accept",
                    buildJsonObject { put("confirmed", true) },
                ),
            )
            val result = kotlinx.serialization.json.Json.parseToJsonElement(toolResult.await()).jsonObject
            assertEquals("accept", result["action"]?.jsonPrimitive?.content)
            bridge.removeServer("calendar")
        }

    private fun fakeTransport(handler: McpInputRequestHandler): McpBridge.McpTransportApi =
        object : McpBridge.McpTransportApi {
            override suspend fun initialize(): JsonObject = buildJsonObject {}

            override suspend fun listTools(): List<McpBridge.McpToolInfo> =
                listOf(
                    McpBridge.McpToolInfo(
                        serverId = "calendar",
                        toolName = "confirm",
                        description = "Confirm a calendar action",
                    ),
                )

            override suspend fun callTool(
                name: String,
                arguments: JsonObject,
            ): String = handler.fulfill("confirm", formRequest()).toString()

            override fun close() = Unit
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
