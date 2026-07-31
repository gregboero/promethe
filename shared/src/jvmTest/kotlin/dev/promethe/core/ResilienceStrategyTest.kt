package dev.promethe.core

import dev.promethe.core.ResilienceStrategy.EscalationLevel
import dev.promethe.core.ResilienceStrategy.EscalationState
import kotlin.test.*

class ResilienceStrategyTest {
    private val strategy = ResilienceStrategy(
        llmAdapter = KoogLlmAdapter(AgentConfig()),
        retryAttempts = 3,
        replanAttempts = 2,
    )

    // ── escalate(): RETRY level ─────────────────────────────────────────────

    @Test
    fun `first failure stays at RETRY with attempt incremented`() {
        val state = EscalationState(level = EscalationLevel.RETRY, attempt = 0)
        val next = strategy.escalate(state, "timeout")

        assertEquals(EscalationLevel.RETRY, next.level)
        assertEquals(1, next.attempt)
        assertTrue(next.errors.contains("timeout"))
    }

    @Test
    fun `second retry increments attempt to 2`() {
        val state = EscalationState(level = EscalationLevel.RETRY, attempt = 1)
        val next = strategy.escalate(state, "connection reset")

        assertEquals(EscalationLevel.RETRY, next.level)
        assertEquals(2, next.attempt)
    }

    @Test
    fun `third retry increments attempt to 3`() {
        val state = EscalationState(level = EscalationLevel.RETRY, attempt = 2)
        val next = strategy.escalate(state, "server error")

        assertEquals(EscalationLevel.RETRY, next.level)
        assertEquals(3, next.attempt)
    }

    @Test
    fun `after max retries escalates to REPLAN`() {
        val state = EscalationState(level = EscalationLevel.RETRY, attempt = 3)
        val next = strategy.escalate(state, "still failing")

        assertEquals(EscalationLevel.REPLAN, next.level)
        assertEquals(0, next.attempt)
    }

    // ── escalate(): REPLAN level ────────────────────────────────────────────

    @Test
    fun `first replan attempt increments attempt`() {
        val state = EscalationState(level = EscalationLevel.REPLAN, attempt = 0)
        val next = strategy.escalate(state, "replan failed")

        assertEquals(EscalationLevel.REPLAN, next.level)
        assertEquals(1, next.attempt)
    }

    @Test
    fun `after max replans escalates to DECOMPOSE`() {
        val state = EscalationState(level = EscalationLevel.REPLAN, attempt = 2)
        val next = strategy.escalate(state, "replan exhausted")

        assertEquals(EscalationLevel.DECOMPOSE, next.level)
        assertEquals(0, next.attempt)
    }

    // ── escalate(): DECOMPOSE level ─────────────────────────────────────────

    @Test
    fun `DECOMPOSE failure goes to EXHAUSTED`() {
        val state = EscalationState(level = EscalationLevel.DECOMPOSE, attempt = 0)
        val next = strategy.escalate(state, "decompose failed")

        assertEquals(EscalationLevel.EXHAUSTED, next.level)
    }

    // ── escalate(): EXHAUSTED level ─────────────────────────────────────────

    @Test
    fun `EXHAUSTED state stays EXHAUSTED`() {
        val state = EscalationState(level = EscalationLevel.EXHAUSTED, attempt = 0)
        val next = strategy.escalate(state, "another error")

        assertEquals(EscalationLevel.EXHAUSTED, next.level)
        assertTrue(next.errors.contains("another error"))
    }

    // ── escalate(): non-retryable errors ────────────────────────────────────

    @Test
    fun `401 error skips straight to EXHAUSTED`() {
        val state = EscalationState(level = EscalationLevel.RETRY, attempt = 0)
        val next = strategy.escalate(state, "HTTP 401 Unauthorized")

        assertEquals(EscalationLevel.EXHAUSTED, next.level)
    }

    @Test
    fun `403 error skips straight to EXHAUSTED`() {
        val state = EscalationState(level = EscalationLevel.RETRY, attempt = 0)
        val next = strategy.escalate(state, "HTTP 403 Forbidden")

        assertEquals(EscalationLevel.EXHAUSTED, next.level)
    }

    @Test
    fun `cannot determine error skips straight to EXHAUSTED`() {
        val state = EscalationState(level = EscalationLevel.RETRY, attempt = 0)
        val next = strategy.escalate(state, "Cannot determine model provider")

        assertEquals(EscalationLevel.EXHAUSTED, next.level)
    }

