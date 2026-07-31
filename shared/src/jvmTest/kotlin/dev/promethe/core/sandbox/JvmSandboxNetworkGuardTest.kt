package dev.promethe.core.sandbox

import dev.promethe.api.SandboxNetworkMode
import dev.promethe.api.SandboxPermissionProfile
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSandboxNetworkGuardTest {
    private val profile =
        SandboxPermissionProfile(
            networkMode = SandboxNetworkMode.ALLOWLIST,
            allowedDomains = listOf("api.example.com"),
        )

    @Test
    fun `pins all public addresses returned by DNS`() {
        val guard =
            JvmSandboxNetworkGuard {
                arrayOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("93.184.216.35"))
            }
        val target = guard.authorize("api.example.com", 443, profile)
        assertEquals(listOf("93.184.216.34", "93.184.216.35"), target.addresses)
    }

    @Test
    fun `rejects DNS answers containing private addresses`() {
        val guard = JvmSandboxNetworkGuard { arrayOf(InetAddress.getByName("10.0.0.7")) }
        assertFailsWith<SandboxNetworkDeniedException> {
            guard.authorize("api.example.com", 443, profile)
        }
    }
}
