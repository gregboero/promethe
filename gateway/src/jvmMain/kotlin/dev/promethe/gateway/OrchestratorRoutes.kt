package dev.promethe.gateway

import dev.promethe.api.*
import dev.promethe.core.AgentOrchestrator
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.orchestratorRoutes(orchestrator: AgentOrchestrator?) {
    route("/orchestrator") {
        @Suppress("DEPRECATION")
        intercept(ApplicationCallPipeline.Plugins) {
            if (orchestrator == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Orchestrator not initialized"))
                finish()
            }
        }

        post("/delegate") {
            try {
                val req = call.receive<dev.promethe.api.DelegationRequest>()
                val childId = orchestrator!!.delegateTask(
                    AgentOrchestrator.DelegationRequest(
                        task = req.task,
                        systemPromptOverride = req.systemPromptOverride,
                        profileId = req.profileId,
                        parentSessionId = req.parentSessionId,
                    ),
                )
                call.respond(DelegationResponse(childSessionId = childId, status = "delegated"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Delegation failed"))
            }
        }

        get("/status/{sessionId}") {
            try {
                val sessionId = call.parameters["sessionId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val status = orchestrator!!.getSubAgentStatus(sessionId)?.toDto()
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Sub-agent not found"))
                call.respond(status)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
            }
        }

        post("/{sessionId}/cancel") {
            try {
                val sessionId = call.parameters["sessionId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                orchestrator!!.cancelSubAgent(sessionId)
                call.respond(mapOf("status" to "cancelled"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Cancellation failed"))
            }
        }

        get("/children/{parentSessionId}") {
            try {
                val parentSessionId = call.parameters["parentSessionId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val childIds = orchestrator!!.listChildren(parentSessionId)
                val statuses = childIds.mapNotNull { orchestrator.getSubAgentStatus(it)?.toDto() }
                call.respond(statuses)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
            }
        }

        get("/dashboard") {
            try {
                val active = orchestrator!!.getActiveCount()
                val completed = orchestrator.getCompletedCount()
                val list = orchestrator.getSubAgentsStatus().map { it.toDto() }
                call.respond(
                    OrchestratorDashboardResponse(
                        activeCount = active,
                        completedCount = completed,
                        subAgents = list,
                    ),
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
            }
        }
    }
}

private fun AgentOrchestrator.SubAgentStatus.toDto() =
    SubAgentStatusResponse(
        sessionId = sessionId,
        profileId = profileId,
        task = task,
        status = status,
        response = response,
        durationMs = durationMs,
    )
