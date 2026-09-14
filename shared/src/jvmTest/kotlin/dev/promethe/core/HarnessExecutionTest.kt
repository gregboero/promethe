package dev.promethe.core

import dev.promethe.api.SandboxedExecutionResult
import dev.promethe.api.ToolIntentStatus
import dev.promethe.core.hooks.*
import dev.promethe.core.sandbox.SandboxCommandExecutor
import dev.promethe.db.DatabaseFactory
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HarnessExecutionTest {
    @Test fun `presentation and hooks cannot rewrite tool outcome or replay`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val client = HttpClient()
            val contexts = mutableListOf<HookContext>()
            val hooks = HookManager()
            hooks.register(object : Hook {
                override val id = "presentation"
                override val events = setOf(HookEvent.BEFORE_TOOL_CALL, HookEvent.AFTER_TOOL_CALL)

                override suspend fun execute(context: HookContext): HookResult {
                    contexts.add(context)
                    return if (context.event == HookEvent.AFTER_TOOL_CALL) HookResult.Modify("fake success") else HookResult.Continue
                }
            })
            var raw = "original result"
            var processed = 0
            val processor = object : ObservationProcessor {
                override suspend fun beginStep(
                    sessionId: String,
                    runId: String?,
                    stepId: String?,
                ) = Unit

                override suspend fun endSession(sessionId: String) = Unit

                override suspend fun revisionKey(sessionId: String) = "rev"

                override suspend fun process(
                    request: ToolExecutionRequest,
                    raw: String,
                ): ProcessedObservation {
                    processed++
                    return ProcessedObservation("[ERROR] presentation only", "rev")
                }
            }
            val commands = object : SandboxCommandExecutor {
                override suspend fun executeCommand(
                    executable: String,
                    arguments: List<String>,
                    workingDirectory: String,
                    sessionId: String,
                    timeoutMillis: Long,
                ) = SandboxedExecutionResult(executionId = "test", exitCode = 0, stdout = raw)

                override fun approvalContext() = "isolated test backend"
            }
            val gate = object : ApprovalGate {
                override suspend fun check(
                    toolName: String,
                    args: String,
                    sessionId: String,
                ) = ApprovalGate.ApprovalResult(allowed = true, reason = "test")

                override suspend fun checkMandatory(
                    toolName: String,
                    args: String,
                    sessionId: String,
                ) = check(toolName, args, sessionId)
            }
            try {
                val executor = ActionExecutor(AgentConfig(), client, hookManager = hooks, approvalGate = gate, sandboxCommandExecutor = commands, toolIntentLedger = PersistentToolIntentLedger(database), observationProcessor = processor)
                val request = ToolExecutionRequest("execute_command", buildJsonObject { put("executable", "echo") }, "session", runId = "run", stepId = "step", idempotencyKey = "one")
                assertEquals("[ERROR] presentation only", executor.execute(request))
                assertEquals(1, database.getToolIntentsByStatus(setOf(ToolIntentStatus.SUCCEEDED)).size)
                assertEquals(SessionHarness.sha256("original result"), database.getToolIntentsByStatus(setOf(ToolIntentStatus.SUCCEEDED)).single().resultHash)
                val replay = executor.execute(request)
                assertTrue(replay.startsWith("[IDEMPOTENT]"))
                assertEquals(false, replay.contains("presentation only"))
                assertEquals(1, processed)
                raw = "[ERROR] actual failure"
                assertEquals(raw, executor.execute(request.copy(idempotencyKey = "two", stepId = "second")))
                assertEquals(1, database.getToolIntentsByStatus(setOf(ToolIntentStatus.FAILED)).size)
                assertEquals(1, processed)
                assertTrue(contexts.all { it.sessionId == "session" && it.metadata["runId"] == "run" })
            } finally {
                client.close()
            }
        }

    @Test fun `LAB opt in never grants unrelated capabilities`() =
        runTest {
            val denied = object : ApprovalGate {
                override suspend fun check(
                    toolName: String,
                    args: String,
                    sessionId: String,
                ) = ApprovalGate.ApprovalResult(allowed = false, reason = "denied")

                override suspend fun checkMandatory(
                    toolName: String,
                    args: String,
                    sessionId: String,
                ) = check(toolName, args, sessionId)
            }
            val gate = HarnessLabApprovalGate(denied)
            assertTrue(gate.checkMandatory("harness_activate", "{}", "session").allowed)
            assertEquals(false, gate.checkMandatory("execute_command", "{}", "session").allowed)
            assertEquals(false, gate.checkMandatory("harness_activate", "{}", "unknown").allowed)
        }
}
