package dev.promethe.gateway

import dev.promethe.api.*
import dev.promethe.core.PrometheJson
import dev.promethe.core.ToolCallOrigin
import dev.promethe.core.config.ConfigProvider
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Channel webhook routes for Telegram, WhatsApp, Discord, Slack, Signal, and Matrix.
 *
 * Each handler follows the pattern:
 * 1. Verify webhook signature (if applicable)
 * 2. Parse channel-specific payload → extract (sessionPrefix, text)
 * 3. Create/ensure session in database
 * 4. Execute agent loop, emitting events to [AgentEventBus]
 * 5. Return the last response
 */
fun Route.channelWebhookRoutes(
    a2aClient: A2AInternalClient,
) {
    // ── Shared execution helper ──────────────────────────────────────────────

    /**
     * Common flow: route through A2A-aligned client → return last response.
     */
    suspend fun executeAndRespond(
        sessionId: String,
        text: String,
        call: io.ktor.server.application.ApplicationCall,
        channel: String = "webhook",
    ) {
        val lastResponse = a2aClient.execute(sessionId, text, channelHint = channel, origin = ToolCallOrigin.CHANNEL)
        call.respond(WebhookReply(reply = lastResponse))
    }

    // ── Telegram ─────────────────────────────────────────────────────────────

    post("/webhook/telegram") {
        val telegramSecret = ConfigProvider.get().get("TELEGRAM_SECRET_TOKEN", "")
        if (!WebhookAuth.verifyTelegram(call, telegramSecret)) return@post

        val rawBody = call.receiveBoundedWebhookBody() ?: return@post
        val requestBody = call.decodeWebhookBody<JsonObject>(rawBody) ?: return@post
        val eventId = requestBody["update_id"]?.jsonPrimitive?.content ?: WebhookReplayGuard.fingerprint(rawBody)
        if (!call.acceptWebhookEvent("telegram", eventId)) return@post
        val message = requestBody["message"]?.jsonObject
        val chat = message?.get("chat")?.jsonObject
        val chatId = chat?.get("id")?.jsonPrimitive?.content ?: "unknown"
        val text = message?.get("text")?.jsonPrimitive?.content ?: ""

        executeAndRespond("telegram-$chatId", text, call, channel = "telegram")
    }

    // ── WhatsApp Cloud API (Meta) ────────────────────────────────────────────

    get("/webhook/whatsapp") {
        val mode = call.request.queryParameters["hub.mode"] ?: ""
        val token = call.request.queryParameters["hub.verify_token"] ?: ""
        val challenge = call.request.queryParameters["hub.challenge"] ?: ""
        val expectedToken = ConfigProvider.get().get("WHATSAPP_VERIFY_TOKEN", "")
        if (mode == "subscribe" && WebhookAuth.verifyChallengeToken(call, token, expectedToken, "WhatsApp")) {
            call.respondText(challenge)
        } else if (mode != "subscribe") {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Invalid verify token"))
        }
    }

    post("/webhook/whatsapp") {
        val appSecret = ConfigProvider.get().get("WHATSAPP_APP_SECRET", "")
        val rawBody = call.receiveBoundedWebhookBody() ?: return@post
        if (!WebhookAuth.verifyWhatsApp(call, appSecret, rawBody)) return@post

        val payload = call.decodeWebhookBody<WhatsAppWebhookPayload>(rawBody) ?: return@post
        val msg = payload.entry
            .firstOrNull()?.changes
            ?.firstOrNull()?.value
            ?.messages?.firstOrNull()
        if (msg == null) {
            call.respond(WebhookReply(reply = "no message"))
            return@post
        }
        val eventId = msg.id.ifBlank { WebhookReplayGuard.fingerprint(rawBody) }
        if (!call.acceptWebhookEvent("whatsapp", eventId)) return@post

        executeAndRespond("whatsapp-${msg.from}", msg.text?.body ?: "", call, channel = "whatsapp")
    }

    // ── Discord Interactions ─────────────────────────────────────────────────

    post("/webhook/discord") {
        val publicKey = ConfigProvider.get().get("DISCORD_PUBLIC_KEY", "")
        val rawBody = call.receiveBoundedWebhookBody() ?: return@post
        if (!WebhookAuth.verifyDiscord(call, publicKey, rawBody)) return@post

        val interaction = call.decodeWebhookBody<DiscordInteraction>(rawBody) ?: return@post

        // PING (type=1) — required for Discord URL verification
        if (interaction.type == 1) {
            call.respond(DiscordInteractionResponse(type = 1))
            return@post
        }
        val eventId = interaction.id.ifBlank { WebhookReplayGuard.fingerprint(rawBody) }
        if (!call.acceptWebhookEvent("discord", eventId)) return@post

        // APPLICATION_COMMAND (type=2)
        val text = interaction.data?.options?.firstOrNull()?.value
            ?: interaction.data?.name ?: ""
        val sessionId = "discord-${interaction.channel_id}"

        val lastResponse = a2aClient.execute(sessionId, text, channelHint = "discord", origin = ToolCallOrigin.CHANNEL)

        call.respond(
            DiscordInteractionResponse(
                type = 4,
                data = DiscordResponseData(content = lastResponse),
            ),
        )
    }

    // ── Slack Events API ─────────────────────────────────────────────────────

    post("/webhook/slack") {
        val signingSecret = ConfigProvider.get().get("SLACK_SIGNING_SECRET", "")
        val rawBody = call.receiveBoundedWebhookBody() ?: return@post
        if (!WebhookAuth.verifySlack(call, signingSecret, rawBody)) return@post

        val payload = call.decodeWebhookBody<SlackEventPayload>(rawBody) ?: return@post

        // URL verification challenge
        if (payload.type == "url_verification") {
            call.respond(SlackChallengeResponse(challenge = payload.challenge))
            return@post
        }

        val event = payload.event
        if (event == null || (event.type != "message" && event.type != "app_mention")) {
            call.respond(WebhookReply(reply = "ignored"))
            return@post
        }
        val eventId = payload.event_id.ifBlank { event.ts.ifBlank { WebhookReplayGuard.fingerprint(rawBody) } }
        if (!call.acceptWebhookEvent("slack", eventId)) return@post

        executeAndRespond("slack-${event.channel}", event.text, call, channel = "slack")
    }

    // ── Signal CLI REST API ──────────────────────────────────────────────────

    post("/webhook/signal") {
        val webhookToken = ConfigProvider.get().get("SIGNAL_WEBHOOK_TOKEN", "")
        if (!WebhookAuth.verifySharedToken(call, webhookToken, "Signal")) return@post
        val rawBody = call.receiveBoundedWebhookBody() ?: return@post
        val payload = call.decodeWebhookBody<SignalWebhookPayload>(rawBody) ?: return@post
        val source = payload.envelope?.source ?: "unknown"
        val text = payload.envelope?.dataMessage?.message ?: ""

        if (text.isBlank()) {
            call.respond(WebhookReply(reply = "empty"))
            return@post
        }
        val eventId = payload.envelope?.timestamp?.takeIf { it > 0 }?.toString() ?: WebhookReplayGuard.fingerprint(rawBody)
        if (!call.acceptWebhookEvent("signal", "$source:$eventId")) return@post

        executeAndRespond("signal-$source", text, call, channel = "signal")
    }

    // ── Matrix Appservice ────────────────────────────────────────────────────

    post("/webhook/matrix") {
        val webhookToken = ConfigProvider.get().get("MATRIX_WEBHOOK_TOKEN", "")
        if (!WebhookAuth.verifySharedToken(call, webhookToken, "Matrix")) return@post
        val rawBody = call.receiveBoundedWebhookBody() ?: return@post
        val event = call.decodeWebhookBody<MatrixEvent>(rawBody) ?: return@post
        val matrixContent = event.content
        if (event.type != "m.room.message" || matrixContent?.msgtype != "m.text") {
            call.respond(WebhookReply(reply = "ignored"))
            return@post
        }
        val eventId = event.event_id.ifBlank { WebhookReplayGuard.fingerprint(rawBody) }
        if (!call.acceptWebhookEvent("matrix", eventId)) return@post

        executeAndRespond("matrix-${event.room_id}", matrixContent.body, call, channel = "matrix")
    }
}

private const val MAX_WEBHOOK_BODY_BYTES = 1_048_576

private suspend fun io.ktor.server.application.ApplicationCall.receiveBoundedWebhookBody(): String? {
    val declaredLength = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (declaredLength != null && declaredLength > MAX_WEBHOOK_BODY_BYTES) {
        respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Webhook payload exceeds 1 MiB"))
        return null
    }
    val body = receiveText()
    if (body.encodeToByteArray().size > MAX_WEBHOOK_BODY_BYTES) {
        respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Webhook payload exceeds 1 MiB"))
        return null
    }
    return body
}

private suspend inline fun <reified T> io.ktor.server.application.ApplicationCall.decodeWebhookBody(rawBody: String): T? =
    try {
        PrometheJson.decodeFromString<T>(rawBody)
    } catch (_: SerializationException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed webhook payload"))
        null
    } catch (_: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed webhook payload"))
        null
    }

private suspend fun io.ktor.server.application.ApplicationCall.acceptWebhookEvent(
    channel: String,
    eventId: String,
): Boolean {
    if (WebhookReplayGuard.accept(channel, eventId)) return true
    respond(HttpStatusCode.Accepted, WebhookReply(reply = "duplicate"))
    return false
}
