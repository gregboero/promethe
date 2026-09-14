package dev.promethe.core

import dev.promethe.api.SandboxedExecutionResult
import dev.promethe.core.sandbox.SandboxCommandExecutor
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullFileAccessApprovalTest {
    @Test
    fun `read requires mandatory approval in full file access`() =
        runTest {
            val approval = RecordingApprovalGate()
            val executor =
                ActionExecutor(
                    config = AgentConfig(),
                    httpClient = HttpClient(),
                    approvalGate = approval,
                    sandboxCommandExecutor = UnconfinedCommandExecutor,
                )

            val result =
                executor.execute(
                    ToolExecutionRequest(
                        toolName = "read_file",
                        arguments = buildJsonObject { put("path", "outside.txt") },
                        sessionId = "local-session",
                        origin = ToolCallOrigin.A2A,
                    ),
                )

            assertTrue(approval.mandatoryCalled)
            assertTrue(result.startsWith("[BLOCKED] Approval denied"))
            assertTrue(approval.arguments.contains("sandbox-policy=full-file-access"))
        }

    @Test
    fun `channel cannot use full local file access`() =
        runTest {
            val approval = RecordingApprovalGate()
            val executor =
                ActionExecutor(
                    config = AgentConfig(),
                    httpClient = HttpClient(),
                    approvalGate = approval,
                    sandboxCommandExecutor = UnconfinedCommandExecutor,
                )

            val result =
                executor.execute(
                    ToolExecutionRequest(
                        toolName = "directory_tree",
                        arguments = buildJsonObject { put("path", "C:/") },
                        sessionId = "discord-session",
                        origin = ToolCallOrigin.CHANNEL,
                    ),
                )

            assertTrue(result.contains("Full local file access is unavailable"))
            assertFalse(approval.mandatoryCalled)
        }

    private class RecordingApprovalGate : ApprovalGate {
        var mandatoryCalled = false
        var arguments = ""

        override suspend fun check(
            toolName: String,
            args: String,
            sessionId: String,
        ): ApprovalGate.ApprovalResult = ApprovalGate.ApprovalResult(false, "test denial")

        override suspend fun checkMandatory(
            toolName: String,
            args: String,
            sessionId: String,
        ): ApprovalGate.ApprovalResult {
            mandatoryCalled = true
            arguments = args
            return ApprovalGate.ApprovalResult(false, "test denial")
        }
    }

    private object UnconfinedCommandExecutor : SandboxCommandExecutor {
        override suspend fun executeCommand(
            executable: String,
            arguments: List<String>,
            workingDirectory: String,
            sessionId: String,
            timeoutMillis: Long,
        ): SandboxedExecutionResult = SandboxedExecutionResult(executionId = "unused")

        override fun approvalContext(): String = "full-file-access"

        override fun hasUnconfinedFileAccess(): Boolean = true
    }
}
