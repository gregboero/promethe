package dev.promethe.gateway

import dev.promethe.core.ToolCallOrigin
import dev.promethe.core.acp.AcpRequest
import dev.promethe.core.acp.AcpServer
import dev.promethe.core.acp.AcpResponse
import dev.promethe.core.acp.AcpStatus
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json

/**
 * ACP Protocol routes for the gateway.
 *
 * Endpoints:
 *   GET  /.well-known/acp.json   — Agent Card discovery
 *   POST /acp/invoke             — Invoke a capability
 *   GET  /acp/health             — Health check
 *
 * Execution goes through [A2AInternalClient] for unified session management,
 * event broadcasting, and consistency with all other entry points.
 */
fun interface AcpExecutor {
    suspend fun execute(
        sessionId: String,
        text: String,
        channelHint: String,
    ): String
}

fun Route.acpRoutes(
    a2aClient: A2AInternalClient,
    baseUrl: String = "http://localhost:8080",
) = acpRoutes(
    AcpExecutor { sessionId, text, channelHint ->
        a2aClient.execute(sessionId, text, channelHint, ToolCallOrigin.ACP)
    },
    baseUrl,
)

fun Route.acpRoutes(
    executor: AcpExecutor,
    baseUrl: String = "http://localhost:8080",
) {
    val acpServer = AcpServer(baseUrl)
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    // ── Discovery endpoint ──
    get(".well-known/acp.json") {
        val card = acpServer.buildAgentCard()
        call.respondText(
            json.encodeToString(dev.promethe.core.acp.AcpAgentCard.serializer(), card),
            ContentType.Application.Json,
        )
    }

    // ── ACP routes ──
    route("acp") {
        // Invoke a capability via A2A-aligned execution
        post("invoke") {
            val request = call.receive<AcpRequest>()
            val sessionId = "acp-${request.contextId ?: System.currentTimeMillis()}"
            val prompt = buildString {
                if (request.capabilityId != "chat") {
                    appendLine("[ACP capability: ${request.capabilityId}]")
                }
                if (request.parameters.isNotEmpty()) {
                    appendLine("Parameters: ${request.parameters}")
                }
                appendLine(request.input)
            }

            val responseText = executor.execute(sessionId, prompt, "acp")

            val acpResponse = AcpResponse(
                output = responseText.ifBlank { "Done." },
                status = if (responseText.startsWith("Error:")) AcpStatus.FAILED else AcpStatus.COMPLETED,
                taskId = sessionId,
                error = if (responseText.startsWith("Error:")) responseText else null,
            )
            call.respondText(
                json.encodeToString(AcpResponse.serializer(), acpResponse),
                ContentType.Application.Json,
                if (acpResponse.status == AcpStatus.COMPLETED) {
                    HttpStatusCode.OK
                } else {
                    HttpStatusCode.InternalServerError
                },
            )
        }

        // Health check
        get("health") {
            call.respond(
                mapOf(
                    "status" to "ok",
                    "protocol" to "ACP/1.0",
                    "capabilities" to acpServer.buildAgentCard().capabilities.size.toString(),
                ),
            )
        }
    }
}
