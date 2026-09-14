package dev.promethe.core.sandbox

import dev.promethe.api.SandboxBackend
import dev.promethe.api.SandboxErrorCode
import dev.promethe.api.SandboxIpcOperation
import dev.promethe.api.SandboxIpcRequest
import dev.promethe.api.SandboxIpcResponse
import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxNetworkMode
import dev.promethe.api.SandboxStatus
import dev.promethe.api.SandboxedExecutionRequest
import dev.promethe.api.SandboxedExecutionResult
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NativeSandboxManagerTest {
    @Test fun `broker response grace does not extend the child execution limit`() =
        runBlocking {
            val factory = ScriptedProcessFactory { request, respond ->
                if (request.operation == SandboxIpcOperation.EXECUTE) {
                    val execution = requireNotNull(request.execution)
                    assertEquals(1L, execution.profile.limits.timeoutMillis)
                    thread(isDaemon = true) {
                        Thread.sleep(3_000) // Exceeds the former two-second transport margin.
                        respond(executionResponse(execution.executionId))
                    }
                } else {
                    respond(controlResponse(request.operation))
                }
            }
            NativeSandboxManager(Path.of("trusted-helper"), factory, transportGraceMillis = 20_000).use { manager ->
                val input = request("broker-delay")
                val result = manager.execute(input.copy(profile = input.profile.copy(limits = input.profile.limits.copy(timeoutMillis = 1))))
                assertFalse(result.timedOut)
                assertEquals("broker-delay", result.stdout)
            }
        }

    @Test
    fun `persistent helper correlates concurrent executions by execution id`() =
        runBlocking {
            val factory =
                ScriptedProcessFactory { request, respond ->
                    when (request.operation) {
                        SandboxIpcOperation.EXECUTE -> {
                            assertEquals(null, request.executionId, "EXECUTE carries its ID only inside the execution payload")
                            val execution = requireNotNull(request.execution)
                            thread(isDaemon = true) {
                                if (execution.executionId == "slow") {
                                    Thread.sleep(80)
                                }
                                respond(executionResponse(execution.executionId))
                            }
                        }

                        else -> {
                            respond(controlResponse(request.operation))
                        }
                    }
                }
            NativeSandboxManager(Path.of("trusted-helper"), factory).use { manager ->
                val slow = async { manager.execute(request("slow")) }
                delay(10)
                val fast = async { manager.execute(request("fast")) }

                val fastResult = fast.await()
                val slowResult = slow.await()
                assertEquals("fast", fastResult.stdout, fastResult.toString())
                assertEquals("slow", slowResult.stdout, slowResult.toString())
                assertEquals(1, factory.startCount.get())
            }
        }

    @Test
    fun `status and self test use the persistent helper`() =
        runBlocking {
            val factory =
                ScriptedProcessFactory { request, respond ->
                    respond(
                        SandboxIpcResponse(
                            operation = request.operation,
                            status =
                                SandboxStatus(
                                    available = true,
                                    backend = SandboxBackend.WINDOWS_ELEVATED,
                                    mode = SandboxMode.WORKSPACE_WRITE,
                                    networkMode = SandboxNetworkMode.OFF,
                                    selfTestPassed = request.operation == SandboxIpcOperation.SELF_TEST,
                                ),
                        ),
                    )
                }
            NativeSandboxManager(Path.of("trusted-helper"), factory).use { manager ->
                assertTrue(manager.status().available)
                assertTrue(manager.selfTest().selfTestPassed)
                assertEquals(1, factory.startCount.get())
            }
        }

    @Test
    fun `timeout requests cancellation and fails closed`() =
        runBlocking {
            val operations = CopyOnWriteArrayList<SandboxIpcRequest>()
            val factory =
                ScriptedProcessFactory { request, respond ->
                    operations += request
                    if (request.operation == SandboxIpcOperation.CANCEL) {
                        respond(SandboxIpcResponse(operation = SandboxIpcOperation.CANCEL))
                    }
                }
            NativeSandboxManager(Path.of("trusted-helper"), factory, transportGraceMillis = 50).use { manager ->
                val result =
                    manager.execute(
                        request("timeout").copy(
                            profile =
                                request("timeout").profile.copy(
                                    limits =
                                        request("timeout").profile.limits.copy(
                                            timeoutMillis = 1,
                                        ),
                                ),
                        ),
                    )

                assertTrue(result.timedOut)
                assertEquals(SandboxErrorCode.TIMED_OUT, result.errorCode)
                assertTrue(
                    operations.any {
                        it.operation == SandboxIpcOperation.CANCEL &&
                            it.executionId == "timeout"
                    },
                )
            }
        }

    @Test
    fun `sensitive environment values are redacted from helper errors`() =
        runBlocking {
            val factory =
                ScriptedProcessFactory { request, respond ->
                    val execution = requireNotNull(request.execution)
                    respond(
                        SandboxIpcResponse(
                            operation = SandboxIpcOperation.EXECUTE,
                            execution =
                                SandboxedExecutionResult(
                                    executionId = execution.executionId,
                                    errorCode = SandboxErrorCode.INTERNAL_ERROR,
                                    errorMessage = "provider token=secret-value failed",
                                ),
                        ),
                    )
                }
            NativeSandboxManager(Path.of("trusted-helper"), factory).use { manager ->
                val result =
                    manager.execute(
                        request("redact").copy(
                            environment = mapOf("API_TOKEN" to "secret-value"),
                            sensitiveEnvironmentKeys = setOf("API_TOKEN"),
                        ),
                    )

                assertFalse(result.errorMessage.orEmpty().contains("secret-value"))
                assertTrue(result.errorMessage.orEmpty().contains("[REDACTED]"))
            }
        }

    @Test
    fun `invalid helper output disables execution without exposing the payload`() =
        runBlocking {
            val factory =
                ScriptedProcessFactory { _, respondRaw ->
                    respondRaw("{not-json")
                }
            NativeSandboxManager(Path.of("trusted-helper"), factory).use { manager ->
                val result = manager.execute(request("invalid-json"))

                assertEquals(SandboxErrorCode.BACKEND_UNAVAILABLE, result.errorCode)
                assertEquals(
                    "Sandbox helper is unavailable; execution was denied.",
                    result.errorMessage,
                )
            }
        }

    @Test
    fun `process launcher delegates without executing locally`() =
        runBlocking {
            val expected =
                SandboxedExecutionResult(
                    executionId = "delegated",
                    exitCode = 0,
                    stdout = "ok",
                )
            val manager =
                object : SandboxManager {
                    override suspend fun execute(request: SandboxedExecutionRequest) = expected

                    override suspend fun cancel(executionId: String) = false

                    override suspend fun status() = unavailableStatus()

                    override suspend fun selfTest() = unavailableStatus()
                }

            val result = NativeSandboxProcessLauncher(manager).execute(request("delegated"))

            assertEquals(expected, result)
        }

    private fun request(executionId: String): SandboxedExecutionRequest =
        SandboxedExecutionRequest(
            executionId = executionId,
            sessionId = "session",
            executable = "git",
            arguments = listOf("status"),
            workingDirectory = "workspace",
        )

    private fun executionResponse(executionId: String): SandboxIpcResponse =
        SandboxIpcResponse(
            operation = SandboxIpcOperation.EXECUTE,
            execution =
                SandboxedExecutionResult(
                    executionId = executionId,
                    exitCode = 0,
                    stdout = executionId,
                ),
        )

    private fun controlResponse(operation: SandboxIpcOperation): SandboxIpcResponse =
        SandboxIpcResponse(
            operation = operation,
            status = unavailableStatus(),
        )

    private fun unavailableStatus(): SandboxStatus =
        SandboxStatus(
            available = false,
            backend = SandboxBackend.UNAVAILABLE,
            mode = SandboxMode.READ_ONLY,
            networkMode = SandboxNetworkMode.OFF,
        )
}

