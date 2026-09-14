package dev.promethe.gateway

import dev.promethe.api.*
import dev.promethe.db.PrometheDatabaseApi
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Session CRUD, message history, and export routes.
 *
 * Extracted from OmnichannelGateway to keep that class focused on
 * server setup and route wiring only.
 */
fun Route.sessionRoutes(database: PrometheDatabaseApi) {
    get("/sessions") {
        val sessions = database.getAllSessions().filter { s ->
            !s.id.startsWith("sub-") &&
                !s.id.startsWith("profile-") &&
                !s.id.startsWith("a2a-") &&
                !s.id.startsWith("ctx-") &&
                !(s.metadata?.contains("\"parent\"") ?: false)
        }
        val counts = database.getSessionMessageCounts()
        val infos = sessions.map { s ->
            SessionInfo(
                id = s.id,
                createdAt = s.createdAt,
                messageCount = counts[s.id] ?: 0,
                title = s.title,
                metadata = s.metadata,
                projectId = s.projectId,
            )
        }
        call.respond(SessionListResponse(infos))
    }

    post("/sessions") {
        val req = call.receive<CreateSessionRequest>()
        val id = req.id ?: "session-${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        val requestedProjectId = req.projectId ?: database.getSetting(ACTIVE_PROJECT_SETTING_KEY)
        val project = requestedProjectId?.let { database.getProject(it) }
        if (requestedProjectId != null && (project == null || project.archived)) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown or archived project"))
        }
        database.insertSessionOrIgnore(id, now, "{}")
        if (requestedProjectId != null) database.assignSessionToProject(id, requestedProjectId)
        val session = database.getSession(id)
        call.respond(
            HttpStatusCode.Created,
            SessionInfo(
                id = id,
                createdAt = session?.createdAt ?: now,
                messageCount = 0,
                title = session?.title,
                metadata = session?.metadata,
                projectId = requestedProjectId,
            ),
        )
    }

    patch("/sessions/{id}/project") {
        val sessionId = call.parameters["id"]
            ?: return@patch call.respond(HttpStatusCode.BadRequest)
        val session = database.getSession(sessionId)
            ?: return@patch call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
        val request = call.receive<AssignSessionProjectRequest>()
        val project = request.projectId?.let { database.getProject(it) }
        if (request.projectId != null && (project == null || project.archived)) {
            return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown or archived project"))
        }
        database.assignSessionToProject(sessionId, request.projectId)
        call.respond(
            SessionInfo(
                id = session.id,
                createdAt = session.createdAt,
                messageCount = database.getSessionMessageCounts()[session.id] ?: 0,
                title = session.title,
                metadata = session.metadata,
                projectId = request.projectId,
            ),
        )
    }

    delete("/sessions/{id}") {
        val id = call.parameters["id"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest)
        database.deleteSession(id)
        call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
    }

    get("/sessions/{id}/messages") {
        val sessionId = call.parameters["id"]
            ?: return@get call.respond(HttpStatusCode.BadRequest)
        val messages = database.getMessagesForSession(sessionId)
        val events =
            messages.mapNotNull { message ->
                val type =
                    when (message.role.lowercase()) {
                        "user" -> "user"
                        "assistant" -> "response"
                        else -> null
                    }
                type?.let {
                    ChatEvent(
                        type = it,
                        content = message.content,
                        timestamp = message.timestamp,
                    )
                }
            }
        call.respond(events)
    }

    patch("/sessions/{id}/metadata") {
        val sessionId = call.parameters["id"]
            ?: return@patch call.respond(HttpStatusCode.BadRequest)
        val body = call.receiveText()
        database.updateSessionMetadata(sessionId, body)
        call.respond(mapOf("status" to "ok"))
    }

    // ── Export ────────────────────────────────────────────────────────────────

    get("/sessions/{id}/export/json") {
        val sessionId = call.parameters["id"]
            ?: return@get call.respond(HttpStatusCode.BadRequest)
        val messages = database.getMessagesForSession(sessionId)
        val sessions = database.getAllSessions()
        val session = sessions.find { it.id == sessionId }

        val export = ConversationExport(
            sessionId = sessionId,
            title = session?.title,
            createdAt = session?.createdAt ?: 0,
            messages = messages.map { m ->
                ExportMessage(role = m.role, content = m.content, timestamp = m.timestamp)
            },
            exportedAt = System.currentTimeMillis(),
        )
        call.response.header("Content-Disposition", "attachment; filename=\"$sessionId.json\"")
        call.respond(export)
    }

    get("/sessions/{id}/export/markdown") {
        val sessionId = call.parameters["id"]
            ?: return@get call.respond(HttpStatusCode.BadRequest)
        val messages = database.getMessagesForSession(sessionId)
        val sessions = database.getAllSessions()
        val session = sessions.find { it.id == sessionId }

        val sb = StringBuilder()
        sb.appendLine("# ${session?.title ?: sessionId}")
        sb.appendLine()
        messages.forEach { m ->
            val prefix = if (m.role == "user") "**You**" else "**Prométhé**"
            sb.appendLine("### $prefix")
            sb.appendLine(m.content)
            sb.appendLine()
        }
        call.response.header("Content-Type", "text/markdown")
        call.response.header("Content-Disposition", "attachment; filename=\"$sessionId.md\"")
        call.respondText(sb.toString())
    }

    // ── Checkpoint & Rollback ────────────────────────────────────────────────

    get("/sessions/{id}/checkpoint") {
        val sessionId = call.parameters["id"]
            ?: return@get call.respond(HttpStatusCode.BadRequest)
        val checkpoint = database.getLatestCheckpoint(sessionId)
        if (checkpoint != null) {
            call.respondText(checkpoint, ContentType.Application.Json)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "No checkpoint found"))
        }
    }

    post("/sessions/{id}/rollback") {
        val sessionId = call.parameters["id"]
            ?: return@post call.respond(HttpStatusCode.BadRequest)
        val checkpoint = database.getLatestCheckpoint(sessionId)
        if (checkpoint != null) {
            call.respond(
                mapOf(
                    "status" to "rollback_ready",
                    "checkpoint" to kotlinx.serialization.json.Json.parseToJsonElement(checkpoint),
                ),
            )
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "No checkpoint to rollback to"))
        }
    }

    delete("/sessions/{id}/checkpoints") {
        val sessionId = call.parameters["id"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest)
        database.clearCheckpoints(sessionId)
        call.respond(mapOf("status" to "cleared"))
    }
}
