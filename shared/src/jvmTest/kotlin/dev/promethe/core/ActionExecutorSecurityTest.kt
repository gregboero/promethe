package dev.promethe.core

import dev.promethe.api.SandboxErrorCode
import dev.promethe.api.SandboxedExecutionRequest
import dev.promethe.api.SandboxedExecutionResult
import dev.promethe.core.sandbox.SandboxProcessLauncher
import dev.promethe.core.sandbox.SandboxedCommandRunner
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionExecutorSecurityTest {
    @BeforeTest
    fun installSandboxRunner() {
        val workspace = Files.createTempDirectory("promethe-local-process")
        LocalProcessSandbox.install(
            SandboxedCommandRunner(
                launcher =
                    SandboxProcessLauncher { request ->
                        when (request.executable) {
                            "timeout" -> {
                                SandboxedExecutionResult(
                                    executionId = request.executionId,
                                    timedOut = true,
                                    errorCode = SandboxErrorCode.TIMED_OUT,
                                    errorMessage = "execution timed out",
                                )
                            }

                            "nonexistent_command_xyz_12345" -> {
                                SandboxedExecutionResult(
                                    executionId = request.executionId,
                                    errorCode = SandboxErrorCode.BACKEND_UNAVAILABLE,
                                    errorMessage = "executable is unavailable",
                                )
                            }

                            else -> {
                                SandboxedExecutionResult(executionId = request.executionId, exitCode = 0, stdout = "sandbox-host")
                            }
                        }
                    },
                workspaceRoot = workspace.toString(),
                config = AgentConfig(),
            ),
        )
    }

    @AfterTest
    fun clearSandboxRunner() {
        LocalProcessSandbox.clear()
    }

    @Test
    fun blocklistRejectsDangerousCommands() {
        assertTrue(runLocalProcess("rm", listOf("-rf", "/")).startsWith("[BLOCKED]"))
        assertTrue(runLocalProcess("shutdown", listOf("-h", "now")).startsWith("[BLOCKED]"))
        assertTrue(runLocalProcess("format", listOf("c:")).startsWith("[BLOCKED]"))
    }

    @Test
    fun safeCommandUsesSandbox() {
        val result = runLocalProcess("hostname", emptyList())
        assertFalse(result.startsWith("[BLOCKED]"))
        assertFalse(result.startsWith("[SANDBOX"))
        assertTrue(result.contains("sandbox-host"))
    }

    @Test
    fun timeoutResultIsReturnedFromSandbox() {
        val result = runLocalProcess("timeout", emptyList(), timeoutMs = 1_000)
        assertTrue(result.contains("TIMED_OUT"), "Should return sandbox timeout: $result")
    }

    @Test
    fun outputTruncationRemainsBounded() {
        val maxBytes = 200
        val longText = "A".repeat(500)
        val result = truncateOutput(longText, maxBytes)
        assertTrue(result.length <= maxBytes + 80, "Output should be truncated, got ${result.length} chars")
    }

    @Test
    fun invalidCommandReturnsSandboxError() {
        val result = runLocalProcess("nonexistent_command_xyz_12345", emptyList())
        assertTrue(result.startsWith("[SANDBOX BACKEND_UNAVAILABLE]"), "Should return sandbox error: $result")
    }
}
