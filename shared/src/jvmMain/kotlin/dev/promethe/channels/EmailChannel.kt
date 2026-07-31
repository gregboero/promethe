package dev.promethe.channels

import dev.promethe.core.PrometheJson
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * EmailChannel — connects Prométhé to email via SMTP (send) and IMAP (receive).
 *
 * ⚠️ DEPENDENCY NOTE:
 * This implementation uses Ktor's HttpClient for webhook-based email services
 * (e.g., SendGrid Inbound Parse, Mailgun Routes) and provides a raw SMTP
 * implementation for sending. For full IMAP receive support, add:
 *
 *   implementation("com.sun.mail:jakarta.mail:2.0.1")
 *
 * OR use a webhook-based inbound email provider and route payloads to parseInboundWebhook().
 *
 * Setup:
 *   Option A (Webhook-based): Use SendGrid/Mailgun inbound webhooks → parseInboundWebhook()
 *   Option B (IMAP polling): Add jakarta.mail dependency → use pollImap() directly
 *   Sending: Uses Ktor HTTP to talk to SMTP relay APIs, or raw SMTP via socket
 *
 * This class handles:
 *   - Sending email via SMTP relay HTTP APIs (SendGrid/Mailgun/generic)
 *   - Parsing inbound email webhook payloads
 *   - Structured email representation
 */
