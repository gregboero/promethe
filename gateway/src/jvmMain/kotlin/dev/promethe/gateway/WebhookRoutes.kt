package dev.promethe.gateway

import dev.promethe.api.*
import dev.promethe.core.WebhookManager
import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.db.WebhookChannelRow
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Webhook API routes for Prométhé gateway.
 *
 * Inbound:  POST /api/v1/webhooks/in/{channel}   — receive events from external platforms
 * Config:   CRUD /api/v1/webhooks/channels        — manage webhook channel configurations
 * Dispatch: POST /api/v1/webhooks/dispatch        — manually send a message to a channel
 */
fun Route.webhookRoutes(
    database: PrometheDatabaseApi,
    webhookManager: WebhookManager,
) {
    route("webhooks") {
        // ── Inbound webhook receiver ──
        // External platforms POST here (e.g., Telegram bot webhook URL)
        post("in/{channel}") {
            val channel =
                call.parameters["channel"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing channel"))

            val body = call.receiveText()
            val headers =
                call.request.headers
                    .entries()
                    .associate { (k, v) -> k.lowercase() to v.first() }

            val response = webhookManager.handleInbound(channel, body, headers)
            call.respond(HttpStatusCode.OK, mapOf("response" to response))
        }

        // ── Manual outbound dispatch ──
        post("dispatch") {
            val req = call.receive<WebhookDispatchRequest>()
            val result = webhookManager.dispatch(req.channel, req.content)
            call.respond(
                WebhookDispatchResponse(
                    success = result.success,
                    channel = result.channel,
                    statusCode = result.statusCode,
                    error = result.error,
                ),
            )
        }

        // ── Channel CRUD ──
        route("channels") {
            get {
                val channels = database.getAllWebhookChannels()
                call.respond(
                    WebhookChannelListResponse(
                        channels = channels.map { it.toConfig() },
                    ),
                )
            }

            post {
                val config = call.receive<WebhookChannelConfig>()
                val id = config.id.ifBlank { "wh-${System.currentTimeMillis()}" }
                database.insertWebhookChannel(
                    WebhookChannelRow(
                        id = id,
                        name = config.name,
                        type = config.type,
                        secret = config.secret,
                        outboundUrl = config.outboundUrl,
                        enabled = config.enabled,
                        headerName = config.headerName,
                        payloadTemplate = config.payloadTemplate,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                call.respond(HttpStatusCode.Created, mapOf("id" to id))
            }

            put("{id}") {
                val id =
                    call.parameters["id"]
                        ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                val config = call.receive<WebhookChannelConfig>()
                database.updateWebhookChannel(
                    WebhookChannelRow(
                        id = id,
                        name = config.name,
                        type = config.type,
                        secret = config.secret,
                        outboundUrl = config.outboundUrl,
                        enabled = config.enabled,
                        headerName = config.headerName,
                        payloadTemplate = config.payloadTemplate,
                    ),
                )
                call.respond(HttpStatusCode.OK, mapOf("updated" to id))
            }

            delete("{id}") {
                val id =
                    call.parameters["id"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                database.deleteWebhookChannel(id)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to id))
            }
        }
    }
}

// ── Helpers ──

private fun WebhookChannelRow.toConfig() =
    WebhookChannelConfig(
        id = id,
        name = name,
        type = type,
        secret = secret,
        outboundUrl = outboundUrl,
        enabled = enabled,
        headerName = headerName,
        payloadTemplate = payloadTemplate,
        createdAt = createdAt,
    )
