package dev.promethe.gateway

import dev.promethe.core.ConversationTrajectory
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentEventBusTest {
    @Test
    fun `trajectory monitor event preserves execution identity`() {
        val event =
            with(AgentEventBus) {
                ConversationTrajectory(
                    inputs = mapOf("query" to "hello"),
                    outputs = mapOf("response" to "world"),
                ).toAgentEvent(
                    agentId = "promethe",
                    runId = "run-12345678",
                    stepId = "run-12345678-step-0001",
                )
            }

        assertEquals("run-12345678", event.runId)
        assertEquals("run-12345678-step-0001", event.stepId)
    }
}
