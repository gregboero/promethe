package dev.promethe.core

import dev.promethe.api.SandboxedExecutionResult
import dev.promethe.api.ToolIntentStatus
import dev.promethe.core.sandbox.SandboxCommandExecutor
import dev.promethe.db.DatabaseFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolIntentLedgerTest {
    @Test
    fun `completed intent is recognized after database reopen`() =
        runTest {
            val directory = createTempDirectory("promethe-tool-intent-reopen").toFile()
            val url = "jdbc:sqlite:${java.io.File(directory, "promethe.db").absolutePath}"
            try {
                val request = request(toolName = "send_message")
                val first = PersistentToolIntentLedger(DatabaseFactory.create(url), SequentialIds())
                val admission = assertIs<ToolIntentAdmission.Proceed>(first.prepare(request, ToolRisk.EXTERNAL_EFFECT, 100))
                val intentId = requireNotNull(admission.intentId)
                assertTrue(first.markExecuting(intentId, 101))
                assertTrue(first.markSucceeded(intentId, "sent", 102))

                val reopened = PersistentToolIntentLedger(DatabaseFactory.create(url), SequentialIds(20))
                assertIs<ToolIntentAdmission.Replay>(reopened.prepare(request, ToolRisk.EXTERNAL_EFFECT, 200))
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `idempotency key cannot be reused with different arguments`() =
        runTest {
            val ledger = PersistentToolIntentLedger(DatabaseFactory.createInMemory(), SequentialIds())
            val first = request(toolName = "send_message", idempotencyKey = "message-42", value = "one")
            val changed = first.copy(arguments = buildJsonObject { put("value", "two") })

            assertIs<ToolIntentAdmission.Proceed>(ledger.prepare(first, ToolRisk.EXTERNAL_EFFECT, 100))
            val denied = assertIs<ToolIntentAdmission.Denied>(ledger.prepare(changed, ToolRisk.EXTERNAL_EFFECT, 101))

            assertTrue(denied.message.contains("different tool invocation"))
        }

    @Test
    fun `executing external effect is never repeated automatically`() =
        runTest {
            val ledger = PersistentToolIntentLedger(DatabaseFactory.createInMemory(), SequentialIds())
            val request = request(toolName = "send_message")
            val admission = assertIs<ToolIntentAdmission.Proceed>(ledger.prepare(request, ToolRisk.EXTERNAL_EFFECT, 100))
            assertTrue(ledger.markExecuting(requireNotNull(admission.intentId), 101))

            val denied = assertIs<ToolIntentAdmission.Denied>(ledger.prepare(request, ToolRisk.EXTERNAL_EFFECT, 400_000))

            assertTrue(denied.message.contains("outcome is uncertain"))
        }

    @Test
    fun `stale read can be reclaimed safely`() =
        runTest {
            val ledger = PersistentToolIntentLedger(DatabaseFactory.createInMemory(), SequentialIds())
            val request = request(toolName = "read_file")
            val first = assertIs<ToolIntentAdmission.Proceed>(ledger.prepare(request, ToolRisk.READ, 100))
            assertTrue(ledger.markExecuting(requireNotNull(first.intentId), 101))

            val retry = assertIs<ToolIntentAdmission.Proceed>(ledger.prepare(request, ToolRisk.READ, 400_000))

            assertEquals(first.intentId, retry.intentId)
            assertTrue(ledger.markExecuting(requireNotNull(retry.intentId), 400_001))
            assertTrue(ledger.markSucceeded(retry.intentId, "contents", 400_002))
        }

    @Test
    fun `secure executor invokes an effect only once per run`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val commandExecutor = CountingCommandExecutor()
            val executor =
                ActionExecutor(
                    config = AgentConfig(),
                    httpClient = HttpClient(),
                    approvalGate = AllowApprovalGate,
                    sandboxCommandExecutor = commandExecutor,
                    toolIntentLedger = PersistentToolIntentLedger(database, SequentialIds()),
                )
            val request =
                request(toolName = "execute_command").copy(
                    arguments =
                        buildJsonObject {
                            put("executable", "echo")
                            put("arguments", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("hello")) })
                        },
                )

            assertEquals("done", executor.execute(request))
            assertTrue(executor.execute(request).startsWith("[IDEMPOTENT]"))
            assertEquals(1, commandExecutor.count)
            assertEquals(1, database.getToolIntentsByStatus(setOf(ToolIntentStatus.SUCCEEDED)).size)
        }

    private fun request(
        toolName: String,
        idempotencyKey: String? = null,
        value: String = "one",
    ): ToolExecutionRequest =
        ToolExecutionRequest(
            toolName = toolName,
            arguments = buildJsonObject { put("value", value) },
            sessionId = "session-tool-intent",
            origin = ToolCallOrigin.A2A,
            runId = "run-tool-intent-0001",
            stepId = "run-tool-intent-0001-step-0001",
            idempotencyKey = idempotencyKey,
        )

    private class SequentialIds(
        start: Int = 0,
    ) : ExecutionIdGenerator {
        private val sequence = AtomicInteger(start)

        override fun nextId(prefix: String): String = "$prefix-intent-${sequence.incrementAndGet().toString().padStart(8, '0')}"
    }

    private class CountingCommandExecutor : SandboxCommandExecutor {
        var count = 0

        override suspend fun executeCommand(
            executable: String,
            arguments: List<String>,
            workingDirectory: String,
            sessionId: String,
            timeoutMillis: Long,
        ): SandboxedExecutionResult {
            count++
            return SandboxedExecutionResult(executionId = "sandbox-00000001", exitCode = 0, stdout = "done")
        }

        override fun approvalContext(): String = "workspace"
    }

    private object AllowApprovalGate : ApprovalGate {
        override suspend fun check(
            toolName: String,
            args: String,
            sessionId: String,
        ): ApprovalGate.ApprovalResult = ApprovalGate.ApprovalResult(true, "test")

        override suspend fun checkMandatory(
            toolName: String,
            args: String,
            sessionId: String,
        ): ApprovalGate.ApprovalResult = ApprovalGate.ApprovalResult(true, "test")
    }
}
