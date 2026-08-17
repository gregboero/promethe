package dev.promethe.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class CapabilityModelsTest {
    @Test
    fun `local runtime status remains backwards compatible`() {
        val descriptor =
            CapabilityDescriptor(
                id = "integration.coding-agent.codex-local",
                name = "Codex local",
                category = "integration.coding-agent",
                maturity = CapabilityMaturity.BETA,
                availability = CapabilityAvailability.AVAILABLE,
                runtimeVersion = "codex 1.2.3",
                authentication = CapabilityAuthentication.AUTHENTICATED,
            )

        val encoded = Json.encodeToString(CapabilityDescriptor.serializer(), descriptor)
        assertEquals(descriptor, Json.decodeFromString(CapabilityDescriptor.serializer(), encoded))
    }
}
