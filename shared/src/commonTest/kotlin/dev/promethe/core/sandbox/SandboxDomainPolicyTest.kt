package dev.promethe.core.sandbox

import dev.promethe.api.SandboxNetworkMode
import dev.promethe.api.SandboxPermissionProfile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxDomainPolicyTest {
    private val profile =
        SandboxPermissionProfile(
            networkMode = SandboxNetworkMode.ALLOWLIST,
            allowedDomains = listOf("api.example.com", "*.packages.example"),
        )

    @Test
    fun `matches exact and wildcard subdomains`() {
        assertTrue(SandboxDomainPolicy.evaluate("api.example.com", profile).allowed)
        assertTrue(SandboxDomainPolicy.evaluate("cdn.packages.example", profile).allowed)
        assertFalse(SandboxDomainPolicy.evaluate("packages.example", profile).allowed)
    }

    @Test
    fun `rejects IP literals and unrelated domains`() {
        assertFalse(SandboxDomainPolicy.evaluate("127.0.0.1", profile).allowed)
        assertFalse(SandboxDomainPolicy.evaluate("::1", profile).allowed)
        assertFalse(SandboxDomainPolicy.evaluate("example.net", profile).allowed)
    }
}
