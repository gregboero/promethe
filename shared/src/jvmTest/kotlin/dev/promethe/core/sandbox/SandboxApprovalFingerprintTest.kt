package dev.promethe.core.sandbox

import dev.promethe.api.SandboxNetworkMode
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.api.SandboxedExecutionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SandboxApprovalFingerprintTest {
    private fun request() =
        SandboxedExecutionRequest(
            executionId = "execution-1",
            sessionId = "session-1",
            executable = "git",
            arguments = listOf("status"),
            workingDirectory = "/workspace",
        )

    @Test
    fun `fingerprint is stable across unordered collections`() {
        val first =
            request().copy(
                environment = linkedMapOf("LANG" to "C", "PATH" to "/usr/bin"),
                sensitiveEnvironmentKeys = linkedSetOf("B", "A"),
            )
        val second =
            request().copy(
                environment = linkedMapOf("PATH" to "/usr/bin", "LANG" to "C"),
                sensitiveEnvironmentKeys = linkedSetOf("A", "B"),
            )
        assertEquals(SandboxApprovalFingerprint.sha256(first), SandboxApprovalFingerprint.sha256(second))
    }

    @Test
    fun `fingerprint changes with arguments workspace or network`() {
        val original = request()
        assertNotEquals(
            SandboxApprovalFingerprint.sha256(original),
            SandboxApprovalFingerprint.sha256(original.copy(arguments = listOf("commit"))),
        )
        assertNotEquals(
            SandboxApprovalFingerprint.sha256(original),
            SandboxApprovalFingerprint.sha256(original.copy(workingDirectory = "/other")),
        )
        assertNotEquals(
            SandboxApprovalFingerprint.sha256(original),
            SandboxApprovalFingerprint.sha256(
                original.copy(profile = SandboxPermissionProfile(networkMode = SandboxNetworkMode.ALLOWLIST, allowedDomains = listOf("example.com"))),
            ),
        )
    }
}
