package dev.promethe.gateway

import dev.promethe.core.TaskScheduler
import dev.promethe.core.ToolCallOrigin
import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.db.ScheduledTaskRow
import kotlinx.coroutines.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * TaskExecutor — runs the scheduling tick loop in the gateway module.
 *
 * Executes due tasks via [A2AInternalClient], ensuring all scheduled task
 * execution goes through the same unified pipeline as chat, webhooks,
 * goals, and ACP invoke.
 *
 * [TaskScheduler] in the shared module handles only CRUD operations and cron
 * utilities (used by CronjobTool). This class handles the runtime execution.
 */
class TaskExecutor(
    private val database: PrometheDatabaseApi,
    private val a2aClient: A2AInternalClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        logger.info { "Starting task executor..." }
        job =
            scope.launch {
                while (isActive) {
                    try {
                        tick()
                    } catch (e: Exception) {
                        logger.error(e) { "Error during scheduler tick" }
                    }
                    delay(30_000) // Check every 30 seconds
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        logger.info { "Task executor stopped" }
    }

    fun isRunning(): Boolean = job?.isActive == true

    private suspend fun tick() {
        val tasks = database.getEnabledScheduledTasks()
        val now = System.currentTimeMillis()

        for (task in tasks) {
            if (shouldRun(task, now)) {
                logger.info { "Running task: ${task.name} (${task.id})" }
                executeTask(task, now)
            }
        }
    }

    private suspend fun executeTask(
        task: ScheduledTaskRow,
        now: Long,
    ) {
        val sessionId = "scheduled-${task.id}-$now"
        val isOneShot = task.runAt != null
        try {
            a2aClient.execute(sessionId, task.prompt, channelHint = "scheduler", origin = ToolCallOrigin.SCHEDULER)

            if (isOneShot) {
                // One-shot: disable after execution
                database.updateScheduledTaskRun(task.id, lastRunAt = now, nextRunAt = null, status = "completed")
                database.updateScheduledTask(task.copy(enabled = false))
                logger.info { "One-shot task ${task.name} completed and disabled" }
            } else {
                val nextRun = TaskScheduler.computeNextRun(task.cronExpression, now)
                database.updateScheduledTaskRun(task.id, lastRunAt = now, nextRunAt = nextRun, status = "success")
                logger.info { "Task ${task.name} completed successfully" }
            }
        } catch (e: Exception) {
            if (isOneShot) {
                database.updateScheduledTaskRun(task.id, lastRunAt = now, nextRunAt = null, status = "error")
                database.updateScheduledTask(task.copy(enabled = false))
                logger.error(e) { "One-shot task ${task.name} failed" }
            } else {
                val nextRun = TaskScheduler.computeNextRun(task.cronExpression, now)
                database.updateScheduledTaskRun(task.id, lastRunAt = now, nextRunAt = nextRun, status = "error")
                logger.error(e) { "Task ${task.name} failed" }
            }
        }
    }

    /**
     * Determine if a task should run now.
     */
    private fun shouldRun(
        task: ScheduledTaskRow,
        now: Long,
    ): Boolean {
        // One-shot: check if runAt has passed
        val runAt = task.runAt
        if (runAt != null) {
            return now >= runAt
        }

        // Recurring: check nextRunAt and cron match
        val nextRun = task.nextRunAt
        if (nextRun != null && now < nextRun) return false

        return TaskScheduler.matchesCron(task.cronExpression, now)
    }
}
