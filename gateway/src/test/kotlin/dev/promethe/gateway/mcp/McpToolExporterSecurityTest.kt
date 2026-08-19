package dev.promethe.gateway.mcp

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.promethe.core.McpProtocol
import dev.promethe.core.SecureToolExecutor
import dev.promethe.core.ToolExecutionRequest
import dev.promethe.core.ToolRegistry
import dev.promethe.core.requestToolInput
import dev.promethe.db.DatabaseFactory
import dev.promethe.db.McpTaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `modern tasks are negotiated and preserve synchronous fallback`() =
        runTest {
            ToolRegistry.register(NeverDirectTool("web_crawl"))
            val manager =
                McpTaskManager(
                    database = DatabaseFactory.createInMemory(),
                    scope = this,
                    taskEligibleTools = setOf("web_crawl"),
                    nextTaskId = { "task-export" },
                )
            var executions = 0
            val exporter =
                McpToolExporter(
                    secureToolExecutor = SecureToolExecutor {
                        executions++
                        "secured"
                    },
                    taskManager = manager,
                )

            val discovery =
                exporter.dispatch(
                    request("server/discover", buildJsonObject {}),
                    protocolVersion = McpProtocol.MODERN_VERSION,
                )!!["result"]!!.jsonObject
            assertNotNull(
                discovery["capabilities"]!!.jsonObject["extensions"]!!.jsonObject[McpProtocol.TASKS_EXTENSION],
            )

            val synchronous =
                exporter.dispatch(
                    request("tools/call", toolCallParams(includeTasks = false)),
                    sessionId = "owner-a",
                    protocolVersion = McpProtocol.MODERN_VERSION,
                )!!["result"]!!.jsonObject
            assertEquals(McpProtocol.RESULT_COMPLETE, synchronous["resultType"]?.jsonPrimitive?.content)
            assertEquals(1, executions)

            val asynchronous =
                exporter.dispatch(
                    request("tools/call", toolCallParams(includeTasks = true), id = 2),
                    sessionId = "owner-a",
                    protocolVersion = McpProtocol.MODERN_VERSION,
                )!!["result"]!!.jsonObject
            assertEquals(McpProtocol.RESULT_TASK, asynchronous["resultType"]?.jsonPrimitive?.content)
            assertEquals("task-export", asynchronous["taskId"]?.jsonPrimitive?.content)
            advanceUntilIdle()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) {
                    while (manager.getTask("task-export", "owner-a")?.status?.name == "WORKING") {
                        delay(10)
                    }
                }
            }
            assertEquals(2, executions)
            assertEquals("COMPLETED", manager.getTask("task-export", "owner-a")?.status?.name)
        }

    @Test
    fun `task methods reject missing per-request capability`() =
        runTest {
            val exporter =
                McpToolExporter(
                    secureToolExecutor = SecureToolExecutor { "unused" },
                    taskManager = McpTaskManager(DatabaseFactory.createInMemory(), this),
                )

            val response =
                exporter.dispatch(
                    request(
                        "tasks/get",
                        buildJsonObject { put("taskId", "unknown") },
                    ),
                    sessionId = "owner-a",
                    protocolVersion = McpProtocol.MODERN_VERSION,
                )

            assertEquals(-32003, response!!["error"]!!.jsonObject["code"]?.jsonPrimitive?.content?.toInt())
        }

    @Test
    fun `invalid JSON RPC envelopes and params are rejected precisely`() =
        runTest {
            val exporter = McpToolExporter()

            val invalidEnvelope =
                exporter.dispatch(
                    buildJsonObject {
                        put("id", 1)
                        put("method", "tools/list")
                    },
                )
            assertEquals(-32600, invalidEnvelope!!["error"]!!.jsonObject["code"]?.jsonPrimitive?.content?.toInt())

            val invalidParams =
                exporter.dispatch(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 2)
                        put("method", "tools/list")
                        put("params", buildJsonArray {})
                    },
                )
            assertEquals(-32602, invalidParams!!["error"]!!.jsonObject["code"]?.jsonPrimitive?.content?.toInt())
        }

    @Test
    fun `durable task input bridge reaches the real secure executor`() =
        runTest {
            ToolRegistry.register(NeverDirectTool("web_crawl"))
            val manager =
                McpTaskManager(
                    database = DatabaseFactory.createInMemory(),
                    scope = this,
                    taskEligibleTools = setOf("web_crawl"),
                    nextTaskId = { "task-bridge" },
                )
            val exporter =
                McpToolExporter(
                    secureToolExecutor = SecureToolExecutor {
                        val responses =
                            requestToolInput(
                                buildJsonObject {
                                    putJsonObject("name") {
                                        put("method", "elicitation/create")
                                        putJsonObject("params") { put("message", "Your name?") }
                                    }
                                },
                            )
                        "Hello ${responses["name"]!!.jsonObject["value"]!!.jsonPrimitive.content}"
                    },
                    taskManager = manager,
                )

            val created =
                exporter.dispatch(
                    request("tools/call", toolCallParams(includeTasks = true)),
                    sessionId = "owner-a",
                    protocolVersion = McpProtocol.MODERN_VERSION,
                )!!["result"]!!.jsonObject
            assertEquals(McpProtocol.RESULT_TASK, created["resultType"]?.jsonPrimitive?.content)
            advanceUntilIdle()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) {
                    while (manager.getTask("task-bridge", "owner-a")?.status != McpTaskStatus.INPUT_REQUIRED) {
                        delay(10)
                    }
                }
            }
            manager.handleTasksUpdate(
                buildJsonObject {
                    put("taskId", "task-bridge")
                    putJsonObject("inputResponses") {
                        putJsonObject("name") { put("value", "Promethe") }
                    }
                },
                "owner-a",
            )
            advanceUntilIdle()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) {
                    while (manager.getTask("task-bridge", "owner-a")?.status != McpTaskStatus.COMPLETED) {
                        delay(10)
                    }
                }
            }

            val result =
                manager.handleTasksGet(
                    buildJsonObject { put("taskId", "task-bridge") },
                    "owner-a",
                )["result"]!!.jsonObject
            assertEquals("Hello Promethe", result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
        }

    private fun request(
        method: String,
        params: kotlinx.serialization.json.JsonObject,
        id: Int = 1,
    ) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        put("params", params)
    }

    private fun toolCallParams(includeTasks: Boolean) =
        buildJsonObject {
            put("name", "web_crawl")
            putJsonObject("arguments") {}
            if (includeTasks) {
                putJsonObject("_meta") {
                    putJsonObject(McpProtocol.CLIENT_CAPABILITIES_META) {
                        putJsonObject("extensions") {
                            putJsonObject(McpProtocol.TASKS_EXTENSION) {}
                        }
                    }
                }
            }
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
