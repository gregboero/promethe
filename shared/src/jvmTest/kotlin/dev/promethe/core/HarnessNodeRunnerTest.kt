package dev.promethe.core

import dev.promethe.api.*
import dev.promethe.core.sandbox.SandboxManager
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class HarnessNodeRunnerTest {
    private class Sandbox : SandboxManager {
        var ready = true
        var cancelled = false
        var request: SandboxedExecutionRequest? = null
        var respond: (SandboxedExecutionRequest) -> SandboxedExecutionResult = { SandboxedExecutionResult(executionId = it.executionId, exitCode = 0, stdout = "\"42\"") }

        override suspend fun execute(request: SandboxedExecutionRequest): SandboxedExecutionResult {
            this.request = request
            return respond(request)
        }

        override suspend fun cancel(executionId: String): Boolean {
            cancelled = true
            return true
        }

        override suspend fun status() = SandboxStatus(available = ready, backend = SandboxBackend.WINDOWS_ELEVATED, mode = SandboxMode.READ_ONLY, networkMode = SandboxNetworkMode.OFF, selfTestPassed = ready)

        override suspend fun selfTest() = status()
    }

    @Test fun `native request is bounded isolated and cleaned`() =
        runTest {
            val root = Files.createTempDirectory("node-runner-test")
            try {
                val node = Files.writeString(root.resolve("node.exe"), "placeholder")
                val sandbox = Sandbox()
                val scratch = root.resolve("scratch")
                val runner = HarnessNodeRunner(sandbox, node, scratch)
                assertEquals("42", runner.execute("return '42';", HarnessObservation("read_file", "raw"), "one"))
                val request = requireNotNull(sandbox.request)
                assertEquals("one", request.sessionId)
                assertTrue(request.environment.isEmpty())
                assertEquals(SandboxNetworkMode.OFF, request.profile.networkMode)
                assertEquals(SandboxMode.READ_ONLY, request.profile.mode)
                assertTrue(request.profile.writableRoots.isEmpty())
                assertEquals(2_000L, request.profile.limits.timeoutMillis)
                assertEquals(256L * 1024 * 1024, request.profile.limits.memoryBytes)
                assertEquals(1, request.profile.limits.processLimit)
                Files.list(scratch).use { assertEquals(0L, it.count()) }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test fun `unavailable sandbox never launches or falls back to host`() =
        runTest {
            val root = Files.createTempDirectory("node-runner-test")
            try {
                val sandbox = Sandbox().apply { ready = false }
                val runner = HarnessNodeRunner(sandbox, Files.writeString(root.resolve("node.exe"), ""), root.resolve("scratch"))
                assertFailsWith<IllegalStateException> { runner.execute("return '42'", HarnessObservation("read_file", "raw"), "one") }
                assertEquals(null, sandbox.request)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test fun `timeout truncation invalid output and cancellation fail closed`() =
        runTest {
            val root = Files.createTempDirectory("node-runner-test")
            try {
                val sandbox = Sandbox()
                val scratch = root.resolve("scratch")
                val runner = HarnessNodeRunner(sandbox, Files.writeString(root.resolve("node.exe"), ""), scratch)
                for (result in listOf(
                    SandboxedExecutionResult(executionId = "test", exitCode = 0, timedOut = true),
                    SandboxedExecutionResult(executionId = "test", exitCode = 0, truncated = true),
                    SandboxedExecutionResult(executionId = "test", exitCode = 1),
                    SandboxedExecutionResult(executionId = "test", exitCode = 0, stdout = "123"),
                    SandboxedExecutionResult(executionId = "test", exitCode = 0, stdout = "{bad"),
                )) {
                    sandbox.respond = { result }
                    assertFailsWith<Exception> { runner.execute("return '42'", HarnessObservation("read_file", "raw"), "one") }
                    Files.list(scratch).use { assertEquals(0L, it.count()) }
                }
                sandbox.respond = { throw CancellationException("cancel") }
                assertFailsWith<CancellationException> { runner.execute("return '42'", HarnessObservation("read_file", "raw"), "one") }
                assertTrue(sandbox.cancelled)
            } finally {
                root.toFile().deleteRecursively()
            }
        }
}
