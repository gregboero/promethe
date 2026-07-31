package dev.promethe.core.sandbox

import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxNetworkMode
import dev.promethe.api.SandboxPermissionProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SandboxPolicyTest {
    @Test
    fun `normalizes and deduplicates domain rules`() {
        val validated =
            SandboxPolicy.validate(
                SandboxPermissionProfile(
                    networkMode = SandboxNetworkMode.ALLOWLIST,
                    allowedDomains = listOf("EXAMPLE.COM.", "*.Example.com", "example.com"),
                ),
            )

        assertEquals(listOf("example.com", "*.example.com"), validated.allowedDomains)
    }

    @Test
    fun `rejects domains when network is disabled`() {
        assertFailsWith<SandboxPolicyException> {
            SandboxPolicy.validate(SandboxPermissionProfile(allowedDomains = listOf("example.com")))
        }
    }

    @Test
    fun `rejects writable roots for read only profile`() {
        assertFailsWith<SandboxPolicyException> {
            SandboxPolicy.validate(
                SandboxPermissionProfile(
                    mode = SandboxMode.READ_ONLY,
                    writableRoots = listOf("/workspace"),
                ),
            )
        }
    }

    @Test
    fun `rejects URL shaped domain rules`() {
        assertFailsWith<SandboxPolicyException> {
            SandboxPolicy.validate(
                SandboxPermissionProfile(
                    networkMode = SandboxNetworkMode.ALLOWLIST,
                    allowedDomains = listOf("https://example.com/path"),
                ),
            )
        }
    }
}
