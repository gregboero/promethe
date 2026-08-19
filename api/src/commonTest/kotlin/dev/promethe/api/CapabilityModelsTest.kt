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
                idempotencyKey = "search-promethe-1",
            )

        val encoded = Json.encodeToString(ToolInvocation.serializer(), invocation)
        assertEquals(invocation, Json.decodeFromString(ToolInvocation.serializer(), encoded))
    }

    @Test
    fun `tool contract descriptor survives capability serialization`() {
        val contract =
            ToolContractDescriptor(
                source = ToolContractSource.INTEGRATION,
                catalogRisk = ToolRisk.CONFIG_CHANGE,
                missingOperationRisk = ToolRisk.EXTERNAL_EFFECT,
                unknownOperationRisk = ToolRisk.EXTERNAL_EFFECT,
                approval = ToolApprovalRequirement.RISK_BASED,
                idempotency = ToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
                egress = ToolEgress.REMOTE_SERVICE,
                ownerOnly = true,
                operationKeys = listOf("action"),
                operationRisks = mapOf("list" to ToolRisk.READ, "allow_user" to ToolRisk.CONFIG_CHANGE),
            )
        val descriptor =
            CapabilityDescriptor(
                id = "tool.discord_policy",
                name = "discord_policy",
                category = "tool",
                maturity = CapabilityMaturity.BETA,
                availability = CapabilityAvailability.AVAILABLE,
                risk = ToolRisk.CONFIG_CHANGE.name,
                toolContract = contract,
            )

        val encoded = Json.encodeToString(CapabilityDescriptor.serializer(), descriptor)

        assertEquals(descriptor, Json.decodeFromString(CapabilityDescriptor.serializer(), encoded))
    }
}
