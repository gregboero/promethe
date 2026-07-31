package dev.promethe.core

import dev.promethe.core.hooks.HookContext
import dev.promethe.core.hooks.HookEvent
import dev.promethe.core.hooks.HookResult
import dev.promethe.core.sandbox.SandboxProcessLauncher
import dev.promethe.core.sandbox.SandboxedCommandRunner
import dev.promethe.api.SandboxedExecutionResult
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginScriptHookTest {
    @Test
    fun `legacy shell hook is skipped instead of being interpreted`() =
        runTest {
            val hook =
                PluginScriptHook(
                    hookId = "plugin.legacy",
                    hookEvents = setOf(HookEvent.BEFORE_TOOL_CALL),
                    executable = "echo",
                    arguments = listOf("safe"),
                    hasLegacyShellConfiguration = true,
                )

            val result = hook.execute(HookContext(event = HookEvent.BEFORE_TOOL_CALL))

            assertEquals(HookResult.Continue, result)
        }

    @Test
    fun `explicit executable without sandbox runner is unavailable`() =
        runTest {
            val hook =
                PluginScriptHook(
                    hookId = "plugin.no-runner",
                    hookEvents = setOf(HookEvent.BEFORE_TOOL_CALL),
                    executable = "echo",
                    arguments = listOf("safe"),
                )

            val result = hook.execute(HookContext(event = HookEvent.BEFORE_TOOL_CALL))

            assertEquals(HookResult.Continue, result)
        }

    @Test
    fun `hook without approval gate never reaches the sandbox runner`() =
        runTest {
            var launched = false
            val hook =
                PluginScriptHook(
                    hookId = "plugin.no-approval",
                    hookEvents = setOf(HookEvent.BEFORE_TOOL_CALL),
                    commandRunner =
                        runner {
                            launched = true
                            SandboxedExecutionResult(executionId = it.executionId)
                        },
                    executable = "echo",
                    arguments = listOf("safe"),
                )

            val result = hook.execute(HookContext(event = HookEvent.BEFORE_TOOL_CALL))

            assertEquals(HookResult.Continue, result)
            assertFalse(launched)
        }

    @Test
    fun `denied mandatory approval prevents plugin hook execution`() =
        runTest {
            var launched = false
            val gate = recordingGate(allowed = false)
            val hook =
                PluginScriptHook(
                    hookId = "plugin.denied",
                    hookEvents = setOf(HookEvent.BEFORE_TOOL_CALL),
                    commandRunner =
                        runner {
                            launched = true
                            SandboxedExecutionResult(executionId = it.executionId)
                        },
                    approvalGate = gate,
                    executable = "echo",
                    arguments = listOf("safe"),
                )

            val result =
                hook.execute(
                    HookContext(
                        event = HookEvent.BEFORE_TOOL_CALL,
                        sessionId = "session-a",
                    ),
                )

            assertTrue(result is HookResult.Abort)
            assertFalse(launched)
            assertEquals("plugin_hook", gate.lastToolName)
            assertTrue(gate.mandatory)
            assertFalse(gate.lastArguments.contains("secret"))
        }

    @Test
    fun `approved hook executes only through the sandbox runner`() =
        runTest {
            var launched = false
            val gate = recordingGate(allowed = true)
            val hook =
                PluginScriptHook(
                    hookId = "plugin.approved",
                    hookEvents = setOf(HookEvent.BEFORE_TOOL_CALL),
                    commandRunner =
                        runner {
                            launched = true
                            SandboxedExecutionResult(executionId = it.executionId)
                        },
                    approvalGate = gate,
                    executable = "echo",
                    arguments = listOf("safe"),
                )

            val result = hook.execute(HookContext(event = HookEvent.BEFORE_TOOL_CALL, sessionId = "session-a"))

            assertEquals(HookResult.Continue, result)
            assertTrue(launched)
            assertTrue(gate.mandatory)
        }

    private fun runner(execute: suspend (dev.promethe.api.SandboxedExecutionRequest) -> SandboxedExecutionResult): SandboxedCommandRunner {
        val workspace = Files.createTempDirectory("promethe-plugin-hook")
        return SandboxedCommandRunner(
            launcher = SandboxProcessLauncher(execute),
            workspaceRoot = workspace.toString(),
            config = AgentConfig(),
        )
    }

    private fun recordingGate(allowed: Boolean): RecordingApprovalGate = RecordingApprovalGate(allowed)

    private class RecordingApprovalGate(
        private val allowed: Boolean,
    ) : ApprovalGate {
        var mandatory = false
        var lastToolName = ""
        var lastArguments = ""

        override suspend fun check(
            toolName: String,
            args: String,
            sessionId: String,
        ): ApprovalGate.ApprovalResult = ApprovalGate.ApprovalResult(allowed, "test decision")

        override suspend fun checkMandatory(
            toolName: String,
            args: String,
            sessionId: String,
        ): ApprovalGate.ApprovalResult {
            mandatory = true
            lastToolName = toolName
            lastArguments = args
            return ApprovalGate.ApprovalResult(allowed, "test decision")
        }
    }
}
