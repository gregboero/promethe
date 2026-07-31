package dev.promethe.core.tools.git

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

class GitToolsTest {
    private lateinit var tempDir: File
    private lateinit var workDirStr: String

    @BeforeTest
    fun setup() {
        tempDir = File("build/tmp/test_git_${System.currentTimeMillis()}").canonicalFile
        tempDir.mkdirs()
        workDirStr = tempDir.absolutePath
    }

    @AfterTest
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun statusUsesSandboxedGitCommand() =
        runTest {
            var received: SandboxedExecutionRequest? = null
            val tool = GitStatusTool(workDirStr, sandboxRunner { request -> received = request })

            val result = tool.execute(GitStatusArgs())

            assertEquals("sandbox", result)
            assertEquals("git", received?.executable)
            assertEquals(listOf("status", "--short", "--branch"), received?.arguments)
            assertEquals(tempDir.toPath().toRealPath().toString(), received?.workingDirectory)
        }

    @Test
    fun commitStagesAndCommitsThroughSandbox() =
        runTest {
            val received = mutableListOf<SandboxedExecutionRequest>()
            val tool = GitCommitTool(workDirStr, sandboxRunner { request -> received += request })

            val result = tool.execute(GitCommitArgs(message = "Initial commit", stageAll = true))

            assertEquals("sandbox", result)
            assertEquals(listOf("add", "-A"), received[0].arguments)
            assertEquals(listOf("commit", "-m", "Initial commit"), received[1].arguments)
        }

    @Test
    fun diffAndLogRejectEscapingPathsAndUnsafeRefs() =
        runTest {
            val runner = sandboxRunner()
            val diffTool = GitDiffTool(workDirStr, runner)
            val logTool = GitLogTool(workDirStr, runner)

            assertTrue(diffTool.execute(GitDiffArgs(path = "../outside")).startsWith("[BLOCKED]"))
            assertTrue(diffTool.execute(GitDiffArgs(target = "--output=/tmp/file")).startsWith("[BLOCKED]"))
            assertTrue(logTool.execute(GitLogArgs(path = "C:\\outside")).startsWith("[BLOCKED]"))
        }

    @Test
    fun branchCommandsUseLiteralArguments() =
        runTest {
            var received: SandboxedExecutionRequest? = null
            val tool = GitBranchTool(workDirStr, sandboxRunner { request -> received = request })

            val result = tool.execute(GitBranchArgs(action = "delete", name = "feature-branch"))

            assertTrue(result.startsWith("Deleted branch: feature-branch"))
            assertEquals(listOf("branch", "-d", "feature-branch"), received?.arguments)
            assertTrue(tool.execute(GitBranchArgs(action = "create", name = "--upload-pack=bad")).startsWith("[ERROR]"))
        }

    @Test
    fun logBoundsLimitAndUsesKnownFormat() =
        runTest {
            var received: SandboxedExecutionRequest? = null
            val tool = GitLogTool(workDirStr, sandboxRunner { request -> received = request })

            tool.execute(GitLogArgs(limit = 1000, format = "short", author = "Test", path = "src"))

            assertEquals(listOf("log", "-n", "100", "--format=short", "--author=Test", "--", "src"), received?.arguments)
        }

    private fun sandboxRunner(
        handler: (SandboxedExecutionRequest) -> Unit = {},
    ): SandboxedCommandRunner =
        SandboxedCommandRunner(
            launcher =
                SandboxProcessLauncher { request ->
                    handler(request)
                    SandboxedExecutionResult(executionId = request.executionId, exitCode = 0, stdout = "sandbox")
                },
            workspaceRoot = workDirStr,
            config = AgentConfig(),
        )
}
