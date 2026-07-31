package dev.promethe.core.sandbox

import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxNetworkMode
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.core.AgentConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SandboxRuntimePolicyTest {
    @Test
    fun `caller cannot replace workspace roots or protected metadata`() {
        val workspace = Files.createTempDirectory("promethe-runtime-policy").toRealPath()
        val policy = SandboxRuntimePolicy(workspace.toString(), AgentConfig())

        val updated =
            policy.update(
                SandboxPermissionProfile(
                    readableRoots = listOf("C:/outside"),
                    writableRoots = listOf("C:/outside"),
                    protectedPaths = emptyList(),
                ),
                localOwner = true,
            )

        assertEquals(listOf(workspace.toString()), updated.readableRoots)
        assertEquals(listOf(workspace.toString()), updated.writableRoots)
        assertTrue(updated.protectedPaths.containsAll(listOf(".git", ".promethe", ".codex", ".agents")))
    }

    @Test
    fun `unsupported full access and network profiles fail closed`() {
        val workspace = Files.createTempDirectory("promethe-runtime-policy")
        val policy = SandboxRuntimePolicy(workspace.toString(), AgentConfig())

        assertFailsWith<SandboxPolicyException> {
            policy.update(SandboxPermissionProfile(mode = SandboxMode.FULL_ACCESS), localOwner = true)
        }
        assertFailsWith<SandboxPolicyException> {
            policy.update(
                SandboxPermissionProfile(
                    networkMode = SandboxNetworkMode.ALLOWLIST,
                    allowedDomains = listOf("example.com"),
                ),
                localOwner = true,
            )
        }
    }

    @Test
    fun `unsupported profiles fail closed during startup`() {
        val workspace = Files.createTempDirectory("promethe-runtime-policy")

        assertFailsWith<SandboxPolicyException> {
            SandboxRuntimePolicy(
                workspace.toString(),
                AgentConfig(sandboxMode = SandboxMode.FULL_ACCESS),
            )
        }
        assertFailsWith<SandboxPolicyException> {
            SandboxRuntimePolicy(
                workspace.toString(),
                AgentConfig(
                    sandboxNetworkMode = SandboxNetworkMode.ALLOWLIST,
                    sandboxAllowedDomains = listOf("example.com"),
                ),
            )
        }
    }
}
