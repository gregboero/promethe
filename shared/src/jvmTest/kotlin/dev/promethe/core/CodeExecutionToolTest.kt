package dev.promethe.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for CodeExecutionTool's backend routing and sandbox guarantees.
 * The core invariant: code execution NEVER falls back silently to
 * unsandboxed local execution — unsupported/unavailable backends refuse.
 */
class CodeExecutionToolTest {
    private val tmpWorkDir = System.getProperty("java.io.tmpdir")

    // ═══════════════════════════════════════════════════════════
    //  No silent unsandboxed fallback
    // ═══════════════════════════════════════════════════════════

    @Test
    fun testSshBackendRefusesCodeExecution() =
        runTest {
            val tool =
                CodeExecutionTool(
                    workDir = tmpWorkDir,
                    config = AgentConfig(executionBackend = "ssh"),
                )
            val result = tool.execute(CodeExecArgs(language = "python", code = "print('x')"))
            assertTrue(result.startsWith("[ERROR]"), "ssh backend should refuse code execution, got: $result")
            assertTrue(
                result.contains("refusing to run unsandboxed"),
                "Refusal must be explicit about the sandbox guarantee, got: $result",
            )
        }

    @Test
    fun testUnknownBackendRefusesCodeExecution() =
        runTest {
            val tool =
                CodeExecutionTool(
                    workDir = tmpWorkDir,
                    config = AgentConfig(executionBackend = "nonexistent-backend"),
                )
            val result = tool.execute(CodeExecArgs(language = "javascript", code = "console.log(1)"))
            assertTrue(result.startsWith("[ERROR]"), "Unknown backend should refuse, got: $result")
            assertTrue(result.contains("supported sandbox backend"), "Refusal should require a supported sandbox, got: $result")
        }

    @Test
    fun testUnsupportedLanguageIsRejected() =
        runTest {
            val tool = CodeExecutionTool(workDir = tmpWorkDir, config = AgentConfig(executionBackend = "local"))
            val result = tool.execute(CodeExecArgs(language = "ruby", code = "puts 1"))
            assertTrue(result.startsWith("[ERROR] Unsupported language"), "Got: $result")
        }

    // ═══════════════════════════════════════════════════════════
    //  Bash blocklist — line-based content check
    // ═══════════════════════════════════════════════════════════

    @Test
    fun testBashCodeExecutionIsUnavailable() =
        runTest {
            val tool = CodeExecutionTool(workDir = tmpWorkDir, config = AgentConfig(executionBackend = "local"))
            val result = tool.execute(CodeExecArgs(language = "bash", code = "rm -rf /"))
            assertTrue(result.startsWith("[ERROR] Unsupported language"), "Shell scripts must be unavailable, got: $result")
        }

    // ═══════════════════════════════════════════════════════════
    //  Docker command construction — isolation flags
    // ═══════════════════════════════════════════════════════════

    @Test
    fun testDockerCommandEnforcesIsolation() {
        val cmd =
            buildDockerCommand(
                image = "python:3.12-slim",
                containerName = "promethe-exec-42",
                interpreter = listOf("python3", "-u"),
                scriptFileName = "exec_42.py",
                extraArgs = listOf("--flag"),
                hostMountDir = "/host/dir",
            )

        val networkIdx = cmd.indexOf("--network")
        assertTrue(networkIdx >= 0 && cmd[networkIdx + 1] == "none", "Container must run without network: $cmd")

        val mountIdx = cmd.indexOf("-v")
        assertEquals("/host/dir:/sandbox:ro", cmd[mountIdx + 1], "Mount must be read-only at /sandbox")

        assertTrue(cmd.contains("--rm"), "Container must be removed after run")
        assertTrue(cmd.contains("python:3.12-slim"), "Image must be present")

        val imageIdx = cmd.indexOf("python:3.12-slim")
        assertEquals(listOf("python3", "-u", "/sandbox/exec_42.py", "--flag"), cmd.drop(imageIdx + 1))
    }

    @Test
    fun testDockerImageDefaultsPerLanguage() {
        assertEquals("python:3.12-slim", dockerImageFor("python", override = ""))
        assertEquals("python:3.12-slim", dockerImageFor("py", override = ""))
        assertEquals("node:22-slim", dockerImageFor("javascript", override = ""))
        assertNull(dockerImageFor("bash", override = ""), "Shell interpreters are not supported")
        assertNull(dockerImageFor("kotlin", override = ""), "Kotlin has no safe default image")
    }

    @Test
    fun testDockerImageOverrideWins() {
        assertEquals("custom/img:1", dockerImageFor("kotlin", override = "custom/img:1"))
        assertEquals("custom/img:1", dockerImageFor("python", override = "custom/img:1"))
    }

    @Test
    fun testRefusalPathDoesNotLeaveTempScripts() =
        runTest {
            val execDir = java.io.File(tmpWorkDir, ".promethe-exec")
            val before = execDir.listFiles()?.size ?: 0
            val tool =
                CodeExecutionTool(
                    workDir = tmpWorkDir,
                    config = AgentConfig(executionBackend = "ssh"),
                )
            tool.execute(CodeExecArgs(language = "python", code = "print('x')"))
            val after = execDir.listFiles()?.size ?: 0
            assertFalse(after > before, "Refused executions must clean up their temp script files")
        }
}