class EmailChannel(
    private val smtpHost: String, // SMTP relay host or API endpoint
    private val smtpPort: Int = 587, // SMTP port (587 for TLS, 465 for SSL)
    private val username: String, // SMTP auth username / API key
    private val password: String, // SMTP auth password / API secret
    private val fromAddress: String, // Sender email address
    private val httpClient: HttpClient,
    private val apiMode: ApiMode = ApiMode.SENDGRID, // Which HTTP email API to use
) {
    private val json = PrometheJson

    enum class ApiMode {
        SENDGRID, // Uses SendGrid v3 API
        MAILGUN, // Uses Mailgun API
        SMTP_RAW, // Direct SMTP (requires jakarta.mail on classpath)
    }

    // ── Data models ────────────────────────────────────────

    @kotlinx.serialization.Serializable
    data class InboundEmail(
        val from: String,
        val to: String,
        val subject: String,
        val textBody: String,
        val htmlBody: String? = null,
        val messageId: String? = null,
        val timestamp: Long? = null,
    )

    // ── Incoming ────────────────────────────────────────────

    /**
     * Parse an inbound email webhook payload (SendGrid Inbound Parse format).
     *
     * SendGrid sends multipart/form-data; this expects the JSON representation
     * that a middleware might convert it to, or a pre-parsed JSON body.
     */
    fun parseInboundWebhook(body: String): InboundEmail? =
        try {
            val obj = json.parseToJsonElement(body).jsonObject
            InboundEmail(
                from = obj["from"]?.jsonPrimitive?.content ?: "",
                to = obj["to"]?.jsonPrimitive?.content ?: "",
                subject = obj["subject"]?.jsonPrimitive?.content ?: "",
                textBody = obj["text"]?.jsonPrimitive?.content
                    ?: obj["plain"]?.jsonPrimitive?.content ?: "",
                htmlBody = obj["html"]?.jsonPrimitive?.content,
                messageId = obj["message_id"]?.jsonPrimitive?.content
                    ?: obj["Message-Id"]?.jsonPrimitive?.content,
            )
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse inbound SendGrid email webhook" }
            null
        }

    /**
     * Parse a Mailgun webhook event (routes / inbound).
     */
    fun parseMailgunWebhook(body: String): InboundEmail? =
        try {
            val obj = json.parseToJsonElement(body).jsonObject
            InboundEmail(
                from = obj["sender"]?.jsonPrimitive?.content
                    ?: obj["from"]?.jsonPrimitive?.content ?: "",
                to = obj["recipient"]?.jsonPrimitive?.content ?: "",
                subject = obj["subject"]?.jsonPrimitive?.content ?: "",
                textBody = obj["body-plain"]?.jsonPrimitive?.content ?: "",
                htmlBody = obj["body-html"]?.jsonPrimitive?.content,
                messageId = obj["Message-Id"]?.jsonPrimitive?.content,
                timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull,
            )
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse inbound Mailgun email webhook" }
            null
        }

    // ── Outgoing ────────────────────────────────────────────

    /**
     * Send an email message.
     *
     * @param to       Recipient email address
     * @param text     Plain-text body
     * @param subject  Email subject line
     * @param html     Optional HTML body
     */
    suspend fun sendMessage(
        to: String,
        text: String,
        subject: String = "Message from Prométhé",
        html: String? = null,
    ): Boolean =
        when (apiMode) {
            ApiMode.SENDGRID -> sendViaSendGrid(to, subject, text, html)
            ApiMode.MAILGUN -> sendViaMailgun(to, subject, text, html)
            ApiMode.SMTP_RAW -> sendViaSmtpApi(to, subject, text, html)
        }

    /**
     * Send via SendGrid v3 API.
     * username should be "apikey", password should be the actual API key.
     */
    private suspend fun sendViaSendGrid(
        to: String,
        subject: String,
        text: String,
        html: String?,
    ): Boolean {
        val body = buildJsonObject {
            putJsonArray("personalizations") {
                addJsonObject {
                    putJsonArray("to") {
                        addJsonObject { put("email", to) }
                    }
                }
            }
            putJsonObject("from") {
                put("email", fromAddress)
            }
            put("subject", subject)
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text/plain")
                    put("value", text)
                }
                if (html != null) {
                    addJsonObject {
                        put("type", "text/html")
                        put("value", html)
                    }
                }
            }
        }

        val response = httpClient.post("https://api.sendgrid.com/v3/mail/send") {
            header("Authorization", "Bearer $password") // password = API key
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        // SendGrid returns 202 on success
        return response.status.value in 200..299
    }

    /**
     * Send via Mailgun API.
     * smtpHost should be "https://api.mailgun.net/v3/YOUR_DOMAIN"
     * username = "api", password = API key
     */
    private suspend fun sendViaMailgun(
        to: String,
        subject: String,
        text: String,
        html: String?,
    ): Boolean {
        val response = httpClient.post("$smtpHost/messages") {
            basicAuth("api", password)
            contentType(ContentType.Application.FormUrlEncoded)
            val params = mutableListOf(
                "from" to fromAddress,
                "to" to to,
                "subject" to subject,
                "text" to text,
            )
            html?.let { params.add("html" to it) }
            setBody(params.formUrlEncode())
        }
        return response.status.value in 200..299
    }

    /**
     * Send via a generic SMTP relay HTTP API.
     * This is a fallback for SMTP relays that expose an HTTP endpoint.
     * For true SMTP socket communication, use jakarta.mail.
     */
    private suspend fun sendViaSmtpApi(
        to: String,
        subject: String,
        text: String,
        @Suppress("UNUSED_PARAMETER") html: String?,
    ): Boolean {
        // Generic HTTP-to-SMTP bridge format
        val body = buildJsonObject {
            put("from", fromAddress)
            put("to", to)
            put("subject", subject)
            put("body", text)
            put("host", smtpHost)
            put("port", smtpPort)
        }

        // This assumes an HTTP-to-SMTP bridge is running.
        // For direct SMTP, add jakarta.mail:
        //   val props = Properties()
        //   props["mail.smtp.host"] = smtpHost
        //   props["mail.smtp.port"] = smtpPort.toString()
        //   props["mail.smtp.auth"] = "true"
        //   props["mail.smtp.starttls.enable"] = "true"
        //   val session = Session.getInstance(props, Authenticator(...))
        //   val msg = MimeMessage(session)
        //   msg.setFrom(InternetAddress(fromAddress))
        //   msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        //   msg.subject = subject
        //   msg.setText(text)
        //   Transport.send(msg)

        return try {
            val response = httpClient.post("http://$smtpHost:$smtpPort/send") {
                basicAuth(username, password)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            response.status.value in 200..299
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send email via SMTP API to $smtpHost:$smtpPort" }
            false
        }
    }

    /**
     * Typing action equivalent: not applicable to email.
     * Provided for interface consistency — this is a no-op.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun sendTypingAction(to: String) {
        // Email has no typing indicator concept.
        // No-op for API consistency with other channels.
    }
}
