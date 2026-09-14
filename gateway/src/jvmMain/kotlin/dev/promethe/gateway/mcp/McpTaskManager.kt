package dev.promethe.gateway.mcp

import dev.promethe.core.McpProtocol
import dev.promethe.db.McpTaskRecord
import dev.promethe.db.McpTaskStatus
import dev.promethe.db.PrometheDatabaseApi
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/** Durable server-side implementation of the MCP Tasks extension. */
class McpTaskManager(
    private val database: PrometheDatabaseApi,
    private val scope: CoroutineScope,
    private val taskEligibleTools: Set<String> = DEFAULT_TASK_ELIGIBLE_TOOLS,
    private val now: () -> Long = System::currentTimeMillis,
    private val nextTaskId: () -> String = { UUID.randomUUID().toString() },
    private val defaultTtlMs: Long = DEFAULT_TTL_MS,
    private val defaultPollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    init {
        require(defaultTtlMs > 0) { "MCP task TTL must be positive" }
        require(defaultPollIntervalMs > 0) { "MCP task poll interval must be positive" }
    }

    private val json = Json { ignoreUnknownKeys = false }
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val pendingInputTurns = ConcurrentHashMap<String, PendingInputTurn>()
    private val issuedInputKeys = ConcurrentHashMap<String, MutableSet<String>>()
    private val initializationMutex = Mutex()
    private var initialized = false

    fun isTaskEligible(toolName: String): Boolean = toolName in taskEligibleTools

    /** Persists the task before starting any work, as required by the extension. */
    suspend fun createTask(
        ownerSessionId: String,
        toolName: String,
        runId: String? = null,
        execution: suspend TaskExecutionContext.() -> JsonObject,
    ): McpTaskRecord {
        ensureInitialized()
        val timestamp = now()
        val task =
            McpTaskRecord(
                taskId = nextTaskId(),
                ownerSessionId = ownerSessionId,
                method = "tools/call",
                resourceName = toolName,
                runId = runId,
                status = McpTaskStatus.WORKING,
                statusMessage = "The operation is in progress.",
                createdAt = timestamp,
                lastUpdatedAt = timestamp,
                ttlMs = defaultTtlMs,
                pollIntervalMs = defaultPollIntervalMs,
            )
        check(database.insertMcpTask(task)) { "Failed to durably create MCP task" }

        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = TaskExecutionContext(this@McpTaskManager, task).execution()
                    database.updateMcpTask(
                        taskId = task.taskId,
                        ownerSessionId = task.ownerSessionId,
                        expectedStatuses = setOf(McpTaskStatus.WORKING, McpTaskStatus.INPUT_REQUIRED),
                        status = McpTaskStatus.COMPLETED,
                        statusMessage = "The operation completed.",
                        resultJson = json.encodeToString(JsonObject.serializer(), result),
                        errorJson = null,
                        inputRequestsJson = null,
                        lastUpdatedAt = now(),
                    )
                } catch (_: CancellationException) {
                    withContext(NonCancellable) {
                        database.updateMcpTask(
                            taskId = task.taskId,
                            ownerSessionId = task.ownerSessionId,
                            expectedStatuses = setOf(McpTaskStatus.WORKING, McpTaskStatus.INPUT_REQUIRED),
                            status = McpTaskStatus.CANCELLED,
                            statusMessage = "The operation was cancelled.",
                            resultJson = null,
                            errorJson = null,
                            inputRequestsJson = null,
                            lastUpdatedAt = now(),
                        )
                    }
                } catch (error: Exception) {
                    logger.warn {
                        "MCP task failed [taskId=${task.taskId}, tool=$toolName, errorType=${error::class.simpleName}]"
                    }
                    database.updateMcpTask(
                        taskId = task.taskId,
                        ownerSessionId = task.ownerSessionId,
                        expectedStatuses = setOf(McpTaskStatus.WORKING, McpTaskStatus.INPUT_REQUIRED),
                        status = McpTaskStatus.FAILED,
                        statusMessage = "The operation failed.",
                        resultJson = null,
                        errorJson = json.encodeToString(JsonObject.serializer(), internalTaskError()),
                        inputRequestsJson = null,
                        lastUpdatedAt = now(),
                    )
                } finally {
                    pendingInputTurns.remove(task.taskId)?.completion?.cancel()
                    issuedInputKeys.remove(task.taskId)
                    activeJobs.remove(task.taskId)
                }
            }
        activeJobs[task.taskId] = job
        job.start()
        logger.info { "MCP task created [taskId=${task.taskId}, tool=$toolName]" }
        return task
    }

    suspend fun getTask(
        taskId: String,
        ownerSessionId: String,
    ): McpTaskRecord? {
        ensureInitialized()
        val task = database.getMcpTask(taskId, ownerSessionId) ?: return null
        val ttlMs = task.ttlMs
        if (task.status in ACTIVE_STATUSES && ttlMs != null && now() >= task.createdAt + ttlMs) {
            database.updateMcpTask(
                taskId = task.taskId,
                ownerSessionId = task.ownerSessionId,
                expectedStatuses = ACTIVE_STATUSES,
                status = McpTaskStatus.FAILED,
                statusMessage = "The task expired before completion.",
                resultJson = null,
                errorJson = json.encodeToString(JsonObject.serializer(), expiredTaskError()),
                inputRequestsJson = null,
                lastUpdatedAt = now(),
            )
            activeJobs.remove(task.taskId)?.cancel(CancellationException("MCP task expired"))
            pendingInputTurns.remove(task.taskId)?.completion?.cancel(CancellationException("MCP task expired"))
            return database.getMcpTask(taskId, ownerSessionId)
        }
        return task
    }

    suspend fun handleTasksGet(
        params: JsonObject,
        ownerSessionId: String,
    ): JsonObject = requireTask(params, ownerSessionId).toDetailedJson()

    suspend fun handleTasksUpdate(
        params: JsonObject,
        ownerSessionId: String,
    ): JsonObject {
        val task = requireTask(params, ownerSessionId)
        val responses = params["inputResponses"] as? JsonObject
            ?: throw IllegalArgumentException("Missing required param: 'inputResponses'")
        val inputRequestsJson = task.inputRequestsJson
        if (task.status == McpTaskStatus.INPUT_REQUIRED && inputRequestsJson != null) {
            val pending = pendingInputTurns[task.taskId]
                ?: throw IllegalArgumentException("Task input channel is unavailable")
            pending.mutex.withLock {
                val originalRequests = parseObject(inputRequestsJson)
                responses.forEach { (key, value) ->
                    if (key in originalRequests && key !in pending.responses) {
                        pending.responses[key] = value
                    }
                }
                val outstanding = originalRequests.filterKeys { it !in pending.responses }
                if (outstanding.size != originalRequests.size) {
                    val transitioned =
                        database.updateMcpTask(
                            taskId = task.taskId,
                            ownerSessionId = task.ownerSessionId,
                            expectedStatuses = setOf(McpTaskStatus.INPUT_REQUIRED),
                            status = if (outstanding.isEmpty()) McpTaskStatus.WORKING else McpTaskStatus.INPUT_REQUIRED,
                            statusMessage =
                                if (outstanding.isEmpty()) {
                                    "Client input was received."
                                } else {
                                    "Additional client input is required."
                                },
                            resultJson = null,
                            errorJson = null,
                            inputRequestsJson =
                                outstanding.takeIf { it.isNotEmpty() }?.let {
                                    json.encodeToString(JsonObject.serializer(), JsonObject(it))
                                },
                            lastUpdatedAt = now(),
                        )
                    check(transitioned) { "Failed to persist MCP task input responses" }
                    if (outstanding.isEmpty()) {
                        pending.completion.complete(JsonObject(pending.responses.toMap()))
                    }
                }
            }
        }
        return buildJsonObject { put("resultType", McpProtocol.RESULT_COMPLETE) }
    }

    suspend fun handleTasksCancel(
        params: JsonObject,
        ownerSessionId: String,
    ): JsonObject {
        val task = requireTask(params, ownerSessionId)
        if (task.status in ACTIVE_STATUSES) {
            database.updateMcpTask(
                taskId = task.taskId,
                ownerSessionId = task.ownerSessionId,
                expectedStatuses = ACTIVE_STATUSES,
                status = McpTaskStatus.CANCELLED,
                statusMessage = "Cancellation was requested by the client.",
                resultJson = null,
                errorJson = null,
                inputRequestsJson = null,
                lastUpdatedAt = now(),
            )
            activeJobs.remove(task.taskId)?.cancel(CancellationException("MCP task cancelled"))
            pendingInputTurns.remove(task.taskId)?.completion?.cancel(CancellationException("MCP task cancelled"))
        }
        return buildJsonObject { put("resultType", McpProtocol.RESULT_COMPLETE) }
    }

    fun buildCreateTaskResult(task: McpTaskRecord): JsonObject = task.toTaskJson(McpProtocol.RESULT_TASK)

    private suspend fun awaitInput(
        task: McpTaskRecord,
        inputRequests: JsonObject,
    ): JsonObject {
        require(inputRequests.isNotEmpty()) { "MCP task input requests cannot be empty" }
        require(inputRequests.size <= MAX_INPUT_REQUESTS) { "Too many MCP task input requests" }
        require(inputRequests.values.all { it is JsonObject }) { "MCP task input requests must be objects" }

        val issued = issuedInputKeys.computeIfAbsent(task.taskId) { ConcurrentHashMap.newKeySet() }
        synchronized(issued) {
            require(inputRequests.keys.none { it in issued }) { "MCP task input request keys cannot be reused" }
            issued.addAll(inputRequests.keys)
        }

        val pending = PendingInputTurn()
        check(pendingInputTurns.putIfAbsent(task.taskId, pending) == null) {
            "MCP task already has an outstanding input turn"
        }
        val transitioned =
            database.updateMcpTask(
                taskId = task.taskId,
                ownerSessionId = task.ownerSessionId,
                expectedStatuses = setOf(McpTaskStatus.WORKING),
                status = McpTaskStatus.INPUT_REQUIRED,
                statusMessage = "Client input is required.",
                resultJson = null,
                errorJson = null,
                inputRequestsJson = json.encodeToString(JsonObject.serializer(), inputRequests),
                lastUpdatedAt = now(),
            )
        if (!transitioned) {
            pendingInputTurns.remove(task.taskId, pending)
            throw IllegalStateException("Failed to persist MCP task input request")
        }
        return try {
            pending.completion.await()
        } finally {
            pendingInputTurns.remove(task.taskId, pending)
        }
    }

    private suspend fun requireTask(
        params: JsonObject,
        ownerSessionId: String,
    ): McpTaskRecord {
        val taskId = (params["taskId"] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() && it.length <= MAX_TASK_ID_LENGTH }
            ?: throw IllegalArgumentException("Missing or invalid required param: 'taskId'")
        return getTask(taskId, ownerSessionId) ?: throw IllegalArgumentException("Unknown task: $taskId")
    }

    private suspend fun ensureInitialized() {
        initializationMutex.withLock {
            if (initialized) return
            database.getMcpTasksByStatus(ACTIVE_STATUSES).forEach { task ->
                database.updateMcpTask(
                    taskId = task.taskId,
                    ownerSessionId = task.ownerSessionId,
                    expectedStatuses = ACTIVE_STATUSES,
                    status = McpTaskStatus.FAILED,
                    statusMessage = "The task was interrupted by a server restart.",
                    resultJson = null,
                    errorJson = json.encodeToString(JsonObject.serializer(), interruptedTaskError()),
                    inputRequestsJson = null,
                    lastUpdatedAt = now(),
                )
            }
            initialized = true
        }
    }

    private fun McpTaskRecord.toDetailedJson(): JsonObject =
        toTaskJson(McpProtocol.RESULT_COMPLETE) {
            when (status) {
                McpTaskStatus.COMPLETED -> put("result", parseObject(requireNotNull(resultJson)))

                McpTaskStatus.FAILED -> put("error", parseObject(requireNotNull(errorJson)))

                McpTaskStatus.INPUT_REQUIRED -> put("inputRequests", parseObject(requireNotNull(inputRequestsJson)))

                McpTaskStatus.WORKING,
                McpTaskStatus.CANCELLED,
                -> Unit
            }
        }

    private fun McpTaskRecord.toTaskJson(
        resultType: String,
        content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): JsonObject =
        buildJsonObject {
            put("resultType", resultType)
            put("taskId", taskId)
            put("status", status.name.lowercase())
            statusMessage?.let { put("statusMessage", it) }
            put("createdAt", Instant.ofEpochMilli(createdAt).toString())
            put("lastUpdatedAt", Instant.ofEpochMilli(lastUpdatedAt).toString())
            put("ttlMs", ttlMs?.let(::JsonPrimitive) ?: JsonNull)
            pollIntervalMs?.let { put("pollIntervalMs", it) }
            content()
        }

    private fun parseObject(value: String): JsonObject = json.parseToJsonElement(value).jsonObject

    private fun internalTaskError(): JsonObject =
        buildJsonObject {
            put("code", -32603)
            put("message", "Task execution failed")
        }

    private fun interruptedTaskError(): JsonObject =
        buildJsonObject {
            put("code", -32004)
            put("message", "Task interrupted by server restart")
        }

    private fun expiredTaskError(): JsonObject =
        buildJsonObject {
            put("code", -32004)
            put("message", "Task expired")
        }

    companion object {
        const val DEFAULT_TTL_MS = 3_600_000L
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L
        private const val MAX_TASK_ID_LENGTH = 160
        private const val MAX_INPUT_REQUESTS = 16
        private val ACTIVE_STATUSES = setOf(McpTaskStatus.WORKING, McpTaskStatus.INPUT_REQUIRED)
        private val DEFAULT_TASK_ELIGIBLE_TOOLS =
            setOf(
                "autonomous_goal",
                "browser_vision",
                "claude_code_delegate",
                "codex_delegate",
                "mixture_of_agents",
                "video_generate",
                "web_crawl",
            )
    }

    class TaskExecutionContext internal constructor(
        private val manager: McpTaskManager,
        private val task: McpTaskRecord,
    ) {
        suspend fun requestInput(inputRequests: JsonObject): JsonObject = manager.awaitInput(task, inputRequests)
    }

    private data class PendingInputTurn(
        val responses: MutableMap<String, JsonElement> = linkedMapOf(),
        val completion: CompletableDeferred<JsonObject> = CompletableDeferred(),
        val mutex: Mutex = Mutex(),
    )
}
