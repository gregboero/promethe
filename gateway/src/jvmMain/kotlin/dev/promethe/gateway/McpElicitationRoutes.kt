package dev.promethe.gateway

import dev.promethe.core.McpElicitationBroker
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
private data class McpElicitationPendingItem(
    val id: String,
    val serverId: String,
    val serverName: String,
    val requestKey: String,
    val message: String,
    val requestedSchema: JsonObject,
    val createdAt: Long,
    val expiresAt: Long,
)

@Serializable
private data class McpElicitationPendingResponse(
    val pending: List<McpElicitationPendingItem>,
)

@Serializable
private data class McpElicitationResponseRequest(
    val action: String,
    val content: JsonObject? = null,
)

@Serializable
private data class McpElicitationActionResponse(
    val success: Boolean,
    val error: String? = null,
)

/** Authenticated owner routes for interactive input requested by outbound MCP servers. */
fun Route.mcpElicitationRoutes(broker: McpElicitationBroker) {
    get("/approval/mcp/pending") {
        val pending =
            broker.listPending().map { request ->
                McpElicitationPendingItem(
                    id = request.id,
                    serverId = request.serverId,
                    serverName = request.serverName,
                    requestKey = request.requestKey,
                    message = request.message,
                    requestedSchema = request.requestedSchema,
                    createdAt = request.createdAt,
                    expiresAt = request.expiresAt,
                )
            }
        call.respond(McpElicitationPendingResponse(pending))
    }

    post("/approval/mcp/{id}") {
        val requestId = call.parameters["id"].orEmpty()
        val request = call.receive<McpElicitationResponseRequest>()
        when (broker.respond(requestId, request.action, request.content)) {
            McpElicitationBroker.ResponseResult.ACCEPTED -> {
                call.respond(McpElicitationActionResponse(success = true))
            }

            McpElicitationBroker.ResponseResult.NOT_FOUND -> {
                call.respond(
                    HttpStatusCode.NotFound,
                    McpElicitationActionResponse(success = false, error = "MCP input request not found"),
                )
            }

            McpElicitationBroker.ResponseResult.INVALID_ACTION -> {
                call.respond(
                    HttpStatusCode.BadRequest,
                    McpElicitationActionResponse(success = false, error = "Unknown MCP input action"),
                )
            }

            McpElicitationBroker.ResponseResult.INVALID_CONTENT -> {
                call.respond(
                    HttpStatusCode.BadRequest,
                    McpElicitationActionResponse(success = false, error = "MCP input does not match the requested schema"),
                )
            }
        }
    }
}
