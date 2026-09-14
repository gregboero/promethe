package dev.promethe.db

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class McpTaskPersistenceTest {
    @Test
    fun `MCP tasks survive a database reopen and transitions are owner bound`() =
        runTest {
            val directory = createTempDirectory("promethe-mcp-task").toFile()
            val url = "jdbc:sqlite:${java.io.File(directory, "promethe.db").absolutePath}"
            try {
                val database = DatabaseFactory.create(url)
                val task =
                    McpTaskRecord(
                        taskId = "durable-task",
                        ownerSessionId = "owner-session",
                        method = "tools/call",
                        resourceName = "web_crawl",
                        runId = "run-1",
                        status = McpTaskStatus.WORKING,
                        createdAt = 100,
                        lastUpdatedAt = 100,
                        ttlMs = 60_000,
                        pollIntervalMs = 250,
                    )
                assertTrue(database.insertMcpTask(task))

                val reopened = DatabaseFactory.create(url)
                assertEquals(task, reopened.getMcpTask("durable-task", "owner-session"))
                assertFalse(
                    reopened.updateMcpTask(
                        taskId = "durable-task",
                        ownerSessionId = "other-session",
                        expectedStatuses = setOf(McpTaskStatus.WORKING),
                        status = McpTaskStatus.CANCELLED,
                        statusMessage = "wrong owner",
                        resultJson = null,
                        errorJson = null,
                        inputRequestsJson = null,
                        lastUpdatedAt = 200,
                    ),
                )
                assertTrue(
                    reopened.updateMcpTask(
                        taskId = "durable-task",
                        ownerSessionId = "owner-session",
                        expectedStatuses = setOf(McpTaskStatus.WORKING),
                        status = McpTaskStatus.COMPLETED,
                        statusMessage = "done",
                        resultJson = "{}",
                        errorJson = null,
                        inputRequestsJson = null,
                        lastUpdatedAt = 200,
                    ),
                )
                assertEquals(McpTaskStatus.COMPLETED, reopened.getMcpTask("durable-task", "owner-session")?.status)
            } finally {
                directory.deleteRecursively()
            }
        }
}
