package dev.promethe.core

import dev.promethe.api.PolicyDataSensitivity
import dev.promethe.api.PolicyDataTrust
import dev.promethe.api.ToolCallOrigin
import dev.promethe.api.ToolEgress
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolicyKernelTest {
    @Test
    fun `unknown tools fail closed even when a lower layer allows them`() {
        val kernel =
            PolicyKernel(
                rules =
                    listOf(
                        PolicyRule(
                            id = "session-allow",
                            version = "1",
                            layer = PolicyLayer.SESSION,
                            effect = PolicyEffect.ALLOW,
                            toolNames = setOf("not_registered"),
                        ),
                    ),
            )
        val request = invocation("not_registered")

        val decision = kernel.evaluate(request, ToolContractRegistry.contractFor(request.toolName))

        assertEquals(PolicyEffect.DENY, decision.effect)
        assertTrue(decision.matches.any { it.ruleId == "unknown-tool-deny" })
    }

    @Test
    fun `untrusted content cannot initiate effects`() {
        val request =
            invocation(
                toolName = "write_file",
                dataTrust = PolicyDataTrust.UNTRUSTED,
            )

        val decision = PolicyKernel().evaluate(request, ToolContractRegistry.contractFor(request.toolName))

        assertEquals(PolicyEffect.DENY, decision.effect)
        assertTrue(decision.matches.any { it.ruleId == "untrusted-no-effects" })
    }

    @Test
    fun `untrusted content cannot initiate remote reads`() {
        val request =
            invocation(
                toolName = "web_search",
                dataTrust = PolicyDataTrust.UNTRUSTED,
            )

        val decision = PolicyKernel().evaluate(request, ToolContractRegistry.contractFor(request.toolName))

        assertEquals(PolicyEffect.DENY, decision.effect)
        assertTrue(decision.matches.any { it.ruleId == "untrusted-no-effects" })
    }

    @Test
    fun `equivalent policy inputs produce the same decision id`() {
        val request = invocation("web_search")
        val kernel = PolicyKernel()

        val first = kernel.evaluate(request, ToolContractRegistry.contractFor(request.toolName))
        val second = kernel.evaluate(request, ToolContractRegistry.contractFor(request.toolName))

        assertEquals(first.decisionId, second.decisionId)
        assertEquals(64, first.decisionId.length)
    }

    @Test
    fun `secret data cannot use remote egress`() {
        val request =
            invocation(
                toolName = "web_search",
                dataSensitivity = PolicyDataSensitivity.SECRET,
            )

        val decision = PolicyKernel().evaluate(request, ToolContractRegistry.contractFor(request.toolName))

        assertEquals(PolicyEffect.DENY, decision.effect)
        assertEquals(ToolEgress.REMOTE_SERVICE, decision.egress)
        assertTrue(decision.matches.any { it.ruleId == "secret-no-egress" })
    }

    @Test
    fun `organization rules can strengthen a read contract`() {
        val kernel =
            PolicyKernel(
                rules =
                    listOf(
                        PolicyRule(
                            id = "review-all-searches",
                            version = "3",
                            layer = PolicyLayer.ORGANIZATION,
                            effect = PolicyEffect.REQUIRE_APPROVAL,
                            toolNames = setOf("web_search"),
                        ),
                    ),
            )
        val request = invocation("web_search")

        val decision = kernel.evaluate(request, ToolContractRegistry.contractFor(request.toolName))

        assertEquals(PolicyEffect.REQUIRE_APPROVAL, decision.effect)
        assertTrue(decision.requiresApproval)
    }

    @Test
    fun `system rules cannot be supplied as overlays`() {
        val error =
            runCatching {
                PolicyKernel(
                    listOf(
                        PolicyRule(
                            id = "replace-system",
                            version = "1",
                            layer = PolicyLayer.SYSTEM,
                            effect = PolicyEffect.ALLOW,
                        ),
                    ),
                )
            }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    private fun invocation(
        toolName: String,
        dataTrust: PolicyDataTrust = PolicyDataTrust.TRUSTED,
        dataSensitivity: PolicyDataSensitivity = PolicyDataSensitivity.INTERNAL,
    ): ToolExecutionRequest =
        ToolExecutionRequest(
            toolName = toolName,
            arguments = buildJsonObject { put("value", "test") },
            sessionId = "policy-test",
            origin = ToolCallOrigin.A2A,
            dataTrust = dataTrust,
            dataSensitivity = dataSensitivity,
        )
}
