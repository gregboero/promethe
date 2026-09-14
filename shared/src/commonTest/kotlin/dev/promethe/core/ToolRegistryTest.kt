package dev.promethe.core

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolRegistryTest {
    @BeforeTest
    fun setup() =
        runTest {
            ToolRegistry.clear()
        }

    @AfterTest
    fun cleanup() =
        runTest {
            ToolRegistry.clear()
        }

    @Test
    fun testRegisterAndGetTool() =
        runTest {
            val fs = getFileSystem()
            val config = AgentConfig()
            val basePath = getProfileDirectoryPath(config)
            val tool = FileReadTool(fs, basePath)

            ToolRegistry.register(tool)

            val retrieved = ToolRegistry.getTool("read_file")
            assertNotNull(retrieved, "Should find registered tool")
        }

    @Test
    fun testListTools() =
        runTest {
            val fs = getFileSystem()
            val config = AgentConfig()
            val basePath = getProfileDirectoryPath(config)

            ToolRegistry.register(FileReadTool(fs, basePath))
            ToolRegistry.register(FileWriteTool(fs, basePath))

            val tools = ToolRegistry.listTools()
            assertEquals(2, tools.size)
        }

    @Test
    fun testClear() =
        runTest {
            val fs = getFileSystem()
            val config = AgentConfig()
            val basePath = getProfileDirectoryPath(config)

            ToolRegistry.register(FileReadTool(fs, basePath))
            assertEquals(1, ToolRegistry.listTools().size)

            ToolRegistry.clear()
            assertEquals(0, ToolRegistry.listTools().size)
        }

    @Test
    fun testGetToolNotFound() =
        runTest {
            val result = ToolRegistry.getTool("nonexistent_tool")
            assertNull(result, "Should return null for unknown tool")
        }

    @Test
    fun testToolsSnapshot() =
        runTest {
            val fs = getFileSystem()
            val config = AgentConfig()
            val basePath = getProfileDirectoryPath(config)

            ToolRegistry.register(FileReadTool(fs, basePath))

            val snapshot = ToolRegistry.toolsSnapshot()
            assertEquals(1, snapshot.size)

            // Snapshot is independent copy
            ToolRegistry.clear()
            assertEquals(1, snapshot.size, "Snapshot should not be affected by clear")
        }

    @Test
    fun `registered tool inventory has complete contracts`() =
        runTest {
            val fs = getFileSystem()
            val config = AgentConfig()
            val basePath = getProfileDirectoryPath(config)
            ToolRegistry.register(FileReadTool(fs, basePath))
            ToolRegistry.register(FileWriteTool(fs, basePath))

            val report = ToolRegistry.requireCompleteContractCoverage()

            assertTrue(report.valid)
            assertEquals(2, report.toolCount)
            assertEquals(1, report.effectfulToolCount)
        }
}
