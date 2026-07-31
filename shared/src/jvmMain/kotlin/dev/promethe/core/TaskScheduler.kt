package dev.promethe.core

import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.db.ScheduledTaskRow
import java.time.LocalDateTime
import java.time.ZoneOffset

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * TaskScheduler — CRUD operations and cron utilities for scheduled tasks.
 *
 * This class handles:
 *   - Scheduling new tasks (used by CronjobTool)
 *   - Listing, cancelling tasks
 *   - Cron expression parsing and matching
 *
 * Execution of due tasks is handled by [TaskExecutor] in the gateway module,
 * which uses [A2AInternalClient] for unified session management and event broadcasting.
 */
class TaskScheduler(
    private val database: PrometheDatabaseApi,
) {
    // ── LLM-callable API (used by CronjobTool) ─────────────────

    data class ScheduledJob(
        val id: String,
        val cronExpression: String,
        val description: String,
    )

    /**
     * Schedule a new recurring task.
     */
    suspend fun schedule(
        jobId: String,
        cronExpression: String,
        taskDescription: String,
    ) {
        // Validate cron expression
        val parts = cronExpression.trim().split(Regex("\\s+"))
        require(parts.size == 5) { "Invalid cron expression: need 5 fields (min hour dom month dow)" }

        val now = System.currentTimeMillis()
        val nextRun = computeNextRun(cronExpression, now)
        database.insertScheduledTask(
            ScheduledTaskRow(
                id = jobId,
                name = jobId,
                cronExpression = cronExpression,
                prompt = taskDescription,
                enabled = true,
                nextRunAt = nextRun,
                createdAt = now,
            ),
        )
        logger.info { "Created job '$jobId': $cronExpression" }
    }

    /**
     * List all enabled scheduled jobs.
     */
    suspend fun listJobs(): List<ScheduledJob> =
        database.getEnabledScheduledTasks().map { row ->
            ScheduledJob(
                id = row.id,
                cronExpression = row.cronExpression,
                description = row.prompt,
            )
        }

    /**
     * Cancel (delete) a scheduled job by ID.
     */
    suspend fun cancel(jobId: String): Boolean =
        try {
            database.deleteScheduledTask(jobId)
            logger.info { "Deleted job '$jobId'" }
            true
        } catch (e: Exception) {
            logger.warn(e) { "Failed to delete scheduled job '$jobId'" }
            false
        }

    /**
     * Get count of enabled tasks (for health check / status).
     */
    fun getTaskCount(): Int =
        kotlinx.coroutines.runBlocking {
            try {
                database.getEnabledScheduledTasks().size
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch enabled scheduled task count" }
                0
            }
        }

    companion object {
        /**
         * Simple cron expression matcher.
         * Supports: numbers, ranges (1-5), steps (star/5), lists (1,3,5), and star (any).
         */
        fun matchesCron(
            expression: String,
            timestampMs: Long,
        ): Boolean {
            val parts = expression.trim().split(Regex("\\s+"))
            if (parts.size != 5) return false

            val dt = LocalDateTime.ofEpochSecond(timestampMs / 1000, 0, ZoneOffset.UTC)
            val minute = dt.minute
            val hour = dt.hour
            val dayOfMonth = dt.dayOfMonth
            val month = dt.monthValue
            val dayOfWeek = dt.dayOfWeek.value % 7 // 0=Sunday

            return matchesField(parts[0], minute, 0, 59) &&
                matchesField(parts[1], hour, 0, 23) &&
                matchesField(parts[2], dayOfMonth, 1, 31) &&
                matchesField(parts[3], month, 1, 12) &&
                matchesField(parts[4], dayOfWeek, 0, 6)
        }

        private fun matchesField(
            field: String,
            value: Int,
            min: Int,
            max: Int,
        ): Boolean {
            if (field == "*") return true

            // Handle lists: "1,3,5"
            return field.split(",").any { part ->
                when {
                    // Step: "*/5" or "1-10/2"
                    part.contains("/") -> {
                        val (range, stepStr) = part.split("/", limit = 2)
                        val step = stepStr.toIntOrNull() ?: return@any false
                        if (step <= 0) return@any false
                        val (start, end) =
                            if (range == "*") {
                                min to max
                            } else if (range.contains("-")) {
                                val (s, e) = range.split("-", limit = 2)
                                (s.toIntOrNull() ?: min) to (e.toIntOrNull() ?: max)
                            } else {
                                val s = range.toIntOrNull() ?: return@any false
                                s to max
                            }
                        value in start..end && (value - start) % step == 0
                    }

                    // Range: "1-5"
                    part.contains("-") -> {
                        val (s, e) = part.split("-", limit = 2)
                        val start = s.toIntOrNull() ?: return@any false
                        val end = e.toIntOrNull() ?: return@any false
                        value in start..end
                    }

                    // Exact number
                    else -> {
                        part.toIntOrNull() == value
                    }
                }
            }
        }

        /**
         * Compute next run time from a cron expression.
         * Simple approach: scan forward minute by minute (max 1 year).
         */
        fun computeNextRun(
            expression: String,
            fromMs: Long,
        ): Long? {
            val startSecond = (fromMs / 1000) + 60 // Start from next minute
            // Check up to ~1 year forward (525600 minutes)
            for (i in 0 until 525600) {
                val candidate = (startSecond + i * 60) * 1000
                if (matchesCron(expression, candidate)) {
                    return candidate
                }
            }
            return null
        }
    }
}
