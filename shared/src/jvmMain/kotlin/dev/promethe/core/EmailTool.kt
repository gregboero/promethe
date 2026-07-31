package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ══════════════════════════════════════════════════════════════
//  Email Tool — Send emails via HTTP API
//  Supports multiple providers:
//    - Resend (default, recommended)
//    - SendGrid
//    - Mailgun
//  Set EMAIL_PROVIDER + EMAIL_API_KEY + EMAIL_FROM
// ══════════════════════════════════════════════════════════════

@Serializable
data class EmailArgs(
    @property:LLMDescription("Recipient email address.")
    val to: String,
    @property:LLMDescription("Email subject line.")
    val subject: String,
    @property:LLMDescription("Email body content. Can be plain text or HTML.")
    val body: String,
    @property:LLMDescription("Content type: 'text' or 'html'.")
    val contentType: String = "text",
    @property:LLMDescription("CC recipients (comma-separated).")
    val cc: String = "",
    @property:LLMDescription("Reply-to email address.")
    val replyTo: String = "",
)

class EmailTool(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val fromAddress: String,
    private val provider: String = "resend",
) : SimpleTool<EmailArgs>(
        argsType = typeToken<EmailArgs>(),
        name = "send_email",
        description = "Send an email. Supports plain text and HTML body, CC, and reply-to.",
    ) {
    private val json = PrometheJson

    override suspend fun execute(args: EmailArgs): String {
        if (apiKey.isBlank()) return "[ERROR] Email API key not configured (set EMAIL_API_KEY)"
        if (fromAddress.isBlank()) return "[ERROR] Sender address not configured (set EMAIL_FROM)"

        return try {
            when (provider.lowercase()) {
                "resend" -> sendViaResend(args)
                "sendgrid" -> sendViaSendGrid(args)
                "mailgun" -> sendViaMailgun(args)
                else -> sendViaResend(args) // Default to Resend
            }
        } catch (e: Exception) {
            "[ERROR] Failed to send email: ${e.message}"
        }
    }

    private suspend fun sendViaResend(args: EmailArgs): String {
        val payload =
            buildJsonObject {
                put("from", fromAddress)
                putJsonArray("to") {
                    args.to
                        .split(",")
                        .map { it.trim() }
                        .forEach { add(it) }
                }
                put("subject", args.subject)
                if (args.contentType == "html") put("html", args.body) else put("text", args.body)
                if (args.cc.isNotBlank()) {
                    putJsonArray("cc") {
                        args.cc
                            .split(",")
                            .map { it.trim() }
                            .forEach { add(it) }
                    }
                }
                if (args.replyTo.isNotBlank()) put("reply_to", args.replyTo)
            }

        val response =
            httpClient.post("https://api.resend.com/emails") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                timeout { requestTimeoutMillis = 15_000 }
                setBody(payload.toString())
            }

        return if (response.status.isSuccess()) {
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            "Email sent via Resend to ${args.to} (id: ${body["id"]?.jsonPrimitive?.content})"
        } else {
            "[ERROR] Resend API error ${response.status}: ${response.bodyAsText().take(200)}"
        }
    }

    private suspend fun sendViaSendGrid(args: EmailArgs): String {
        val contentKey = if (args.contentType == "html") "text/html" else "text/plain"
        val payload =
            buildJsonObject {
                putJsonArray("personalizations") {
                    addJsonObject {
                        putJsonArray("to") {
                            args.to.split(",").map { it.trim() }.forEach { email ->
                                addJsonObject { put("email", email) }
                            }
                        }
                        if (args.cc.isNotBlank()) {
                            putJsonArray("cc") {
                                args.cc.split(",").map { it.trim() }.forEach { email ->
                                    addJsonObject { put("email", email) }
                                }
                            }
                        }
                    }
                }
                putJsonObject("from") { put("email", fromAddress) }
                put("subject", args.subject)
                putJsonArray("content") {
                    addJsonObject {
                        put("type", contentKey)
                        put("value", args.body)
                    }
                }
                if (args.replyTo.isNotBlank()) {
                    putJsonObject("reply_to") { put("email", args.replyTo) }
                }
            }

        val response =
            httpClient.post("https://api.sendgrid.com/v3/mail/send") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                timeout { requestTimeoutMillis = 15_000 }
                setBody(payload.toString())
            }

        return if (response.status.value in 200..299) {
            "Email sent via SendGrid to ${args.to}"
        } else {
            "[ERROR] SendGrid API error ${response.status}: ${response.bodyAsText().take(200)}"
        }
    }

    private suspend fun sendViaMailgun(args: EmailArgs): String {
        // Mailgun uses form data, not JSON
        val domain = fromAddress.substringAfter("@")
        val response =
            httpClient.post("https://api.mailgun.net/v3/$domain/messages") {
                header("Authorization", "Basic ${java.util.Base64.getEncoder().encodeToString("api:$apiKey".toByteArray())}")
                timeout { requestTimeoutMillis = 15_000 }
                setBody(
                    io.ktor.client.request.forms.FormDataContent(
                        io.ktor.http.Parameters.build {
                            append("from", fromAddress)
                            append("to", args.to)
                            append("subject", args.subject)
                            if (args.contentType == "html") append("html", args.body) else append("text", args.body)
                            if (args.cc.isNotBlank()) append("cc", args.cc)
                            if (args.replyTo.isNotBlank()) append("h:Reply-To", args.replyTo)
                        },
                    ),
                )
            }

        return if (response.status.isSuccess()) {
            "Email sent via Mailgun to ${args.to}"
        } else {
            "[ERROR] Mailgun API error ${response.status}: ${response.bodyAsText().take(200)}"
        }
    }
}
