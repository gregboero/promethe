package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.config.ConfigProvider
import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Base64

// ══════════════════════════════════════════════════════════════
//  Twilio Messaging Tool — SMS + WhatsApp
//  Send and receive messages via Twilio API.
//  Requires TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_PHONE_NUMBER
// ══════════════════════════════════════════════════════════════

@Serializable
data class TwilioArgs(
    @property:LLMDescription("Action: 'send_sms', 'send_whatsapp', 'list_messages', 'get_message'")
    val action: String,
    @property:LLMDescription("Recipient phone number in E.164 format (e.g., '+1234567890').")
    val to: String = "",
    @property:LLMDescription("Message body text.")
    val body: String = "",
    @property:LLMDescription("Message SID for get_message action.")
    val messageSid: String = "",
    @property:LLMDescription("Max messages to list (default: 10).")
    val limit: Int = 10,
    @property:LLMDescription("Media URL to include (for MMS or WhatsApp images).")
    val mediaUrl: String = "",
)

class TwilioTool(
    private val httpClient: HttpClient,
    private val accountSidOverride: String = "",
    private val authTokenOverride: String = "",
    private val fromNumberOverride: String = "",
) : SimpleTool<TwilioArgs>(
        argsType = typeToken<TwilioArgs>(),
        name = "twilio",
        description = "Send SMS and WhatsApp messages via Twilio. List and retrieve message history.",
    ) {
    private val json = PrometheJson

    override suspend fun execute(args: TwilioArgs): String {
        val credentials = resolveCredentials()
        if (credentials.accountSid.isBlank() || credentials.authToken.isBlank()) {
            return "[ERROR] Twilio not configured. Set TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN."
        }

        return try {
            when (args.action.lowercase()) {
                "send_sms" -> sendMessage(credentials, args.to, args.body, args.mediaUrl, whatsapp = false)
                "send_whatsapp" -> sendMessage(credentials, args.to, args.body, args.mediaUrl, whatsapp = true)
                "list_messages" -> listMessages(credentials, args.limit)
                "get_message" -> getMessage(credentials, args.messageSid)
                else -> "[ERROR] Unknown Twilio action: ${args.action}. Use: send_sms, send_whatsapp, list_messages, get_message"
            }
        } catch (e: Exception) {
            "[ERROR] Twilio API call failed: ${e.message}"
        }
    }

    private suspend fun sendMessage(
        credentials: TwilioCredentials,
        to: String,
        body: String,
        mediaUrl: String,
        whatsapp: Boolean,
    ): String {
        if (to.isBlank()) return "[ERROR] Recipient phone number required"
        if (body.isBlank()) return "[ERROR] Message body required"
        if (credentials.fromNumber.isBlank()) return "[ERROR] TWILIO_PHONE_NUMBER is required to send messages"

        val from = if (whatsapp) "whatsapp:${credentials.fromNumber}" else credentials.fromNumber
        val toFormatted = if (whatsapp) "whatsapp:$to" else to

        val response =
            httpClient.post("${credentials.baseUrl}/Messages.json") {
                header("Authorization", credentials.authHeader)
                timeout { requestTimeoutMillis = 15_000 }
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("From", from)
                            append("To", toFormatted)
                            append("Body", body)
                            if (mediaUrl.isNotBlank()) append("MediaUrl", mediaUrl)
                        },
                    ),
                )
            }

        val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject

        return if (response.status.isSuccess()) {
            val sid = obj["sid"]?.jsonPrimitive?.content ?: "?"
            val status = obj["status"]?.jsonPrimitive?.content ?: "?"
            val channel = if (whatsapp) "WhatsApp" else "SMS"
            "$channel sent to $to (SID: $sid, status: $status)"
        } else {
            val errorMsg = obj["message"]?.jsonPrimitive?.content ?: response.bodyAsText().take(200)
            "[ERROR] Twilio: $errorMsg"
        }
    }

    private suspend fun listMessages(
        credentials: TwilioCredentials,
        limit: Int,
    ): String {
        val response =
            httpClient.get("${credentials.baseUrl}/Messages.json?PageSize=${limit.coerceIn(1, 1000)}") {
                header("Authorization", credentials.authHeader)
                timeout { requestTimeoutMillis = 15_000 }
            }

        val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val messages = obj["messages"]?.jsonArray ?: return "No messages found."

        return messages
            .joinToString("\n") { el ->
                val msg = el.jsonObject
                val dir = msg["direction"]?.jsonPrimitive?.content ?: "?"
                val from = msg["from"]?.jsonPrimitive?.content ?: "?"
                val to = msg["to"]?.jsonPrimitive?.content ?: "?"
                val body = msg["body"]?.jsonPrimitive?.content?.take(50) ?: ""
                val status = msg["status"]?.jsonPrimitive?.content ?: "?"
                "[$dir] $from → $to: $body ($status)"
            }.ifBlank { "No messages found." }
    }

    private suspend fun getMessage(
        credentials: TwilioCredentials,
        sid: String,
    ): String {
        if (sid.isBlank()) return "[ERROR] Message SID required"

        val response =
            httpClient.get("${credentials.baseUrl}/Messages/$sid.json") {
                header("Authorization", credentials.authHeader)
                timeout { requestTimeoutMillis = 15_000 }
            }

        val msg = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return buildString {
            appendLine("SID: ${msg["sid"]?.jsonPrimitive?.content}")
            appendLine("From: ${msg["from"]?.jsonPrimitive?.content}")
            appendLine("To: ${msg["to"]?.jsonPrimitive?.content}")
            appendLine("Status: ${msg["status"]?.jsonPrimitive?.content}")
            appendLine("Direction: ${msg["direction"]?.jsonPrimitive?.content}")
            appendLine("Date: ${msg["date_sent"]?.jsonPrimitive?.content}")
            appendLine("Body: ${msg["body"]?.jsonPrimitive?.content}")
            val price = msg["price"]?.jsonPrimitive?.content
            if (price != null) appendLine("Price: $price ${msg["price_unit"]?.jsonPrimitive?.content}")
        }
    }

    private fun resolveCredentials(): TwilioCredentials {
        val config = ConfigProvider.get()
        return TwilioCredentials(
            accountSid = accountSidOverride.ifBlank { config.get("TWILIO_ACCOUNT_SID", "") },
            authToken = authTokenOverride.ifBlank { config.get("TWILIO_AUTH_TOKEN", "") },
            fromNumber = fromNumberOverride.ifBlank { config.get("TWILIO_PHONE_NUMBER", "") },
        )
    }

    private data class TwilioCredentials(
        val accountSid: String,
        val authToken: String,
        val fromNumber: String,
    ) {
        val baseUrl: String
            get() = "https://api.twilio.com/2010-04-01/Accounts/$accountSid"

        val authHeader: String
            get() =
                "Basic " +
                    Base64
                        .getEncoder()
                        .encodeToString("$accountSid:$authToken".toByteArray())
    }
}
