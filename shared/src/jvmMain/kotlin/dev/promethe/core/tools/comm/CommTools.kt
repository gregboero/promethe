package dev.promethe.core.tools.comm

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import dev.promethe.core.config.ConfigProvider

// ── Args ─────────────────────────────────────────────────────────────

@Serializable
data class SignalArgs(
    @property:LLMDescription("Action: 'send' or 'receive'. Default 'send'.")
    val action: String = "send",
    @property:LLMDescription("Recipient phone number (e.g. '+33612345678') for 'send'.")
    val recipient: String = "",
    @property:LLMDescription("Message text to send.")
    val message: String = "",
    @property:LLMDescription("Signal-cli-rest-api base URL. Default 'http://localhost:8080'.")
    val apiUrl: String = "http://localhost:8080",
    @property:LLMDescription("Sender phone number registered with signal-cli.")
    val sender: String = "",
)

@Serializable
data class SlackArgs(
    @property:LLMDescription("Action: 'send', 'list_channels'. Default 'send'.")
    val action: String = "send",
    @property:LLMDescription("Channel name or ID (e.g. '#general' or 'C01234').")
    val channel: String = "",
    @property:LLMDescription("Message text to post.")
    val message: String = "",
)

@Serializable
data class DiscordArgs(
    @property:LLMDescription("Discord webhook URL.")
    val webhookUrl: String = "",
    @property:LLMDescription("Message content to send.")
    val message: String = "",
    @property:LLMDescription("Username to display. Default 'Promethe'.")
    val username: String = "Promethe",
)

// ── Tools ────────────────────────────────────────────────────────────

class SignalTool(
    private val httpClient: HttpClient,
) : SimpleTool<SignalArgs>(
        argsType = typeToken<SignalArgs>(),
        name = "signal",
        description = "Send and receive messages via Signal messenger using signal-cli-rest-api.",
    ) {
    override suspend fun execute(args: SignalArgs): String {
        return when (args.action.lowercase()) {
            "send" -> {
                if (args.recipient.isBlank()) return "[ERROR] Recipient required."
                if (args.message.isBlank()) return "[ERROR] Message required."
                if (args.sender.isBlank()) return "[ERROR] Sender phone required."
                try {
                    val response = httpClient.post("${args.apiUrl}/v2/send") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"message":"${args.message}","number":"${args.sender}",""" +
                                """"recipients":["${args.recipient}"]}""",
                        )
                    }
                    val body = response.bodyAsText()
                    if (response.status.value in 200..299) {
                        "Signal message sent to ${args.recipient}"
                    } else {
                        "[ERROR] Signal API error ${response.status.value}: $body"
                    }
                } catch (e: Exception) {
                    "[ERROR] Signal send failed: ${e.message}"
                }
            }

            "receive" -> {
                if (args.sender.isBlank()) return "[ERROR] Sender phone required."
                try {
                    val response = httpClient.get(
                        "${args.apiUrl}/v1/receive/${args.sender}",
                    )
                    response.bodyAsText()
                } catch (e: Exception) {
                    "[ERROR] Signal receive failed: ${e.message}"
                }
            }

            else -> {
                "[ERROR] Unknown action '${args.action}'. Use: send, receive."
            }
        }
    }
}

class SlackTool(
    private val httpClient: HttpClient,
    private val token: String,
) : SimpleTool<SlackArgs>(
        argsType = typeToken<SlackArgs>(),
        name = "slack",
        description = "Post messages to Slack channels via the Slack API.",
    ) {
    override suspend fun execute(args: SlackArgs): String {
        if (token.isBlank()) return "[ERROR] SLACK_TOKEN not configured."
        return when (args.action.lowercase()) {
            "send" -> {
                if (args.channel.isBlank()) return "[ERROR] Channel required."
                if (args.message.isBlank()) return "[ERROR] Message required."
                try {
                    val response = httpClient.post("https://slack.com/api/chat.postMessage") {
                        contentType(ContentType.Application.Json)
                        headers { append("Authorization", "Bearer $token") }
                        setBody("""{"channel":"${args.channel}","text":"${args.message}"}""")
                    }
                    val body = response.bodyAsText()
                    if (body.contains("\"ok\":true")) {
                        "Message posted to ${args.channel}"
                    } else {
                        "[ERROR] Slack API: $body"
                    }
                } catch (e: Exception) {
                    "[ERROR] Slack post failed: ${e.message}"
                }
            }

            "list_channels" -> {
                try {
                    val response = httpClient.get(
                        "https://slack.com/api/conversations.list?limit=50",
                    ) {
                        headers { append("Authorization", "Bearer $token") }
                    }
                    response.bodyAsText()
                } catch (e: Exception) {
                    "[ERROR] Slack list failed: ${e.message}"
                }
            }

            else -> {
                "[ERROR] Unknown action '${args.action}'. Use: send, list_channels."
            }
        }
    }
}

class DiscordTool(
    private val httpClient: HttpClient,
) : SimpleTool<DiscordArgs>(
        argsType = typeToken<DiscordArgs>(),
        name = "discord",
        description = "Post messages via Discord webhook.",
    ) {
    override suspend fun execute(args: DiscordArgs): String {
        val webhookUrl = args.webhookUrl.ifBlank { ConfigProvider.get().get("DISCORD_WEBHOOK_URL", "") }
        if (webhookUrl.isBlank()) return "[ERROR] Webhook URL required (arg or DISCORD_WEBHOOK_URL env)."
        if (args.message.isBlank()) return "[ERROR] Message required."
        return try {
            val response = httpClient.post(webhookUrl) {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"${args.message}","username":"${args.username}"}""")
            }
            if (response.status.value in 200..299) {
                "Discord message sent."
            } else {
                "[ERROR] Discord webhook error: ${response.status.value}"
            }
        } catch (e: Exception) {
            "[ERROR] Discord send failed: ${e.message}"
        }
    }
}
