package dev.promethe.core.sandbox

import dev.promethe.api.SANDBOX_PROTOCOL_VERSION
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
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NativeSandboxManager internal constructor(
    private val helper: Path,
    private val processFactory: SandboxHelperProcessFactory,
    private val json: Json =
        Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
            explicitNulls = false
        },
) : SandboxManager,
    Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupMutex = Mutex()
    private val writerMutex = Mutex()
    private val processLock = Any()
    private val controlLock = Any()
    private val pendingExecutions = ConcurrentHashMap<String, CompletableDeferred<SandboxIpcResponse>>()
    private val pendingControls =
        EnumMap<SandboxIpcOperation, ArrayDeque<CompletableDeferred<SandboxIpcResponse>>>(
            SandboxIpcOperation::class.java,
        )
    private val controlMutexes =
        EnumMap<SandboxIpcOperation, Mutex>(SandboxIpcOperation::class.java).apply {
            SandboxIpcOperation.entries
                .filter { it != SandboxIpcOperation.EXECUTE }
                .forEach { operation -> put(operation, Mutex()) }
        }

    @Volatile
    private var processState: ProcessState? = null

    @Volatile
    private var closed = false

    override suspend fun execute(request: SandboxedExecutionRequest): SandboxedExecutionResult {
        if (closed) {
            return unavailableResult(request.executionId)
        }
        if (request.protocolVersion != SANDBOX_PROTOCOL_VERSION) {
            return protocolErrorResult(request.executionId, "Unsupported sandbox protocol version.")
        }
        if (request.executionId.isBlank()) {
            return invalidRequestResult(request.executionId, "Sandbox execution ID is required.")
        }

        val pending = CompletableDeferred<SandboxIpcResponse>()
        if (pendingExecutions.putIfAbsent(request.executionId, pending) != null) {
            return invalidRequestResult(request.executionId, "Sandbox execution ID is already active.")
        }

        return try {
            sendRequest(
                SandboxIpcRequest(
                    operation = SandboxIpcOperation.EXECUTE,
                    execution = request,
                    executionId = request.executionId,
                ),
            )
            val timeoutMillis =
                request.profile.limits.timeoutMillis
                    .coerceIn(MIN_EXECUTION_TIMEOUT_MILLIS, MAX_EXECUTION_TIMEOUT_MILLIS)
            val response =
                withTimeout(timeoutMillis + TRANSPORT_GRACE_MILLIS) {
                    pending.await()
                }
            executionResult(response, request)
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val cancelled =
                withContext(NonCancellable) {
                    withTimeoutOrNull(CANCEL_GRACE_MILLIS) {
                        this@NativeSandboxManager.cancel(request.executionId)
                    } ?: false
                }
            if (!cancelled) {
                stopCurrentProcess()
            }
            SandboxedExecutionResult(
                executionId = request.executionId,
                timedOut = true,
                errorCode = SandboxErrorCode.TIMED_OUT,
                errorMessage = "Sandbox execution timed out.",
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                withTimeoutOrNull(CANCEL_GRACE_MILLIS) {
                    this@NativeSandboxManager.cancel(request.executionId)
                }
            }
            throw error
        } catch (error: Exception) {
            unavailableResult(request.executionId)
        } finally {
            pendingExecutions.remove(request.executionId, pending)
        }
    }

    override suspend fun cancel(executionId: String): Boolean {
        if (executionId.isBlank() || closed) {
            return false
        }
        val response =
            runCatching {
                requestControl(
                    SandboxIpcRequest(
                        operation = SandboxIpcOperation.CANCEL,
                        executionId = executionId,
                    ),
                )
            }.getOrNull() ?: return false
        return response.protocolVersion == SANDBOX_PROTOCOL_VERSION &&
            response.operation == SandboxIpcOperation.CANCEL &&
            response.errorCode == null
    }

    override suspend fun status(): SandboxStatus = queryStatus(SandboxIpcOperation.STATUS, requireSelfTest = false)

    override suspend fun selfTest(): SandboxStatus = queryStatus(SandboxIpcOperation.SELF_TEST, requireSelfTest = true)

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        stopCurrentProcess()
        scope.cancel()
    }

    private suspend fun queryStatus(
        operation: SandboxIpcOperation,
        requireSelfTest: Boolean,
    ): SandboxStatus {
        if (closed) {
            return unavailableStatus()
        }
        val response =
            runCatching {
                requestControl(SandboxIpcRequest(operation = operation))
            }.getOrNull() ?: return unavailableStatus()
        if (
            response.protocolVersion != SANDBOX_PROTOCOL_VERSION ||
            response.operation != operation ||
            response.errorCode != null
        ) {
            return unavailableStatus(response.errorMessage)
        }
        val status = response.status ?: return unavailableStatus()
        if (status.protocolVersion != SANDBOX_PROTOCOL_VERSION) {
            return unavailableStatus("Sandbox helper uses an incompatible protocol.")
        }
        return status.copy(
            message = status.message?.let(SandboxErrorRedactor::redact),
            selfTestPassed = if (requireSelfTest) status.selfTestPassed else status.selfTestPassed,
        )
    }

    private suspend fun requestControl(request: SandboxIpcRequest): SandboxIpcResponse {
        val operation = request.operation
        require(operation != SandboxIpcOperation.EXECUTE)
        val operationMutex = controlMutexes.getValue(operation)
        return operationMutex.withLock {
            val pending = CompletableDeferred<SandboxIpcResponse>()
            synchronized(controlLock) {
                pendingControls.getOrPut(operation, ::ArrayDeque).addLast(pending)
            }
            try {
                sendRequest(request)
                withTimeout(CONTROL_TIMEOUT_MILLIS) {
                    pending.await()
                }
            } catch (error: Exception) {
                synchronized(controlLock) {
                    pendingControls[operation]?.remove(pending)
                }
                if (error is kotlinx.coroutines.TimeoutCancellationException) {
                    stopCurrentProcess()
                }
                throw error
            }
        }
    }

    private suspend fun sendRequest(request: SandboxIpcRequest) {
        val state = ensureProcess()
        val line = json.encodeToString(request)
        try {
            writerMutex.withLock {
                check(state.process.isAlive) { "sandbox helper stopped" }
                withContext(Dispatchers.IO) {
                    state.writer.write(line)
                    state.writer.newLine()
                    state.writer.flush()
                }
            }
        } catch (error: Exception) {
            failProcess(state)
            throw SandboxTransportException()
        }
    }

    private suspend fun ensureProcess(): ProcessState {
        processState?.takeIf { it.process.isAlive }?.let { return it }
        return startupMutex.withLock {
            processState?.takeIf { it.process.isAlive }?.let { return@withLock it }
            check(!closed) { "sandbox manager is closed" }
            val process =
                withContext(Dispatchers.IO) {
                    processFactory.start(helper)
                }
            val state =
                ProcessState(
                    process = process,
                    writer =
                        BufferedWriter(
                            OutputStreamWriter(process.input, StandardCharsets.UTF_8),
                        ),
                )
            synchronized(processLock) {
                processState = state
            }
            scope.launch { readResponses(state) }
            scope.launch { drainErrors(state) }
            state
        }
    }

    private suspend fun readResponses(state: ProcessState) {
        val reader =
            BufferedReader(
                InputStreamReader(state.process.output, StandardCharsets.UTF_8),
            )
        try {
            while (state.process.isAlive || reader.ready()) {
                val line = reader.readLine() ?: break
                val response =
                    try {
                        json.decodeFromString<SandboxIpcResponse>(line)
                    } catch (error: SerializationException) {
                        throw SandboxProtocolException()
                    } catch (error: IllegalArgumentException) {
                        throw SandboxProtocolException()
                    }
                if (response.protocolVersion != SANDBOX_PROTOCOL_VERSION) {
                    throw SandboxProtocolException()
                }
                dispatch(response)
            }
        } catch (error: Exception) {
            // All callers receive a stable, redacted transport error below.
        } finally {
            failProcess(state)
        }
    }

    private fun dispatch(response: SandboxIpcResponse) {
        if (response.operation == SandboxIpcOperation.EXECUTE) {
            val executionId = response.execution?.executionId ?: throw SandboxProtocolException()
            val pending = pendingExecutions[executionId] ?: return
            pending.complete(response)
            return
        }
        val pending =
            synchronized(controlLock) {
                pendingControls[response.operation]?.removeFirstOrNull()
            } ?: return
        pending.complete(response)
    }

    private fun executionResult(
        response: SandboxIpcResponse,
        request: SandboxedExecutionRequest,
    ): SandboxedExecutionResult {
        if (response.operation != SandboxIpcOperation.EXECUTE) {
            return protocolErrorResult(request.executionId, "Sandbox helper returned an unexpected operation.")
        }
        if (response.errorCode != null) {
            return SandboxedExecutionResult(
                executionId = request.executionId,
                errorCode = response.errorCode,
                errorMessage = SandboxErrorRedactor.redact(response.errorMessage, request),
            )
        }
        val result =
            response.execution
                ?: return protocolErrorResult(
                    request.executionId,
                    "Sandbox helper omitted the execution result.",
                )
        if (
            result.protocolVersion != SANDBOX_PROTOCOL_VERSION ||
            result.executionId != request.executionId
        ) {
            return protocolErrorResult(request.executionId, "Sandbox helper returned an invalid execution result.")
        }
        return result.copy(errorMessage = result.errorMessage?.let { SandboxErrorRedactor.redact(it, request) })
    }

    private suspend fun drainErrors(state: ProcessState) {
        try {
            state.process.error.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                while (reader.readLine() != null) {
                    // Drain without retaining helper output.
                }
            }
        } catch (_: Exception) {
            // Helper stderr is deliberately discarded because it may contain sensitive command data.
        }
    }

    private fun stopCurrentProcess() {
        val state =
            synchronized(processLock) {
                processState.also { processState = null }
            } ?: return
        terminate(state)
        completePendingAsUnavailable()
    }

    private fun failProcess(state: ProcessState) {
        val shouldFail =
            synchronized(processLock) {
                if (processState === state) {
                    processState = null
                    true
                } else {
                    false
                }
            }
        terminate(state)
        if (shouldFail) {
            completePendingAsUnavailable()
        }
    }

    private fun terminate(state: ProcessState) {
        runCatching { state.writer.close() }
        if (state.process.isAlive) {
            runCatching { state.process.destroy() }
        }
        if (state.process.isAlive) {
            runCatching { state.process.destroyForcibly() }
        }
    }

    private fun completePendingAsUnavailable() {
        pendingExecutions.forEach { (executionId, pending) ->
            pending.complete(
                SandboxIpcResponse(
                    operation = SandboxIpcOperation.EXECUTE,
                    execution = unavailableResult(executionId),
                    errorCode = SandboxErrorCode.BACKEND_UNAVAILABLE,
                    errorMessage = TRANSPORT_ERROR_MESSAGE,
                ),
            )
        }
        synchronized(controlLock) {
            pendingControls.values.forEach { queue ->
                while (queue.isNotEmpty()) {
                    queue.removeFirst().complete(
                        SandboxIpcResponse(
                            operation = SandboxIpcOperation.STATUS,
                            errorCode = SandboxErrorCode.BACKEND_UNAVAILABLE,
                            errorMessage = TRANSPORT_ERROR_MESSAGE,
                        ),
                    )
                }
            }
        }
    }

    private fun unavailableResult(executionId: String): SandboxedExecutionResult =
        SandboxedExecutionResult(
            executionId = executionId,
            errorCode = SandboxErrorCode.BACKEND_UNAVAILABLE,
            errorMessage = TRANSPORT_ERROR_MESSAGE,
        )

    private fun invalidRequestResult(
        executionId: String,
        message: String,
    ): SandboxedExecutionResult =
        SandboxedExecutionResult(
            executionId = executionId,
            errorCode = SandboxErrorCode.INVALID_REQUEST,
            errorMessage = message,
        )

    private fun protocolErrorResult(
        executionId: String,
        message: String,
    ): SandboxedExecutionResult =
        SandboxedExecutionResult(
            executionId = executionId,
            errorCode = SandboxErrorCode.PROTOCOL_ERROR,
            errorMessage = message,
        )

    private fun unavailableStatus(message: String? = null): SandboxStatus =
        SandboxStatus(
            available = false,
            backend = SandboxBackend.UNAVAILABLE,
            mode = SandboxMode.READ_ONLY,
            networkMode = SandboxNetworkMode.OFF,
            message = SandboxErrorRedactor.redact(message ?: TRANSPORT_ERROR_MESSAGE),
        )

    private data class ProcessState(
        val process: SandboxHelperProcess,
        val writer: BufferedWriter,
    )

    companion object {
        fun discover(
            environment: Map<String, String> = System.getenv(),
            classLoader: ClassLoader = NativeSandboxManager::class.java.classLoader,
        ): SandboxManager {
            val discovery =
                SandboxHelperDiscovery(
                    environment = environment,
                    classLoader = classLoader,
                )
            val helper =
                discovery.discover().getOrElse {
                    return UnavailableSandboxManager(discovery.describeFailure())
                }
            return NativeSandboxManager(
                helper = helper.path,
                processFactory = JvmSandboxHelperProcessFactory,
            )
        }

        private const val MIN_EXECUTION_TIMEOUT_MILLIS = 1L
        private const val MAX_EXECUTION_TIMEOUT_MILLIS = 24L * 60L * 60L * 1_000L
        private const val TRANSPORT_GRACE_MILLIS = 2_000L
        private const val CONTROL_TIMEOUT_MILLIS = 5_000L
        private const val CANCEL_GRACE_MILLIS = 1_000L
        private const val TRANSPORT_ERROR_MESSAGE = "Sandbox helper is unavailable; execution was denied."
    }
}

class NativeSandboxProcessLauncher(
    private val manager: SandboxManager,
) : SandboxProcessLauncher {
    override suspend fun execute(request: SandboxedExecutionRequest): SandboxedExecutionResult = manager.execute(request)
}

private class SandboxTransportException : IllegalStateException()

private class SandboxProtocolException : IllegalStateException()
