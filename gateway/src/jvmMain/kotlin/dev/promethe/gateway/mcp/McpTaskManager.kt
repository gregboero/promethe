package dev.promethe.gateway.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * McpTaskManager — manages long-running MCP tasks per the Tasks extension (2026 spec).
 *
 * When a tool call takes too long or is marked as long-running, the server returns a
 * task handle instead of the final result. The client can then poll, update, or cancel
 * the task through dedicated JSON-RPC methods:
 *
 *   - `tasks/get`    → poll task status/result
 *   - `tasks/cancel` → terminate a running task
 *
 * Task lifecycle:
 *   PENDING → RUNNING → COMPLETED | FAILED | CANCELLED
 *
 * Tasks are stored in-memory with TTL-based cleanup.
 */
class McpTaskManager {
    // ── Data models ──────────────────────────────────────────

    enum class TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
    }

    @Serializable
    data class McpTask(
        val id: String,
        val toolName: String,
        val status: TaskStatus,
        val progress: Float? = null, // 0.0 to 1.0
        val result: String? = null,
        val error: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
    )

    // ── State ────────────────────────────────────────────────

    private val tasks = ConcurrentHashMap<String, McpTask>()
    private val mutex = Mutex()

    // ── Public API ───────────────────────────────────────────

    /**
     * Create a new pending task and return its handle.
     */
    suspend fun createTask(toolName: String): McpTask =
        mutex.withLock {
            val task = McpTask(
                id = UUID.randomUUID().toString(),
                toolName = toolName,
                status = TaskStatus.PENDING,
            )
            tasks[task.id] = task
            logger.info { "Task created: ${task.id} for tool '$toolName'" }
            task
        }

    /**
     * Update task status and optional progress/result.
     */
    suspend fun updateTask(
        taskId: String,
        status: TaskStatus? = null,
        progress: Float? = null,
        result: String? = null,
        error: String? = null,
    ): McpTask? =
        mutex.withLock {
            val existing = tasks[taskId] ?: return@withLock null
            val updated = existing.copy(
                status = status ?: existing.status,
                progress = progress ?: existing.progress,
                result = result ?: existing.result,
                error = error ?: existing.error,
                updatedAt = System.currentTimeMillis(),
            )
            tasks[taskId] = updated
            logger.debug { "Task updated: $taskId → ${updated.status}" }
            updated
        }

    /**
     * Get a task by ID.
     */
    fun getTask(taskId: String): McpTask? = tasks[taskId]

    /**
     * Cancel a task if it's still running or pending.
     */
    suspend fun cancelTask(taskId: String): McpTask? =
        mutex.withLock {
            val existing = tasks[taskId] ?: return@withLock null
            if (existing.status in listOf(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED)) {
                return@withLock existing // Already terminal
            }
            val cancelled = existing.copy(
                status = TaskStatus.CANCELLED,
                updatedAt = System.currentTimeMillis(),
            )
            tasks[taskId] = cancelled
            logger.info { "Task cancelled: $taskId" }
            cancelled
        }

    /**
     * List all active (non-terminal) tasks.
     */
    fun listActiveTasks(): List<McpTask> = tasks.values.filter { it.status in listOf(TaskStatus.PENDING, TaskStatus.RUNNING) }

    /**
     * Clean up old completed/failed/cancelled tasks (older than TTL).
     */
    suspend fun cleanup(ttlMs: Long = 3_600_000) =
        mutex.withLock {
            // 1 hour default
            val cutoff = System.currentTimeMillis() - ttlMs
            val toRemove = tasks.values.filter {
                it.status in listOf(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED) &&
                    it.updatedAt < cutoff
            }
            toRemove.forEach { tasks.remove(it.id) }
            if (toRemove.isNotEmpty()) {
                logger.info { "Cleaned up ${toRemove.size} expired tasks" }
            }
        }

    // ── JSON-RPC dispatch helpers ────────────────────────────

    /**
     * Handle `tasks/get` JSON-RPC method.
     */
    fun handleTasksGet(params: JsonObject): JsonObject {
        val taskId = params["id"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing required param: 'id'")

        val task = getTask(taskId)
            ?: throw IllegalArgumentException("Unknown task: $taskId")

        return taskToJson(task)
    }

    /**
     * Handle `tasks/cancel` JSON-RPC method.
     */
    suspend fun handleTasksCancel(params: JsonObject): JsonObject {
        val taskId = params["id"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing required param: 'id'")

        val task = cancelTask(taskId)
            ?: throw IllegalArgumentException("Unknown task: $taskId")

        return taskToJson(task)
    }

    /**
     * Build a task handle response for returning to the client
     * when a tool call is deferred to a task.
     */
    fun buildTaskHandle(task: McpTask): JsonObject =
        buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "Task started: ${task.id}")
                        },
                    )
                },
            )
            putJsonObject("_meta") {
                put("taskId", task.id)
                put("status", task.status.name.lowercase())
            }
        }

    // ── Private ──────────────────────────────────────────────

    private fun taskToJson(task: McpTask): JsonObject =
        buildJsonObject {
            put("id", task.id)
            put("toolName", task.toolName)
            put("status", task.status.name.lowercase())
            task.progress?.let { put("progress", it) }
            task.result?.let { put("result", it) }
            task.error?.let { put("error", it) }
            put("createdAt", task.createdAt)
            put("updatedAt", task.updatedAt)
        }
}
