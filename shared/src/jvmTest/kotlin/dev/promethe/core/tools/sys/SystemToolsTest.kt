package dev.promethe.core.tools.sys

import dev.promethe.api.SandboxedExecutionRequest
import dev.promethe.api.SandboxedExecutionResult
import dev.promethe.core.AgentConfig
import dev.promethe.core.sandbox.SandboxProcessLauncher
import dev.promethe.core.sandbox.SandboxedCommandRunner
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SystemToolsTest {
    private lateinit var tempDir: File
    private lateinit var workDirStr: String

    @BeforeTest
    fun setup() {
        tempDir = File("build/tmp/test_sys_${System.currentTimeMillis()}").canonicalFile
        tempDir.mkdirs()
        workDirStr = tempDir.absolutePath
    }

    @AfterTest
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun shellBuildsSandboxedStructuredCommand() =
        runTest {
            var received: SandboxedExecutionRequest? = null
            val tool = ShellTool(workDirStr, sandboxRunner { request -> received = request })

            val result = tool.execute(ShellArgs(executable = "printf", arguments = listOf("hello_system_test")))

            assertEquals("hello_system_test", result)
            assertEquals("printf", received?.executable)
            assertEquals(listOf("hello_system_test"), received?.arguments)
            assertEquals(tempDir.toPath().toRealPath().toString(), received?.workingDirectory)
        }

    @Test
    fun shellRejectsInterpretersAndWorkspaceEscapes() =
        runTest {
            val tool = ShellTool(workDirStr, sandboxRunner())
            assertTrue(tool.execute(ShellArgs(executable = "sh", arguments = listOf("-c", "echo nope"))).startsWith("[BLOCKED]"))
            assertTrue(tool.execute(ShellArgs(executable = "C:\\Windows\\System32\\cmd.exe")).startsWith("[BLOCKED]"))
            assertTrue(tool.execute(ShellArgs(executable = "ls", cwd = "../")).startsWith("[BLOCKED]"))
            assertTrue(tool.execute(ShellArgs(executable = "cat", arguments = listOf("/etc/passwd"))).startsWith("[BLOCKED]"))
            assertTrue(tool.execute(ShellArgs(executable = "cat", arguments = listOf("../secret"))).startsWith("[BLOCKED]"))
            assertTrue(tool.execute(ShellArgs(executable = "type", arguments = listOf("C:\\secret.txt"))).startsWith("[BLOCKED]"))
        }

    @Test
    fun processManagerListsOnlySandboxVisibleProcessesAndBlocksHostKill() =
        runTest {
            var received: SandboxedExecutionRequest? = null
            val tool = ProcessManagerTool(sandboxRunner { request -> received = request })

            val listResult = tool.execute(ProcessManagerArgs(action = "list", filter = "sandbox"))
            val killResult = tool.execute(ProcessManagerArgs(action = "kill", pid = 1234))

            assertTrue(listResult.contains("sandbox"))
            assertTrue(received?.executable == "ps" || received?.executable == "tasklist")
            assertTrue(killResult.startsWith("[BLOCKED]"))
        }

    @Test
    fun systemInfoUsesSandboxVisibleCommand() =
        runTest {
            var received: SandboxedExecutionRequest? = null
            val tool = SystemInfoTool(sandboxRunner { request -> received = request })
            val allResult = tool.execute(SystemInfoArgs(category = "all"))
            assertEquals("sandbox", allResult)
            assertTrue(received?.executable == "systeminfo" || received?.executable == "uname")
        }

    @Test
    fun dockerUsesLiteralArgumentsThroughSandbox() =
        runTest {
            var received: SandboxedExecutionRequest? = null
            val tool = DockerTool(workDirStr, sandboxRunner { request -> received = request })

            val result = tool.execute(DockerArgs(action = "compose", arguments = listOf("ps", "--all")))

            assertEquals("sandbox", result)
            assertEquals("docker", received?.executable)
            assertEquals(listOf("compose", "ps", "--all"), received?.arguments)
            assertTrue(tool.execute(DockerArgs(action = "ps", arguments = listOf("|", "cat"))).startsWith("[BLOCKED]"))
        }

    @Test
    fun environmentToolExposesOnlyAllowlistedValues() =
        runTest {
            val tool = EnvironmentTool()
            assertTrue(tool.execute(EnvironmentArgs(action = "list")).isNotEmpty())
            assertTrue(tool.execute(EnvironmentArgs(action = "get", name = "PROMETHE_MASTER_KEY")).startsWith("[BLOCKED]"))
        }

    private fun sandboxRunner(
        handler: (SandboxedExecutionRequest) -> Unit = {},
    ): SandboxedCommandRunner =
        SandboxedCommandRunner(
            launcher =
                SandboxProcessLauncher { request ->
                    handler(request)
                    val stdout =
                        when (request.executable) {
                            "ps", "tasklist" -> "123 sandbox"
                            "printf" -> request.arguments.joinToString("")
                            else -> "sandbox"
                        }
                    SandboxedExecutionResult(executionId = request.executionId, exitCode = 0, stdout = stdout)
                },
            workspaceRoot = workDirStr,
            config = AgentConfig(),
        )
}
