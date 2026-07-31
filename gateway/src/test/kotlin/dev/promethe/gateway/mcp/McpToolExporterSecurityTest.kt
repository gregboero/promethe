package dev.promethe.gateway.mcp

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.promethe.core.SecureToolExecutor
import dev.promethe.core.ToolExecutionRequest
import dev.promethe.core.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class McpToolExporterSecurityTest {
    @AfterTest
    fun cleanup() =
        runTest {
            ToolRegistry.clear()
        }

    @Test
    fun `tool calls always use the secure executor`() =
        runTest {
            ToolRegistry.register(NeverDirectTool("echo"))
            var captured: ToolExecutionRequest? = null
            val exporter =
                McpToolExporter(
                    secureToolExecutor = SecureToolExecutor { request ->
                        captured = request
                        "secured"
                    },
                )

            val response =
                exporter.dispatch(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "tools/call")
                        put(
                            "params",
                            buildJsonObject {
                                put("name", "echo")
                                put("arguments", buildJsonObject { put("value", "hello") })
                            },
                        )
                    },
                    sessionId = "owner-session",
                )

            assertEquals("echo", captured?.toolName)
            assertEquals("owner-session", captured?.sessionId)
            assertEquals(
                "secured",
                response?.get("result")?.jsonObject
                    ?.get("content")?.jsonArray
                    ?.first()?.jsonObject
                    ?.get("text")?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `stdio style exporter does not advertise approval-required tools`() =
        runTest {
            ToolRegistry.register(NeverDirectTool("echo"))
            ToolRegistry.register(NeverDirectTool("shell"))
            val exporter = McpToolExporter(exposeApprovalRequiredTools = false)

            val response =
                exporter.dispatch(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 1)
                        put("method", "tools/list")
                    },
                )

            val names =
                response?.get("result")?.jsonObject
                    ?.get("tools")?.jsonArray
                    ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
            assertNotNull(names)
            assertEquals(true, "echo" in names)
            assertFalse("shell" in names)
        }

    @Serializable
    private data class TestArgs(
        val value: String = "",
    )

    private class NeverDirectTool(
        name: String,
    ) : SimpleTool<TestArgs>(
            argsType = typeToken<TestArgs>(),
            name = name,
            description = "Test tool that must never be called directly",
        ) {
        override suspend fun execute(args: TestArgs): String = error("direct execution bypassed SecureToolExecutor")
    }
}
