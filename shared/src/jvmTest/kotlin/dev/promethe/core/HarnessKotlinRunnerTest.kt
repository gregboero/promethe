package dev.promethe.core

import dev.promethe.api.*
import dev.promethe.core.sandbox.SandboxManager
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class HarnessKotlinRunnerTest {
    private class Sandbox : SandboxManager {
        var ready = true
        var cancelled = false
        var request: SandboxedExecutionRequest? = null
        val requests = mutableListOf<SandboxedExecutionRequest>()
        var respond: (SandboxedExecutionRequest) -> SandboxedExecutionResult = {
            val compilation = Json.parseToJsonElement(Files.readString(java.nio.file.Path.of(it.workingDirectory).resolve("input.json"))).jsonObject.getValue("operation").jsonPrimitive.content == "compile"
            SandboxedExecutionResult(executionId = it.executionId, exitCode = 0, stdout = if (compilation) """{"artifact":{},"compilationMillis":10}""" else """{"results":["42"],"evaluationMillis":1}""")
        }

        override suspend fun execute(request: SandboxedExecutionRequest): SandboxedExecutionResult {
            this.request = request
            requests.add(request)
            return respond(request)
        }

        override suspend fun cancel(executionId: String): Boolean {
            cancelled = true
            return true
        }

        override suspend fun status() = SandboxStatus(available = ready, backend = SandboxBackend.WINDOWS_ELEVATED, mode = SandboxMode.READ_ONLY, networkMode = SandboxNetworkMode.OFF, selfTestPassed = ready)

        override suspend fun selfTest() = status()
    }

    @Test fun `worker request is isolated bounded and cleaned on success and failure`() =
        runTest {
            val root = Files.createTempDirectory("kotlin-runner-test")
            try {
                val lib = Files.createDirectories(root.resolve("dist/lib"))
                Files.writeString(lib.resolve("worker.jar"), "placeholder")
                Files.writeString(Files.createDirectories(root.resolve("dist/bin")).resolve("harness-jvm.exe"), "placeholder")
                val bin = Files.createDirectories(root.resolve("runtime/bin"))
                Files.writeString(bin.resolve(if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"), "placeholder")
                val sandbox = Sandbox()
                val scratch = root.resolve("scratch")
                val runner = HarnessKotlinRunner(sandbox, root.resolve("dist"), root.resolve("runtime"), scratch, reusePreparedRuntime = false)
                assertEquals(HarnessLanguage.KOTLIN, runner.language)
                assertEquals("42", runner.execute("observation.text", HarnessObservation("read_file", "42"), "one"))
                val request = requireNotNull(sandbox.request)
                assertEquals("one", request.sessionId)
                assertTrue(request.environment.isEmpty())
                assertEquals(SandboxMode.READ_ONLY, request.profile.mode)
                assertEquals(SandboxNetworkMode.OFF, request.profile.networkMode)
                assertTrue(request.profile.writableRoots.isEmpty())
                assertEquals(5_000L, request.profile.limits.timeoutMillis)
                assertEquals(1024L * 1024 * 1024, request.profile.limits.memoryBytes)
                assertEquals(1, request.profile.limits.processLimit)
                assertEquals(2, sandbox.requests.size)
                val startupFlag = if (System.getProperty("os.name").startsWith("Windows")) "fast-evaluate" else "-XX:TieredStopAtLevel=1"
                assertTrue(startupFlag in request.arguments)
                assertFalse(startupFlag in sandbox.requests.first().arguments)
                assertEquals(15_000L, sandbox.requests.first().profile.limits.timeoutMillis)
                assertEquals(256 * 1024, sandbox.requests.first().profile.limits.maxOutputBytesPerStream)
                assertEquals(1, runner.cachedEntries())
                assertEquals("42", runner.execute("observation.text", HarnessObservation("read_file", "changed input"), "one"))
                assertEquals(3, sandbox.requests.size) // Cache skips compilation, never the evaluation.
                runner.execute("observation.text + \"new source\"", HarnessObservation("read_file", "42"), "one")
                assertEquals(5, sandbox.requests.size)
                Files.writeString(lib.resolve("worker.jar"), "changed classpath of same path")
                runner.execute("observation.text", HarnessObservation("read_file", "42"), "one")
                assertEquals(7, sandbox.requests.size)
                runner.execute("observation.text", HarnessObservation("read_file", "42"), "two")
                assertEquals(9, sandbox.requests.size)
                runner.endSession("one")
                assertEquals(1, runner.cachedEntries())
                runner.execute("observation.text", HarnessObservation("read_file", "42"), "one")
                assertEquals(11, sandbox.requests.size)
                Files.list(scratch).use { assertEquals(0L, it.count()) }
                for (result in listOf(
                    SandboxedExecutionResult(executionId = "test", exitCode = 0, timedOut = true),
                    SandboxedExecutionResult(executionId = "test", exitCode = 0, truncated = true),
                    SandboxedExecutionResult(executionId = "test", exitCode = 1),
                    SandboxedExecutionResult(executionId = "test", exitCode = 0, stdout = """{"results":[42]}"""),
                    SandboxedExecutionResult(executionId = "test", exitCode = 0, stdout = """{"results":[]}"""),
                    SandboxedExecutionResult(executionId = "test", exitCode = 0, stdout = "{bad"),
                )) {
                    sandbox.respond = { result }
                    assertFailsWith<Exception> { runner.execute("observation.text", HarnessObservation("read_file", "42"), "one") }
                    Files.list(scratch).use { assertEquals(0L, it.count()) }
                }
                sandbox.respond = { throw CancellationException("cancel") }
                assertFailsWith<CancellationException> { runner.execute("observation.text", HarnessObservation("read_file", "42"), "one") }
                assertTrue(sandbox.cancelled)
                runner.endSession("two")
                assertEquals(0, runner.cachedEntries())
                sandbox.respond = {
                    val compilation = Files.readString(java.nio.file.Path.of(it.workingDirectory).resolve("input.json")).contains("\"operation\":\"compile\"")
                    SandboxedExecutionResult(executionId = it.executionId, exitCode = 0, stdout = if (compilation) """{"artifact":{},"compilationMillis":10}""" else """{"results":["42"],"evaluationMillis":1}""")
                }
                val compatibilityRunner = HarnessKotlinRunner(sandbox, root.resolve("dist"), root.resolve("runtime"), scratch, optimizeEvaluationStartup = false, reusePreparedRuntime = false)
                compatibilityRunner.execute("observation.text", HarnessObservation("read_file", "42"), "compatibility")
                assertFalse(startupFlag in requireNotNull(sandbox.request).arguments)
                compatibilityRunner.endSession("compatibility")
                val timings = mutableListOf<KotlinRunnerTiming>()
                val preparedRunner = HarnessKotlinRunner(sandbox, root.resolve("dist"), root.resolve("runtime"), scratch, timing = { timings.add(it) })
                repeat(2) { assertEquals("42", preparedRunner.execute("observation.text", HarnessObservation("read_file", "42"), "prepared")) }
                val preparedCalls = sandbox.requests.takeLast(3)
                assertEquals(preparedCalls.first().executable, preparedCalls.last().executable)
                assertNotEquals(preparedCalls.first().workingDirectory, preparedCalls.last().workingDirectory)
                assertEquals(3, preparedCalls.map { it.executionId }.toSet().size)
                assertEquals(1, preparedRunner.preparedRuntimeEntries())
                assertTrue(timings.all { it.successful })
                sandbox.respond = { SandboxedExecutionResult(executionId = it.executionId, timedOut = true) }
                assertFailsWith<HarnessExecutionException> { preparedRunner.execute("observation.text", HarnessObservation("read_file", "42"), "prepared") }
                assertFalse(timings.last().successful)
                assertEquals(0, preparedRunner.preparedRuntimeEntries())
                assertEquals(0, preparedRunner.cachedEntries())
                Files.list(scratch).use { assertEquals(0L, it.count()) }
                sandbox.ready = false
                sandbox.request = null
                assertFailsWith<IllegalStateException> { runner.execute("observation.text", HarnessObservation("read_file", "42"), "one") }
                assertNull(sandbox.request)
            } finally {
                root.toFile().deleteRecursively()
            }
        }
}
