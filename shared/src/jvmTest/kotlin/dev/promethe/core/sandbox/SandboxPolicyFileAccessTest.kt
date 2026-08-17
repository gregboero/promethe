package dev.promethe.core.sandbox

import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.core.AgentConfig
import dev.promethe.core.tools.fs.DirectoryTreeArgs
import dev.promethe.core.tools.fs.DirectoryTreeTool
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SandboxPolicyFileAccessTest {
    @Test
    fun `default tree includes selected roots when main workspace is requested`() =
        kotlinx.coroutines.test.runTest {
            val workspace = Files.createTempDirectory("promethe-tree-workspace")
            val selected = Files.createTempDirectory("promethe-tree-selected")
            Files.writeString(selected.resolve("visible.txt"), "visible")
            val policy =
                SandboxRuntimePolicy(
                    workspace.toString(),
                    AgentConfig(sandboxReadableRoots = listOf(selected.toString())),
                )
            val access = SandboxPolicyFileAccess(policy)

            val result =
                DirectoryTreeTool(workspace.toString(), access).execute(
                    DirectoryTreeArgs(path = workspace.toString(), maxDepth = 1),
                )

            assertTrue(result.contains(workspace.toRealPath().toString()))
            assertTrue(result.contains(selected.toRealPath().toString()))
            assertTrue(result.contains("visible.txt"))
        }

    @Test
    fun `selected root is live and revocable without restart`() =
        kotlinx.coroutines.test.runTest {
            val workspace = Files.createTempDirectory("promethe-policy-workspace")
            val selected = Files.createTempDirectory("promethe-policy-selected")
            val policy =
                SandboxRuntimePolicy(
                    workspace.toString(),
                    AgentConfig(
                        sandboxReadableRoots = listOf(selected.toString()),
                        sandboxWritableRoots = listOf(selected.toString()),
                    ),
                )
            val access = SandboxPolicyFileAccess(policy)
            val selectedFile = selected.resolve("selected.txt")

            access.write(selectedFile.toString(), "selected content")
            assertEquals("selected content", access.read(selectedFile.toString()))

            policy.update(SandboxPermissionProfile(), localOwner = true)
            assertFailsWith<WorkspaceAccessDeniedException> {
                access.read(selectedFile.toString())
            }
        }

    @Test
    fun `full file access does not widen process roots`() =
        kotlinx.coroutines.test.runTest {
            val workspace = Files.createTempDirectory("promethe-policy-workspace")
            val outside = Files.createTempDirectory("promethe-policy-outside")
            val policy = SandboxRuntimePolicy(workspace.toString(), AgentConfig())
            val access = SandboxPolicyFileAccess(policy)
            val outsideFile = outside.resolve("outside.txt")

            policy.update(SandboxPermissionProfile(mode = SandboxMode.FULL_ACCESS), localOwner = true)
            access.write(outsideFile.toString(), "outside content")

            assertEquals("outside content", outsideFile.readText())
            assertEquals(SandboxMode.WORKSPACE_WRITE, policy.processProfile().mode)
            assertEquals(listOf(workspace.toRealPath().toString()), policy.processProfile().writableRoots)
        }
}