    @Test
    fun `not initialized error skips straight to EXHAUSTED`() {
        val state = EscalationState(level = EscalationLevel.RETRY, attempt = 0)
        val next = strategy.escalate(state, "Executor not initialized")

        assertEquals(EscalationLevel.EXHAUSTED, next.level)
    }

    @Test
    fun `invalid api error skips straight to EXHAUSTED`() {
        val state = EscalationState(level = EscalationLevel.REPLAN, attempt = 1)
        val next = strategy.escalate(state, "Invalid API key provided")

        assertEquals(EscalationLevel.EXHAUSTED, next.level)
    }

    // ── escalate(): error accumulation ──────────────────────────────────────

    @Test
    fun `errors accumulate across escalations`() {
        var state = EscalationState(level = EscalationLevel.RETRY, attempt = 0)
        state = strategy.escalate(state, "error-1")
        state = strategy.escalate(state, "error-2")
        state = strategy.escalate(state, "error-3")

        assertEquals(3, state.errors.size)
        assertEquals(listOf("error-1", "error-2", "error-3"), state.errors)
    }

    // ── escalate(): full escalation chain ───────────────────────────────────

    @Test
    fun `full escalation chain from RETRY to EXHAUSTED`() {
        var state = EscalationState(
            level = EscalationLevel.RETRY,
            attempt = 0,
            originalTask = "complex task",
        )

        // Exhaust retries (3 attempts, then one more triggers escalation)
        repeat(3) { state = strategy.escalate(state, "retry-fail-$it") }
        assertEquals(EscalationLevel.RETRY, state.level)
        assertEquals(3, state.attempt)

        // Next failure escalates to REPLAN
        state = strategy.escalate(state, "final-retry-fail")
        assertEquals(EscalationLevel.REPLAN, state.level)
        assertEquals(0, state.attempt)

        // Exhaust replans
        repeat(2) { state = strategy.escalate(state, "replan-fail-$it") }
        assertEquals(EscalationLevel.REPLAN, state.level)
        assertEquals(2, state.attempt)

        // Next failure escalates to DECOMPOSE
        state = strategy.escalate(state, "final-replan-fail")
        assertEquals(EscalationLevel.DECOMPOSE, state.level)

        // DECOMPOSE failure → EXHAUSTED
        state = strategy.escalate(state, "decompose-fail")
        assertEquals(EscalationLevel.EXHAUSTED, state.level)

        // Total errors: 3 retries + 1 escalation + 2 replans + 1 escalation + 1 decompose = 8
        assertEquals(8, state.errors.size)
    }

    // ── describe() ──────────────────────────────────────────────────────────

    @Test
    fun `describe RETRY shows attempt count`() {
        val state = EscalationState(level = EscalationLevel.RETRY, attempt = 2)
        val desc = strategy.describe(state)

        assertTrue(desc.contains("Retry"))
        assertTrue(desc.contains("2/3"))
    }

    @Test
    fun `describe REPLAN shows attempt count`() {
        val state = EscalationState(level = EscalationLevel.REPLAN, attempt = 1)
        val desc = strategy.describe(state)

        assertTrue(desc.contains("Replan"))
        assertTrue(desc.contains("1/2"))
    }

    @Test
    fun `describe DECOMPOSE shows sub-task count`() {
        val state = EscalationState(
            level = EscalationLevel.DECOMPOSE,
            decomposedTasks = listOf("task-a", "task-b", "task-c"),
        )
        val desc = strategy.describe(state)

        assertTrue(desc.contains("Decompose"))
        assertTrue(desc.contains("3"))
    }

    @Test
    fun `describe EXHAUSTED shows error count`() {
        val state = EscalationState(
            level = EscalationLevel.EXHAUSTED,
            errors = mutableListOf("e1", "e2"),
        )
        val desc = strategy.describe(state)

        assertTrue(desc.contains("exhausted"))
        assertTrue(desc.contains("2"))
    }

    // ── EscalationState defaults ────────────────────────────────────────────

    @Test
    fun `EscalationState defaults are sensible`() {
        val state = EscalationState()

        assertEquals(EscalationLevel.RETRY, state.level)
        assertEquals(0, state.attempt)
        assertTrue(state.errors.isEmpty())
        assertEquals("", state.originalTask)
        assertEquals("", state.currentTask)
        assertTrue(state.decomposedTasks.isEmpty())
    }
}
