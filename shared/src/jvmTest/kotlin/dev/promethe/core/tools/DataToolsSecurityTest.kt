package dev.promethe.core.tools.data

import dev.promethe.core.JvmOutboundUrlPolicy
import dev.promethe.core.PinnedJvmOutboundHttpFetcher
import dev.promethe.core.SecureJvmWorkspaceFileReader
import dev.promethe.core.SecureJvmWorkspaceFileWriter
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataToolsSecurityTest {
    private lateinit var workspace: File

    @BeforeTest
    fun setup() {
        workspace = File("build/tmp/test_data_security_${System.currentTimeMillis()}").canonicalFile
        workspace.mkdirs()
    }

    @AfterTest
    fun teardown() {
        workspace.deleteRecursively()
    }

    @Test
    fun `csv rejects parent escapes and protected metadata`() =
        runTest {
            val tool =
                CsvTool(
                    workspace.path,
                    SecureJvmWorkspaceFileReader(workspace.path),
                    SecureJvmWorkspaceFileWriter(workspace.path),
                )

            val parentEscape = tool.execute(CsvArgs(action = "write", path = "../outside.csv", data = "secret"))
            val protectedWrite = tool.execute(CsvArgs(action = "write", path = ".git/config", data = "secret"))

            assertTrue(parentEscape.contains("Access denied"))
            assertTrue(protectedWrite.contains("Access denied"))
            assertFalse(File(workspace.parentFile, "outside.csv").exists())
            assertFalse(File(workspace, ".git/config").exists())
        }

    @Test
    fun `notes rejects path separators and traversal keys`() =
        runTest {
            val tool =
                NotesTool(
                    SecureJvmWorkspaceFileReader(workspace.path),
                    SecureJvmWorkspaceFileWriter(workspace.path),
                )

            val parentEscape = tool.execute(NotesArgs(action = "set", key = "..", content = "secret"))
            val nestedEscape = tool.execute(NotesArgs(action = "set", key = "../secret", content = "secret"))

            assertTrue(parentEscape.contains("Note key must contain"))
            assertTrue(nestedEscape.contains("Note key must contain"))
            assertFalse(File(workspace.parentFile, "secret.md").exists())
        }

    @Test
    fun `api call rejects private destinations before opening a client`() =
        runTest {
            val outbound =
                PinnedJvmOutboundHttpFetcher(
                    policy =
                        JvmOutboundUrlPolicy {
                            arrayOf(InetAddress.getByName("127.0.0.1"))
                        },
                    clientFactory = { error("HTTP client must not be opened for a denied target") },
                )

            val result = ApiCallTool(outbound).execute(ApiCallArgs(url = "http://internal.example/admin"))

            assertTrue(result.contains("private, local, or special-use destinations are blocked"))
        }
}