private class ScriptedProcessFactory(
    private val handler: (SandboxIpcRequest, (Any) -> Unit) -> Unit,
) : SandboxHelperProcessFactory {
    val startCount = AtomicInteger()

    override fun start(helper: Path): SandboxHelperProcess {
        startCount.incrementAndGet()
        return ScriptedHelperProcess(handler)
    }
}

private class ScriptedHelperProcess(
    private val handler: (SandboxIpcRequest, (Any) -> Unit) -> Unit,
) : SandboxHelperProcess {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }
    private val alive = AtomicBoolean(true)
    private val managerInput = PipedOutputStream()
    private val helperRequests = PipedInputStream(managerInput)
    private val managerOutput = PipedInputStream()
    private val helperResponses = PipedOutputStream(managerOutput)
    private val responseQueue = LinkedBlockingQueue<String>()

    override val input: OutputStream = managerInput
    override val output: InputStream = managerOutput
    override val error: InputStream = ByteArrayInputStream(ByteArray(0))
    override val isAlive: Boolean
        get() = alive.get()

    init {
        thread(isDaemon = true, name = "fake-sandbox-response-writer") {
            while (alive.get() || responseQueue.isNotEmpty()) {
                val line = responseQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                runCatching {
                    helperResponses.write(line.toByteArray(StandardCharsets.UTF_8))
                    helperResponses.write('\n'.code)
                    helperResponses.flush()
                }.onFailure {
                    alive.set(false)
                }
            }
        }
        thread(isDaemon = true, name = "fake-sandbox-helper") {
            helperRequests.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val request = json.decodeFromString<SandboxIpcRequest>(line)
                    handler(request) { response -> writeResponse(response) }
                }
            }
            alive.set(false)
        }
    }

    override fun destroy() {
        closeStreams()
    }

    override fun destroyForcibly() {
        closeStreams()
    }

    private fun writeResponse(response: Any) {
        val line =
            when (response) {
                is SandboxIpcResponse -> json.encodeToString(response)
                is String -> response
                else -> error("unsupported fake response")
            }
        if (alive.get()) responseQueue.offer(line)
    }

    private fun closeStreams() {
        if (!alive.compareAndSet(true, false)) {
            return
        }
        runCatching { managerInput.close() }
        runCatching { helperRequests.close() }
        runCatching { helperResponses.close() }
        runCatching { managerOutput.close() }
    }
}
