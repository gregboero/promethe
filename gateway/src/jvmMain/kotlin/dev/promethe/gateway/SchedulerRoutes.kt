package dev.promethe.gateway

import dev.promethe.api.ScheduledTaskListResponse
import dev.promethe.api.ScheduledTaskRequest
import dev.promethe.api.ScheduledTaskResponse
import dev.promethe.core.TaskScheduler
import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.db.ScheduledTaskRow
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * REST routes for managing scheduled tasks (cron jobs).
 * CRUD: GET/POST/PUT/DELETE /api/v1/scheduler/tasks
 */
fun Route.schedulerRoutes(
    database: PrometheDatabaseApi,
    scheduler: TaskScheduler,
) {
    route("scheduler/tasks") {
        // List all tasks
        get {
            val tasks = database.getAllScheduledTasks()
            call.respond(ScheduledTaskListResponse(tasks.map { it.toResponse() }))
        }

        // Create a new task
        post {
            val req = call.receive<ScheduledTaskRequest>()
            val id = "task-${System.currentTimeMillis()}"
            val now = System.currentTimeMillis()
            val isOneShot = req.runAt != null
            val nextRun = if (isOneShot) req.runAt else TaskScheduler.computeNextRun(req.cronExpression, now)

            val row =
                ScheduledTaskRow(
                    id = id,
                    name = req.name,
                    cronExpression = req.cronExpression,
                    prompt = req.prompt,
                    profileId = req.profileId,
                    enabled = req.enabled,
                    nextRunAt = nextRun,
                    createdAt = now,
                    runAt = req.runAt,
                )
            database.insertScheduledTask(row)
            call.respond(HttpStatusCode.Created, row.toResponse())
        }

        // Get a specific task
        get("{id}") {
            val id =
                call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
            val task =
                database.getScheduledTask(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
            call.respond(task.toResponse())
        }

        // Update a task
        put("{id}") {
            val id =
                call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest)
            val existing =
                database.getScheduledTask(id)
                    ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
            val req = call.receive<ScheduledTaskRequest>()

            // Recalculate nextRunAt if cron expression or runAt changed
            val now = System.currentTimeMillis()
            val isOneShot = req.runAt != null
            val nextRun = if (isOneShot) req.runAt else TaskScheduler.computeNextRun(req.cronExpression, now)

            val updated =
                existing.copy(
                    name = req.name,
                    cronExpression = req.cronExpression,
                    prompt = req.prompt,
                    profileId = req.profileId,
                    enabled = req.enabled,
                    nextRunAt = nextRun,
                    runAt = req.runAt,
                )
            database.updateScheduledTask(updated)
            call.respond(updated.toResponse())
        }

        // Delete a task
        delete("{id}") {
            val id =
                call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)
            database.deleteScheduledTask(id)
            call.respond(mapOf("status" to "deleted"))
        }

        // Toggle enable/disable
        post("{id}/toggle") {
            val id =
                call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
            val task =
                database.getScheduledTask(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Task not found"))
            val updated = task.copy(enabled = !task.enabled)
            database.updateScheduledTask(updated)
            call.respond(updated.toResponse())
        }
    }
}

private fun ScheduledTaskRow.toResponse() =
    ScheduledTaskResponse(
        id = id,
        name = name,
        cronExpression = cronExpression,
        prompt = prompt,
        profileId = profileId,
        enabled = enabled,
        lastRunAt = lastRunAt,
        nextRunAt = nextRunAt,
        lastRunStatus = lastRunStatus,
        createdAt = createdAt,
        runAt = runAt,
    )
