package dev.promethe.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    @Test
    fun `tool invocation preserves execution identity`() {
        val invocation =
            ToolInvocation(
                toolName = "web_search",
                arguments = buildJsonObject { put("query", "Promethe") },
                sessionId = "session-1",
                runId = "run-12345678",
                stepId = "run-12345678-step-0001",
            )

        val encoded = Json.encodeToString(ToolInvocation.serializer(), invocation)
        assertEquals(invocation, Json.decodeFromString(ToolInvocation.serializer(), encoded))
    }
}
