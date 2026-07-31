package dev.promethe.core.sandbox

import dev.promethe.api.SandboxErrorCode
import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.api.SandboxedExecutionRequest
import dev.promethe.api.SandboxedExecutionResult
import dev.promethe.core.AgentConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SandboxedCommandRunnerTest {
    @Test
    fun `builds a workspace bounded request`() {
        val workspace = Files.createTempDirectory("promethe-command-runner")
        var captured: SandboxedExecutionRequest? = null
        val runner =
            SandboxedCommandRunner(
                launcher =
                    SandboxProcessLauncher { request ->
                        captured = request
                        SandboxedExecutionResult(executionId = request.executionId, exitCode = 0, stdout = "ok")
                    },
                workspaceRoot = workspace.toString(),
                config = AgentConfig(sandboxMode = SandboxMode.WORKSPACE_WRITE),
            )

        lateinit var result: SandboxedExecutionResult
        kotlinx.coroutines.test.runTest {
            result =
                runner.execute(
                    executable = "git",
                    arguments = listOf("status"),
                    sessionId = "session with spaces",
                )
        }

        assertEquals("ok", result.stdout)
        assertEquals(listOf(workspace.toRealPath().toString()), captured?.profile?.readableRoots)
        assertEquals(listOf(workspace.toRealPath().toString()), captured?.profile?.writableRoots)
        assertEquals("session_with_spaces", captured?.sessionId)
        assertTrue(captured?.executionId?.isNotBlank() == true)
    }

    @Test
    fun `renders denied and successful executions without throwing`() {
        assertEquals(
            "[EXIT 2]\nout\nerr\n--- [TRUNCATED] ---",
            SandboxedExecutionResult(
                executionId = "test",
                exitCode = 2,
                stdout = "out",
                stderr = "err",
                truncated = true,
            ).renderCommandOutput(),
        )
    }

    @Test
    fun `approval context changes with the runtime policy`() {
        val workspace = Files.createTempDirectory("promethe-approval-context")
        val policy = SandboxRuntimePolicy(workspace.toString(), AgentConfig())
        val runner =
            SandboxedCommandRunner(
                launcher = SandboxProcessLauncher { request ->
                    SandboxedExecutionResult(executionId = request.executionId)
                },
                runtimePolicy = policy,
                workspaceRoot = workspace.toString(),
            )
        val initial = runner.approvalContext()

        policy.update(SandboxPermissionProfile(mode = SandboxMode.READ_ONLY), localOwner = true)

        assertNotEquals(initial, runner.approvalContext())
    }

    @Test
    fun `user command interface rejects shell interpreters before native launch`() =
        kotlinx.coroutines.test.runTest {
            val workspace = Files.createTempDirectory("promethe-command-runner")
            var launches = 0
            val runner =
                SandboxedCommandRunner(
                    launcher =
                        SandboxProcessLauncher { request ->
                            launches++
                            SandboxedExecutionResult(executionId = request.executionId, exitCode = 0)
                        },
                    workspaceRoot = workspace.toString(),
                    config = AgentConfig(),
                )

            val result =
                runner.executeCommand(
                    executable = "bash",
                    arguments = listOf("-c", "echo unsafe"),
                    workingDirectory = ".",
                    sessionId = "test",
                    timeoutMillis = 1_000,
                )

            assertEquals(SandboxErrorCode.POLICY_DENIED, result.errorCode)
            assertEquals(0, launches)
        }
}
