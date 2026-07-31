package dev.promethe.core

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltinToolsTest {
    private lateinit var fs: FileSystem
    private lateinit var basePath: Path

    @BeforeTest
    fun setup() {
        fs = getFileSystem()
        val config = AgentConfig()
        basePath = getProfileDirectoryPath(config) / "test_sandbox"
        if (!fs.exists(basePath)) {
            fs.createDirectories(basePath)
        }
    }

    @AfterTest
    fun teardown() {
        if (fs.exists(basePath)) {
            try {
                fs.deleteRecursively(basePath)
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun testFileWriteAndReadHappyPath() =
        runTest {
            val readTool = FileReadTool(fs, basePath)
            val writeTool = FileWriteTool(fs, basePath)

            val writeResult =
                writeTool.execute(
                    FileWriteArgs(path = "hello.txt", content = "Hello from KMP agent!"),
                )
            assertTrue(writeResult.contains("successfully"), "Write result should indicate success")

            val readResult =
                readTool.execute(
                    FileReadArgs(path = "hello.txt"),
                )
            assertEquals("Hello from KMP agent!", readResult)
        }

    @Test
    fun testFileReadFileNotFound() =
        runTest {
            val readTool = FileReadTool(fs, basePath)
            val result =
                readTool.execute(
                    FileReadArgs(path = "does_not_exist.txt"),
                )
            assertTrue(result.contains("[ERROR] File not found"), "Should return not found error")
        }

    @Test
    fun testPathTraversalRejection() =
        runTest {
            val readTool = FileReadTool(fs, basePath)
            val writeTool = FileWriteTool(fs, basePath)

            val traversalPaths =
                listOf(
                    "../outside.txt",
                    "/absolute/path.txt",
                    "C:\\windows\\win.ini",
                    "sub/../../traversal.txt",
                )

            for (path in traversalPaths) {
                val readResult = readTool.execute(FileReadArgs(path = path))
                assertTrue(readResult.contains("Access denied"), "Path traversal read should be blocked for: $path")

                val writeResult =
                    writeTool.execute(
                        FileWriteArgs(path = path, content = "should fail"),
                    )
                assertTrue(writeResult.contains("Access denied"), "Path traversal write should be blocked for: $path")
            }
        }

    @Test
    fun testHttpFetchErrorHandling() =
        runTest {
            // Test que HttpFetchTool capture les erreurs d'infrastructures réseau
            val badClient = HttpClient() // Client non configuré / URL invalide
            val fetchTool = HttpFetchTool(badClient)

            val result =
                fetchTool.execute(
                    HttpFetchArgs(url = "https://invalid-domain-name-that-does-not-exist.xyz"),
                )
            assertTrue(result.contains("[ERROR]"), "Should fail and return a structured error message")
            badClient.close()
        }
}
