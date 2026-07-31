package dev.promethe.core.tools.data

import dev.promethe.api.SandboxErrorCode
import dev.promethe.api.SandboxedExecutionResult
import dev.promethe.core.AgentConfig
import dev.promethe.core.JvmOutboundUrlPolicy
import dev.promethe.core.PinnedJvmOutboundHttpFetcher
import dev.promethe.core.SecureJvmWorkspaceFileReader
import dev.promethe.core.SecureJvmWorkspaceFileWriter
import dev.promethe.core.sandbox.SandboxProcessLauncher
import dev.promethe.core.sandbox.SandboxedCommandRunner
import java.io.File
import java.net.InetAddress
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class DataToolsTest {
    private lateinit var tempDir: File
    private lateinit var workDirStr: String

    @BeforeTest
    fun setup() {
        tempDir = File("build/tmp/test_data_${System.currentTimeMillis()}").canonicalFile
        tempDir.mkdirs()
        workDirStr = tempDir.absolutePath
    }

    @AfterTest
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testJsonQueryTool() =
        runTest {
            val tool = JsonQueryTool()
            val json =
                """
                {
                  "user": {
                    "name": "Alice",
                    "emails": ["alice@gmail.com", "alice@work.com"]
                  }
                }
                """.trimIndent()

            val nameResult = tool.execute(JsonQueryArgs(json = json, query = ".user.name"))
            assertEquals("\"Alice\"", nameResult)

            val emailResult = tool.execute(JsonQueryArgs(json = json, query = ".user.emails[1]"))
            assertEquals("\"alice@work.com\"", emailResult)
        }

    @Test
    fun testCsvTool() =
        runTest {
            val tool =
                CsvTool(
                    workDirStr,
                    SecureJvmWorkspaceFileReader(workDirStr),
                    SecureJvmWorkspaceFileWriter(workDirStr),
                )
            val csvPath = "data.csv"

            // Write CSV
            val writeResult = tool.execute(CsvArgs(action = "write", path = csvPath, data = "id,name,age\n1,Alice,30\n2,Bob,25"))
            if (writeResult.contains("unavailable on this filesystem")) return@runTest
            assertTrue(writeResult.contains("Written"), "Should show lines written")

            // Read CSV
            val readResult = tool.execute(CsvArgs(action = "read", path = csvPath))
            assertTrue(readResult.contains("Alice") && readResult.contains("Bob"), "Read result should contain headers and rows")

            // Query CSV
            val queryResult = tool.execute(CsvArgs(action = "query", path = csvPath, data = "name=Alice"))
            assertTrue(queryResult.contains("1,Alice,30"), "Query should match filters")
        }

    @Test
    fun testPdfReaderTool() =
        runTest {
            val runner =
                SandboxedCommandRunner(
                    launcher = SandboxProcessLauncher { request ->
                        SandboxedExecutionResult(
                            executionId = request.executionId,
                            errorCode = SandboxErrorCode.INTERNAL_ERROR,
                        )
                    },
                    workspaceRoot = workDirStr,
                    config = AgentConfig(),
                )
            val tool = PdfReaderTool(workDirStr, runner)
            // Verify tool returns error since file doesn't exist, and check metadata
            assertEquals("pdf_reader", tool.name)
            val result = tool.execute(PdfReaderArgs(path = "nonexistent.pdf"))
            assertTrue(result.contains("[ERROR] File not found"), "Should fail gracefully on missing PDF")
        }

    @Test
    fun testApiCallTool() =
        runTest {
            val client = HttpClient(
                MockEngine { _ ->
                    respond(
                        content = """{"status":"ok"}""",
                        status = HttpStatusCode.OK,
                    )
                },
            )
            val outbound =
                PinnedJvmOutboundHttpFetcher(
                    policy =
                        JvmOutboundUrlPolicy {
                            arrayOf(InetAddress.getByName("93.184.216.34"))
                        },
                    clientFactory = { client },
                )
            val tool = ApiCallTool(outbound)
            val result = tool.execute(ApiCallArgs(url = "https://myapi.com/v1", method = "POST", body = "hello_api"))
            assertTrue(result.contains("status") && result.contains("200"), "Should execute HTTP call and return response text")
        }

    @Test
    fun testTodoTool() =
        runTest {
            val tool =
                TodoTool(
                    SecureJvmWorkspaceFileReader(workDirStr),
                    SecureJvmWorkspaceFileWriter(workDirStr),
                )

            // List empty
            var res = tool.execute(TodoArgs(action = "list"))
            if (res.contains("unavailable on this filesystem")) return@runTest
            assertEquals("No tasks.", res)

            // Add
            res = tool.execute(TodoArgs(action = "add", value = "First task"))
            assertTrue(res.contains("Added task #1"), "Should add task")

            // List populated
            res = tool.execute(TodoArgs(action = "list"))
            assertTrue(res.contains("1. [ ] First task"), "Should list tasks with state")

            // Done
            res = tool.execute(TodoArgs(action = "done", value = "1"))
            assertTrue(res.contains("Marked task #1 as done"), "Should check task")

            // List done
            res = tool.execute(TodoArgs(action = "list"))
            assertTrue(res.contains("[x] First task"), "Should show task completed")

            // Remove
            res = tool.execute(TodoArgs(action = "remove", value = "1"))
            assertTrue(res.contains("Removed:"), "Should remove task")
        }

    @Test
    fun testNotesTool() =
        runTest {
            val tool =
                NotesTool(
                    SecureJvmWorkspaceFileReader(workDirStr),
                    SecureJvmWorkspaceFileWriter(workDirStr),
                )

            // List empty
            var res = tool.execute(NotesArgs(action = "list"))
            if (res.contains("unavailable on this filesystem")) return@runTest
            assertEquals("No notes.", res)

            // Set
            res = tool.execute(NotesArgs(action = "set", key = "idea", content = "Learn Kotlin Multiplatform"))
            assertTrue(res.contains("Saved note 'idea'"), "Should save note")

            // Get
            res = tool.execute(NotesArgs(action = "get", key = "idea"))
            assertEquals("Learn Kotlin Multiplatform", res)

            // List populated
            res = tool.execute(NotesArgs(action = "list"))
            assertTrue(res.contains("- idea"), "Should list saved note keys")

            // Delete
            res = tool.execute(NotesArgs(action = "delete", key = "idea"))
            assertTrue(res.contains("Deleted note 'idea'"), "Should delete note")
        }
}
