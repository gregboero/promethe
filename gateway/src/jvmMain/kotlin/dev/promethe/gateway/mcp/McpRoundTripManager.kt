package dev.promethe.gateway.mcp

import dev.promethe.core.McpProtocol
import dev.promethe.core.canonicalJson
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keeps a synchronous MCP tool execution alive while the client supplies structured input. */
class McpRoundTripManager(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val nextRequestState: () -> String = { UUID.randomUUID().toString() },
    private val inputTimeoutMs: Long = DEFAULT_INPUT_TIMEOUT_MS,
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    private val maxPendingExecutions: Int = DEFAULT_MAX_PENDING_EXECUTIONS,
    private val maxPendingPerOwner: Int = DEFAULT_MAX_PENDING_PER_OWNER,
) {
    init {
        require(inputTimeoutMs > 0) { "MCP round-trip input timeout must be positive" }
        require(maxRounds > 0) { "MCP round-trip limit must be positive" }
        require(maxPendingExecutions > 0) { "MCP pending execution limit must be positive" }
        require(maxPendingPerOwner > 0) { "MCP per-owner pending execution limit must be positive" }
    }

    private val pendingStates = ConcurrentHashMap<String, PendingState>()
    private val pendingSlots = Semaphore(maxPendingExecutions)
    private val ownerPendingCounts = ConcurrentHashMap<String, AtomicInteger>()

    suspend fun executeOrResume(
        ownerSessionId: String,
        toolName: String,
        arguments: JsonObject,
        clientInputMethods: Set<String>,
        requestState: String?,
        inputResponses: JsonObject?,
        execution: suspend RoundTripExecutionContext.() -> JsonObject,
    ): JsonObject {
        val argumentFingerprint = canonicalJson(arguments)
        return if (requestState == null) {
            require(inputResponses == null) { "inputResponses requires requestState" }
            start(ownerSessionId, toolName, argumentFingerprint, clientInputMethods, execution)
        } else {
            require(requestState.isNotBlank() && requestState.length <= MAX_REQUEST_STATE_LENGTH) {
                "Invalid requestState"
            }
            val responses = inputResponses ?: throw IllegalArgumentException("requestState requires inputResponses")
            resume(ownerSessionId, toolName, argumentFingerprint, clientInputMethods, requestState, responses)
        }
    }

    private suspend fun start(
        ownerSessionId: String,
        toolName: String,
        argumentFingerprint: String,
        clientInputMethods: Set<String>,
        execution: suspend RoundTripExecutionContext.() -> JsonObject,
    ): JsonObject {
        val state =
            ExecutionState(
                ownerSessionId = ownerSessionId,
                toolName = toolName,
                argumentFingerprint = argumentFingerprint,
                clientInputMethods = clientInputMethods,
            )
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result =
                        RoundTripExecutionContext { inputRequests ->
                            awaitInput(state, inputRequests)
                        }.execution()
                    state.events.send(RoundTripEvent.Completed(result))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    state.events.send(RoundTripEvent.Failed(error))
                } finally {
                    clearPendingStates(state)
                    state.events.close()
                }
            }
        state.job = job
        job.start()
        return awaitNextEvent(state)
    }

    private suspend fun resume(
        ownerSessionId: String,
        toolName: String,
        argumentFingerprint: String,
        clientInputMethods: Set<String>,
        requestState: String,
        inputResponses: JsonObject,
    ): JsonObject {
        val pending = pendingStates[requestState]
            ?: throw IllegalArgumentException("Unknown or expired requestState")
        require(pending.execution.ownerSessionId == ownerSessionId) { "requestState does not belong to this session" }
        require(pending.execution.toolName == toolName) { "requestState does not belong to this tool" }
        require(pending.execution.argumentFingerprint == argumentFingerprint) {
            "requestState arguments do not match the original request"
        }
        require(pending.requiredInputMethods.all { it in clientInputMethods }) {
            "The client no longer advertises the required input capability"
        }
        if (now() >= pending.expiresAt) {
            if (pendingStates.remove(requestState, pending)) {
                pending.execution.job?.cancel(CancellationException("MCP round-trip input expired"))
            }
            throw IllegalArgumentException("Expired requestState")
        }
        require(canonicalJson(inputResponses).length <= MAX_INPUT_PAYLOAD_CHARS) { "MCP input responses are too large" }
        require(inputResponses.keys == pending.inputRequests.keys) {
            "inputResponses must match all requested input keys"
        }
        check(pendingStates.remove(requestState, pending)) { "requestState was already consumed" }
        check(pending.completion.complete(inputResponses)) { "requestState was already completed" }
        return awaitNextEvent(pending.execution)
    }

    private suspend fun awaitNextEvent(state: ExecutionState): JsonObject =
        try {
            when (val event = state.events.receive()) {
                is RoundTripEvent.Completed -> {
                    event.result
                }

                is RoundTripEvent.InputRequired -> {
                    buildJsonObject {
                        put("resultType", McpProtocol.RESULT_INPUT_REQUIRED)
                        put("inputRequests", event.inputRequests)
                        put("requestState", event.requestState)
                    }
                }

                is RoundTripEvent.Failed -> {
                    throw event.error
                }
            }
        } catch (error: CancellationException) {
            state.job?.cancel(error)
            throw error
        }

    private suspend fun awaitInput(
        state: ExecutionState,
        inputRequests: JsonObject,
    ): JsonObject {
        require(inputRequests.isNotEmpty()) { "MCP input requests cannot be empty" }
        require(inputRequests.size <= MAX_INPUT_REQUESTS) { "Too many MCP input requests" }
        require(inputRequests.values.all { it is JsonObject }) { "MCP input requests must be objects" }
        require(canonicalJson(inputRequests).length <= MAX_INPUT_PAYLOAD_CHARS) { "MCP input requests are too large" }
        require(state.rounds.incrementAndGet() <= maxRounds) { "MCP input_required exceeded $maxRounds rounds" }

        val requiredMethods =
            inputRequests.values.map { request ->
                val requestObject = request as JsonObject
                val method = requestObject["method"] as? JsonPrimitive
                require(method?.isString == true && method.content.isNotBlank()) {
                    "MCP input request method must be a non-empty string"
                }
                require(requestObject["params"] is JsonObject) { "MCP input request params must be an object" }
                method.content
            }.toSet()
        require(requiredMethods.all { it in state.clientInputMethods }) {
            "The client does not advertise a required MCP input capability"
        }
        require(pendingSlots.tryAcquire()) { "Too many pending MCP round-trip executions" }
        val ownerPendingCount = ownerPendingCounts.computeIfAbsent(state.ownerSessionId) { AtomicInteger() }
        if (ownerPendingCount.incrementAndGet() > maxPendingPerOwner) {
            if (ownerPendingCount.decrementAndGet() == 0) {
                ownerPendingCounts.remove(state.ownerSessionId, ownerPendingCount)
            }
            pendingSlots.release()
            throw IllegalArgumentException("Too many pending MCP round-trip executions for this session")
        }

        val completion = CompletableDeferred<JsonObject>()
        var requestState: String? = null
        var pending: PendingState? = null
        return try {
            val allocatedState = createRequestState()
            val allocatedPending =
                PendingState(
                    execution = state,
                    inputRequests = inputRequests,
                    requiredInputMethods = requiredMethods,
                    completion = completion,
                    expiresAt = now() + inputTimeoutMs,
                )
            requestState = allocatedState
            pending = allocatedPending
            check(pendingStates.putIfAbsent(allocatedState, allocatedPending) == null) { "Duplicate MCP requestState" }
            state.events.send(RoundTripEvent.InputRequired(inputRequests, allocatedState))
            withTimeout(inputTimeoutMs) { completion.await() }
        } finally {
            val allocatedState = requestState
            val allocatedPending = pending
            if (allocatedState != null && allocatedPending != null) {
                pendingStates.remove(allocatedState, allocatedPending)
            }
            if (ownerPendingCount.decrementAndGet() == 0) {
                ownerPendingCounts.remove(state.ownerSessionId, ownerPendingCount)
            }
            pendingSlots.release()
        }
    }

    private fun createRequestState(): String {
        repeat(MAX_STATE_GENERATION_ATTEMPTS) {
            val candidate = nextRequestState()
            require(candidate.isNotBlank() && candidate.length <= MAX_REQUEST_STATE_LENGTH) {
                "Generated invalid MCP requestState"
            }
            if (!pendingStates.containsKey(candidate)) return candidate
        }
        error("Unable to allocate a unique MCP requestState")
    }

    private fun clearPendingStates(state: ExecutionState) {
        pendingStates.entries.removeIf { (_, pending) ->
            if (pending.execution !== state) return@removeIf false
            pending.completion.cancel(CancellationException("MCP round-trip execution ended"))
            true
        }
    }

    class RoundTripExecutionContext internal constructor(
        private val inputRequester: suspend (JsonObject) -> JsonObject,
    ) {
        suspend fun requestInput(inputRequests: JsonObject): JsonObject = inputRequester(inputRequests)
    }

    private class ExecutionState(
        val ownerSessionId: String,
        val toolName: String,
        val argumentFingerprint: String,
        val clientInputMethods: Set<String>,
    ) {
        val events = Channel<RoundTripEvent>(capacity = 1)
        val rounds = AtomicInteger(0)
        var job: Job? = null
    }

    private data class PendingState(
        val execution: ExecutionState,
        val inputRequests: JsonObject,
        val requiredInputMethods: Set<String>,
        val completion: CompletableDeferred<JsonObject>,
        val expiresAt: Long,
    )

    private sealed interface RoundTripEvent {
        data class InputRequired(
            val inputRequests: JsonObject,
            val requestState: String,
        ) : RoundTripEvent

        data class Completed(
            val result: JsonObject,
        ) : RoundTripEvent

        data class Failed(
            val error: Exception,
        ) : RoundTripEvent
    }

    companion object {
        private const val DEFAULT_INPUT_TIMEOUT_MS = 120_000L
        private const val DEFAULT_MAX_ROUNDS = 4
        private const val DEFAULT_MAX_PENDING_EXECUTIONS = 128
        private const val DEFAULT_MAX_PENDING_PER_OWNER = 8
        private const val MAX_INPUT_REQUESTS = 16
        private const val MAX_INPUT_PAYLOAD_CHARS = 256 * 1024
        private const val MAX_REQUEST_STATE_LENGTH = 128
        private const val MAX_STATE_GENERATION_ATTEMPTS = 8
    }
}
