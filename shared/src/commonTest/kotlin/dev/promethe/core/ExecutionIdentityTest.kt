package dev.promethe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExecutionIdentityTest {
    @Test
    fun `generated IDs are valid and unique`() {
        val first = DefaultExecutionIdGenerator.nextId("run")
        val second = DefaultExecutionIdGenerator.nextId("run")

        assertTrue(isValidExecutionId(first))
        assertTrue(isValidExecutionId(second))
        assertTrue(first != second)
    }

    @Test
    fun `step identity is stable for a run and index`() {
        val run = AgentRunIdentity("run-12345678", "run-parent123")

        assertEquals("run-12345678-step-0003", run.step(3).stepId)
        assertEquals("run-12345678", run.step(3).runId)
    }

    @Test
    fun `invalid identifiers fail closed`() {
        assertFailsWith<IllegalArgumentException> { AgentRunIdentity("../unsafe") }
        assertFailsWith<IllegalArgumentException> { AgentRunIdentity("run-valid123", "bad id") }
    }
}
