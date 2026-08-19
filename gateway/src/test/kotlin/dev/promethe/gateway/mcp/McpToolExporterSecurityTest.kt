package dev.promethe.gateway.mcp

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.promethe.core.McpProtocol
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
import kotlin.test.assertTrue

class McpToolExporterSecurityTest {
    @AfterTest
    fun cleanup() =
        runTest {
            ToolRegistry.clear()
        }

    @Test
    fun `tool calls always use the secure executor`() =
        runTest {
            ToolRegistry.register(NeverDirectTool("read_file"))
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
                                put("name", "read_file")
                                put("arguments", buildJsonObject { put("value", "hello") })
                            },
                        )
                    },
                    sessionId = "owner-session",
                )

            assertEquals("read_file", captured?.toolName)
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
    fun `unknown tools remain hidden with an interactive executor`() =
        runTest {
            ToolRegistry.register(NeverDirectTool("unknown_dynamic_tool"))
            val exporter = McpToolExporter(secureToolExecutor = SecureToolExecutor { "unexpected" })

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
            assertFalse("unknown_dynamic_tool" in names)
        }

    @Test
    fun `stdio style exporter does not advertise approval-required tools`() =
        runTest {
            ToolRegistry.register(NeverDirectTool("read_file"))
            ToolRegistry.register(NeverDirectTool("unknown_dynamic_tool"))
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
            assertEquals(true, "read_file" in names)
            assertFalse("unknown_dynamic_tool" in names)
            assertFalse("shell" in names)
        }

    @Test
    fun `modern tool catalogs are deterministic cacheable and use 2020-12 schemas`() =
        runTest {
            ToolRegistry.register(NeverDirectTool("workspace_roots"))
            ToolRegistry.register(NeverDirectTool("directory_tree"))
            val exporter = McpToolExporter(secureToolExecutor = SecureToolExecutor { "unused" })

            val response =
                exporter.dispatch(
                    request =
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", 1)
                            put("method", "tools/list")
                        },
                    protocolVersion = McpProtocol.MODERN_VERSION,
                )

            val result = response?.get("result")?.jsonObject
            assertNotNull(result)
            assertEquals("complete", result["resultType"]?.jsonPrimitive?.content)
            assertEquals("private", result["cacheScope"]?.jsonPrimitive?.content)
            assertTrue(result["ttlMs"]?.jsonPrimitive?.content?.toLongOrNull()!! > 0)
            val tools = result["tools"]!!.jsonArray.map { it.jsonObject }
            val names = tools.map { it["name"]!!.jsonPrimitive.content }
            assertEquals(names.sorted(), names)
            assertTrue("directory_tree" in names)
            assertTrue("workspace_roots" in names)
            tools.forEach { tool ->
                val schema = tool["inputSchema"]!!.jsonObject
                assertEquals(
                    "https://json-schema.org/draft/2020-12/schema",
                    schema["\$schema"]?.jsonPrimitive?.content,
                )
                assertEquals(false, schema["additionalProperties"]?.jsonPrimitive?.content?.toBooleanStrict())
            }
            assertNotNull(result["_meta"]?.jsonObject?.get(McpProtocol.SERVER_INFO_META))
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
