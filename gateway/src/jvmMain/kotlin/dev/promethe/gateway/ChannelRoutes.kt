package dev.promethe.gateway

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * ChannelRoutes -- manage messaging channel configuration.
 *
 * Provides:
 * - GET  /api/v1/channels                 -- list all channels with status
 * - PUT  /api/v1/channels/{name}/config   -- set env vars for a channel
 * - POST /api/v1/channels/{name}/test     -- send a test message
 */
fun Route.channelRoutes() {
    val store = EnvConfigStore

    get("/channels") {
        val env = store.loadAll()
        val channels = CHANNEL_DEFINITIONS.map { def ->
            val configured = def.requiredKeys.all { key -> env[key]?.isNotBlank() == true }
            ChannelStatus(
                name = def.name,
                displayName = def.displayName,
                configured = configured,
                requiredKeys = def.requiredKeys,
                optionalKeys = def.optionalKeys,
                webhookPath = def.webhookPath,
            )
        }
        call.respond(mapOf("channels" to channels))
    }

    put("/channels/{name}/config") {
        val name = call.parameters["name"]
            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing channel name"))
        val def = CHANNEL_DEFINITIONS.find { it.name == name }
            ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown channel: $name"))
        val body = call.receive<Map<String, String>>()

        // Only allow setting keys defined for this channel
        val allowedKeys = def.requiredKeys + def.optionalKeys
        body.forEach { (key, value) ->
            if (key in allowedKeys) {
                store.set(key, value)
            }
        }
        call.respond(mapOf("status" to "configured", "channel" to name))
    }

    post("/channels/{name}/test") {
        val name = call.parameters["name"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing channel name"))
        val def = CHANNEL_DEFINITIONS.find { it.name == name }
            ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown channel: $name"))
        val env = store.loadAll()
        val configured = def.requiredKeys.all { key -> env[key]?.isNotBlank() == true }
        if (!configured) {
            call.respond(HttpStatusCode.PreconditionFailed, mapOf("error" to "Channel not fully configured"))
            return@post
        }
        // Test is best-effort — just confirms config is present
        call.respond(mapOf("status" to "ok", "channel" to name, "message" to "Configuration valid"))
    }
}

@Serializable
data class ChannelStatus(
    val name: String,
    val displayName: String,
    val configured: Boolean,
    val requiredKeys: List<String>,
    val optionalKeys: List<String> = emptyList(),
    val webhookPath: String,
)

data class ChannelDefinition(
    val name: String,
    val displayName: String,
    val requiredKeys: List<String>,
    val optionalKeys: List<String> = emptyList(),
    val webhookPath: String,
)

private val CHANNEL_DEFINITIONS = listOf(
    ChannelDefinition(
        name = "telegram",
        displayName = "Telegram",
        requiredKeys = listOf("TELEGRAM_BOT_TOKEN"),
        optionalKeys = listOf("TELEGRAM_SECRET_TOKEN"),
        webhookPath = "/webhook/telegram",
    ),
    ChannelDefinition(
        name = "discord",
        displayName = "Discord",
        requiredKeys = listOf("DISCORD_BOT_TOKEN"),
        optionalKeys =
            listOf(
                "DISCORD_PUBLIC_KEY",
                "DISCORD_GUILD_ID",
                "DISCORD_MESSAGE_CONTENT_ENABLED",
                "DISCORD_ALLOWED_USER_IDS",
                "DISCORD_KNOWLEDGE_CHANNEL_IDS",
            ),
        webhookPath = "/webhook/discord",
    ),
    ChannelDefinition(
        name = "slack",
        displayName = "Slack",
        requiredKeys = listOf("SLACK_BOT_TOKEN", "SLACK_SIGNING_SECRET"),
        webhookPath = "/webhook/slack",
    ),
    ChannelDefinition(
        name = "whatsapp",
        displayName = "WhatsApp Cloud",
        requiredKeys = listOf("WHATSAPP_PHONE_NUMBER_ID", "WHATSAPP_ACCESS_TOKEN"),
        optionalKeys = listOf("WHATSAPP_VERIFY_TOKEN", "WHATSAPP_APP_SECRET"),
        webhookPath = "/webhook/whatsapp",
    ),
    ChannelDefinition(
        name = "signal",
        displayName = "Signal",
        requiredKeys = listOf("SIGNAL_CLI_REST_URL", "SIGNAL_PHONE_NUMBER"),
        webhookPath = "/webhook/signal",
    ),
    ChannelDefinition(
        name = "matrix",
        displayName = "Matrix",
        requiredKeys = listOf("MATRIX_HOMESERVER_URL", "MATRIX_ACCESS_TOKEN"),
        webhookPath = "/webhook/matrix",
    ),
    ChannelDefinition(
        name = "sms",
        displayName = "SMS (Twilio)",
        requiredKeys = listOf("TWILIO_ACCOUNT_SID", "TWILIO_AUTH_TOKEN", "TWILIO_PHONE_NUMBER"),
        webhookPath = "/webhook/sms",
    ),
)
