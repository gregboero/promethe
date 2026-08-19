package dev.promethe.gateway.mcp

import dev.promethe.core.McpProtocol
import dev.promethe.db.DatabaseFactory
import dev.promethe.db.McpTaskRecord
import dev.promethe.db.McpTaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@OptIn(ExperimentalCoroutinesApi::class)
class McpTaskManagerTest {
    @Test
    fun `task completion is durable and isolated to its owner session`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val manager =
                McpTaskManager(
                    database = database,
                    scope = this,
                    taskEligibleTools = setOf("web_crawl"),
                    nextTaskId = { "task-1" },
                )

            val task =
                manager.createTask("owner-a", "web_crawl") {
                    buildJsonObject { put("answer", "complete") }
                }
            assertEquals(McpTaskStatus.WORKING, task.status)
            assertEquals(McpProtocol.RESULT_TASK, manager.buildCreateTaskResult(task)["resultType"]?.jsonPrimitive?.content)

            advanceUntilIdle()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) {
                    while (manager.getTask("task-1", "owner-a")?.status == McpTaskStatus.WORKING) {
                        delay(10)
                    }
                }
            }

            val stored = manager.getTask("task-1", "owner-a")
            assertEquals(McpTaskStatus.COMPLETED, stored?.status)
            assertNull(manager.getTask("task-1", "owner-b"))
            val detail =
                manager.handleTasksGet(
                    buildJsonObject { put("taskId", "task-1") },
                    "owner-a",
                )
            assertEquals("complete", detail["result"]?.jsonObject?.get("answer")?.jsonPrimitive?.content)
        }

    @Test
    fun `task cancellation cancels the running job and persists the terminal state`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val manager =
                McpTaskManager(
                    database = database,
                    scope = this,
                    taskEligibleTools = setOf("web_crawl"),
                    nextTaskId = { "task-cancel" },
                )

            manager.createTask("owner-a", "web_crawl") {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            started.await()
            assertFailsWith<IllegalArgumentException> {
                manager.handleTasksCancel(
                    buildJsonObject { put("taskId", "task-cancel") },
                    "owner-b",
                )
            }
            assertEquals(McpTaskStatus.WORKING, manager.getTask("task-cancel", "owner-a")?.status)
            manager.handleTasksCancel(
                buildJsonObject { put("taskId", "task-cancel") },
                "owner-a",
            )
            advanceUntilIdle()

            assertTrue(cancelled.isCompleted)
            assertEquals(McpTaskStatus.CANCELLED, manager.getTask("task-cancel", "owner-a")?.status)
        }

    @Test
    fun `active tasks from a previous process fail closed on restart`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            assertTrue(
                database.insertMcpTask(
                    McpTaskRecord(
                        taskId = "interrupted-task",
                        ownerSessionId = "owner-a",
                        method = "tools/call",
                        resourceName = "web_crawl",
                        status = McpTaskStatus.WORKING,
                        createdAt = 10,
                        lastUpdatedAt = 10,
                        ttlMs = 60_000,
                    ),
                ),
            )
            val manager = McpTaskManager(database, this, now = { 20 })

            val recovered = manager.getTask("interrupted-task", "owner-a")

            assertEquals(McpTaskStatus.FAILED, recovered?.status)
            assertTrue(recovered?.errorJson.orEmpty().contains("interrupted"))
        }

    @Test
    fun `task input responses resume the suspended execution`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val manager =
                McpTaskManager(
                    database = database,
                    scope = this,
                    taskEligibleTools = setOf("web_crawl"),
                    nextTaskId = { "task-input" },
                )
            manager.createTask("owner-a", "web_crawl") {
                val responses =
                    requestInput(
                        buildJsonObject {
                            putJsonObject("first") {
                                put("method", "elicitation/create")
                                putJsonObject("params") { put("message", "First?") }
                            }
                            putJsonObject("second") {
                                put("method", "elicitation/create")
                                putJsonObject("params") { put("message", "Second?") }
                            }
                        },
                    )
                buildJsonObject {
                    put("first", responses["first"]!!.jsonObject["value"]!!.jsonPrimitive.content)
                    put("second", responses["second"]!!.jsonObject["value"]!!.jsonPrimitive.content)
                }
            }
            advanceUntilIdle()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) {
                    while (manager.getTask("task-input", "owner-a")?.status != McpTaskStatus.INPUT_REQUIRED) {
                        delay(10)
                    }
                }
            }

            assertFailsWith<IllegalArgumentException> {
                manager.handleTasksUpdate(
                    buildJsonObject {
                        put("taskId", "task-input")
                        putJsonObject("inputResponses") {
                            putJsonObject("first") { put("value", "stolen") }
                        }
                    },
                    "owner-b",
                )
            }
            manager.handleTasksUpdate(
                buildJsonObject {
                    put("taskId", "task-input")
                    putJsonObject("inputResponses") {
                        putJsonObject("first") { put("value", "one") }
                        putJsonObject("unknown") { put("value", "ignored") }
                    }
                },
                "owner-a",
            )
            assertEquals(McpTaskStatus.INPUT_REQUIRED, manager.getTask("task-input", "owner-a")?.status)
            manager.handleTasksUpdate(
                buildJsonObject {
                    put("taskId", "task-input")
                    putJsonObject("inputResponses") {
                        putJsonObject("second") { put("value", "two") }
                    }
                },
                "owner-a",
            )
            advanceUntilIdle()
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(2_000) {
                    while (manager.getTask("task-input", "owner-a")?.status != McpTaskStatus.COMPLETED) {
                        delay(10)
                    }
                }
            }

            val detail =
                manager.handleTasksGet(
                    buildJsonObject { put("taskId", "task-input") },
                    "owner-a",
                )
            assertEquals("one", detail["result"]!!.jsonObject["first"]!!.jsonPrimitive.content)
            assertEquals("two", detail["result"]!!.jsonObject["second"]!!.jsonPrimitive.content)
        }
}
