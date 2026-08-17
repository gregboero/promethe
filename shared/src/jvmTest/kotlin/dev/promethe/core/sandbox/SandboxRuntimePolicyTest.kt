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
    fun `local owner can add canonical roots while protected metadata remains enforced`() {
        val workspace = Files.createTempDirectory("promethe-runtime-policy").toRealPath()
        val selected = Files.createTempDirectory("promethe-selected-root").toRealPath()
        val policy = SandboxRuntimePolicy(workspace.toString(), AgentConfig())

        val updated =
            policy.update(
                SandboxPermissionProfile(
                    readableRoots = listOf(selected.toString()),
                    writableRoots = listOf(selected.toString()),
                    protectedPaths = emptyList(),
                ),
                localOwner = true,
            )

        assertEquals(listOf(workspace.toString(), selected.toString()), updated.readableRoots)
        assertEquals(listOf(workspace.toString(), selected.toString()), updated.writableRoots)
        assertTrue(updated.protectedPaths.containsAll(listOf(".git", ".promethe", ".codex", ".agents")))
    }

    @Test
    fun `full file access stays local while process roots stay confined`() {
        val workspace = Files.createTempDirectory("promethe-runtime-policy")
        val policy = SandboxRuntimePolicy(workspace.toString(), AgentConfig())

        val updated = policy.update(SandboxPermissionProfile(mode = SandboxMode.FULL_ACCESS), localOwner = true)
        assertEquals(SandboxMode.FULL_ACCESS, updated.mode)
        assertEquals(SandboxMode.WORKSPACE_WRITE, policy.processProfile().mode)
        assertEquals(listOf(workspace.toRealPath().toString()), policy.processProfile().readableRoots)

        assertFailsWith<IllegalStateException> {
            policy.update(SandboxPermissionProfile(mode = SandboxMode.FULL_ACCESS), localOwner = false)
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
    fun `unsupported network profile fails closed during startup`() {
        val workspace = Files.createTempDirectory("promethe-runtime-policy")

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
