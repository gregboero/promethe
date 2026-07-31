package dev.promethe.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral tests for [AutoHealingExecutor] — every test drives the real
 * retry loop through a stubbed [AutoHealingExecutor.ToolInvoker] and asserts
 * on the actual [AutoHealingExecutor.AutoHealResult].
 */
class AutoHealingExecutorTest {
    private val args = buildJsonObject { }
    private val resilience = ResilienceStrategy(KoogLlmAdapter(AgentConfig()))

    /** Invoker stub returning scripted responses (or throwing scripted exceptions). */
    private class ScriptedInvoker(
        private val script: List<Any>,
    ) : AutoHealingExecutor.ToolInvoker {
        var calls = 0

        override suspend fun invoke(
            toolName: String,
            args: kotlinx.serialization.json.JsonObject,
        ): String {
            val step = script[calls.coerceAtMost(script.lastIndex)]
            calls++
            if (step is Exception) throw step
            return step as String
        }
    }

    private fun healer(
        invoker: AutoHealingExecutor.ToolInvoker,
        maxRetries: Int = 3,
    ) = AutoHealingExecutor(invoker, resilience, maxRetries = maxRetries, retryDelayMs = 1)

    // ── Success paths ────────────────────────────────────────────

    @Test
    fun `first-try success does not retry and is not healed`() =
        runTest {
            val invoker = ScriptedInvoker(listOf("all good"))
            val result = healer(invoker).executeWithHealing("t", args)

            assertTrue(result.success)
            assertEquals("all good", result.result)
            assertEquals(1, result.attempts)
            assertEquals(1, invoker.calls)
            assertFalse(result.healed, "No retry happened — must not be flagged as healed")
        }

    @Test
    fun `error result then success retries and reports healed`() =
        runTest {
            val invoker = ScriptedInvoker(listOf("[ERROR] transient glitch", "recovered"))
            val result = healer(invoker).executeWithHealing("t", args)

            assertTrue(result.success)
            assertEquals("recovered", result.result)
            assertEquals(2, invoker.calls, "Exactly one retry expected")
            assertTrue(result.healed, "Recovered after retry — must be flagged as healed")
            assertEquals(listOf("[ERROR] transient glitch"), result.errors)
        }

    @Test
    fun `transient exception then success retries and reports healed`() =
        runTest {
            val invoker = ScriptedInvoker(listOf(java.net.SocketTimeoutException("read timed out"), "recovered"))
            val result = healer(invoker).executeWithHealing("t", args)

            assertTrue(result.success)
            assertEquals("recovered", result.result)
            assertEquals(2, invoker.calls)
            assertTrue(result.healed)
        }

    // ── Failure paths ────────────────────────────────────────────

    @Test
    fun `persistent error results exhaust maxRetries`() =
        runTest {
            val invoker = ScriptedInvoker(listOf("[ERROR] still broken"))
            val result = healer(invoker, maxRetries = 3).executeWithHealing("t", args)

            assertFalse(result.success)
            assertEquals(3, invoker.calls, "Must stop after maxRetries executions")
            assertEquals(3, result.errors.size)
            assertFalse(result.healed)
        }

    @Test
    fun `non-transient exception fails immediately without retry`() =
        runTest {
            val invoker = ScriptedInvoker(listOf(IllegalStateException("boom"), "would recover"))
            val result = healer(invoker).executeWithHealing("t", args)

            assertFalse(result.success)
            assertEquals(1, invoker.calls, "Non-transient exceptions must not be retried")
            assertTrue(result.result.contains("boom"))
            assertEquals(1, result.errors.size)
        }

    @Test
    fun `persistent transient exceptions exhaust maxRetries`() =
        runTest {
            val invoker = ScriptedInvoker(listOf(java.net.ConnectException("refused")))
            val result = healer(invoker, maxRetries = 3).executeWithHealing("t", args)

            assertFalse(result.success)
            assertEquals(3, invoker.calls)
            assertEquals(3, result.errors.size)
            assertTrue(result.errors.all { it.contains("refused") })
        }

    @Test
    fun `blocked results are treated as errors`() =
        runTest {
            val invoker = ScriptedInvoker(listOf("[BLOCKED] dangerous command"))
            val result = healer(invoker, maxRetries = 2).executeWithHealing("t", args)

            assertFalse(result.success)
            assertEquals(2, invoker.calls)
        }
}
