package dev.promethe.core.sandbox

import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxPermissionProfile
import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CanonicalWorkspaceAccessBrokerTest {
    private val broker = CanonicalWorkspaceAccessBroker()

    @Test
    fun `allows reads and writes inside workspace`() {
        val workspace = Files.createTempDirectory("promethe-workspace")
        val file = workspace.resolve("notes.txt").createFile()
        val profile = SandboxPermissionProfile()

        assertEquals(file.toRealPath().toString(), broker.resolveReadablePath(workspace.toString(), "notes.txt", profile))
        assertEquals(workspace.resolve("new.txt").toString(), broker.resolveWritablePath(workspace.toString(), "new.txt", profile))
    }

    @Test
    fun `blocks parent traversal and protected metadata writes`() {
        val parent = Files.createTempDirectory("promethe-parent")
        val workspace = parent.resolve("workspace").createDirectory()
        workspace.resolve(".git").createDirectory()
        val profile = SandboxPermissionProfile()

        assertFailsWith<WorkspaceAccessDeniedException> {
            broker.resolveWritablePath(workspace.toString(), "../outside.txt", profile)
        }
        assertFailsWith<WorkspaceAccessDeniedException> {
            broker.resolveWritablePath(workspace.toString(), ".git/config", profile)
        }
    }

    @Test
    fun `blocks symlink escape when supported`() {
        val parent = Files.createTempDirectory("promethe-links")
        val workspace = parent.resolve("workspace").createDirectory()
        val outside = parent.resolve("outside").createDirectory()
        val link = workspace.resolve("escape")
        val created = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        if (!created) return

        assertFailsWith<WorkspaceAccessDeniedException> {
            broker.resolveWritablePath(workspace.toString(), "escape/secret.txt", SandboxPermissionProfile())
        }
    }

    @Test
    fun `read only profile rejects writes`() {
        val workspace = Files.createTempDirectory("promethe-readonly")
        val error =
            assertFailsWith<WorkspaceAccessDeniedException> {
                broker.resolveWritablePath(
                    workspace.toString(),
                    "new.txt",
                    SandboxPermissionProfile(mode = SandboxMode.READ_ONLY),
                )
            }
        assertTrue(error.message.orEmpty().contains("read-only"))
    }
}
