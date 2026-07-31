package dev.promethe.channels

import dev.promethe.core.PrometheJson
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * SmsChannel — connects Prométhé to SMS via Twilio REST API.
 *
 * Setup:
 *   1. Create a Twilio account at twilio.com
 *   2. Get Account SID and Auth Token from the Console dashboard
 *   3. Buy or configure a phone number for sending
 *   4. Configure webhook URL for incoming messages (TwiML App or phone number config)
 *
 * API Reference: https://www.twilio.com/docs/sms/api/message-resource
 *
 * This class handles:
 *   - Sending SMS messages
 *   - Parsing incoming webhook data (Twilio sends form-encoded POST)
 *   - Message status callbacks
 */
class SmsChannel(
    private val accountSid: String,
    private val authToken: String,
    private val fromNumber: String, // E.164 format, e.g. "+14155552671"
    private val httpClient: HttpClient,
) {
    private val baseUrl = "https://api.twilio.com/2010-04-01/Accounts/$accountSid"
    private val json = PrometheJson

    // ── Data models ────────────────────────────────────────

    /**
     * Represents a parsed incoming SMS from Twilio webhook.
     * Twilio sends form-encoded data; this model is populated after parsing.
     */
    data class IncomingSms(
        val from: String,
        val to: String,
        val body: String,
        val messageSid: String,
        val numMedia: Int = 0,
        val fromCity: String? = null,
        val fromState: String? = null,
        val fromCountry: String? = null,
    )

    // ── Incoming ────────────────────────────────────────────

    /**
     * Parse an incoming Twilio SMS webhook payload.
     *
     * Twilio sends form-encoded data. This method handles both:
     * - Form-encoded (converted to map by the caller): pass as key=value&key=value string
     * - JSON (if a middleware converts it): pass as JSON string
     *
     * @param body Raw webhook body (form-encoded or JSON)
     */
    fun parseUpdate(body: String): IncomingSms? {
        // Try JSON first
        try {
            val obj = json.parseToJsonElement(body).jsonObject
            return IncomingSms(
                from = obj["From"]?.jsonPrimitive?.content ?: "",
                to = obj["To"]?.jsonPrimitive?.content ?: "",
                body = obj["Body"]?.jsonPrimitive?.content ?: "",
                messageSid = obj["MessageSid"]?.jsonPrimitive?.content ?: "",
                numMedia = obj["NumMedia"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                fromCity = obj["FromCity"]?.jsonPrimitive?.content,
                fromState = obj["FromState"]?.jsonPrimitive?.content,
                fromCountry = obj["FromCountry"]?.jsonPrimitive?.content,
            )
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse SMS update as JSON, trying form-encoded" }
            // Not JSON, try form-encoded
        }

        // Parse form-encoded
        try {
            val params = body.split("&").associate { param ->
                val (key, value) = param.split("=", limit = 2)
                java.net.URLDecoder.decode(key, "UTF-8") to java.net.URLDecoder.decode(value, "UTF-8")
            }
            val from = params["From"] ?: return null
            val msgBody = params["Body"] ?: return null
            return IncomingSms(
                from = from,
                to = params["To"] ?: "",
                body = msgBody,
                messageSid = params["MessageSid"] ?: "",
                numMedia = params["NumMedia"]?.toIntOrNull() ?: 0,
                fromCity = params["FromCity"],
                fromState = params["FromState"],
                fromCountry = params["FromCountry"],
            )
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse SMS update as form-encoded data" }
            return null
        }
    }

    // ── Outgoing ────────────────────────────────────────────

    /**
     * Send an SMS message via Twilio.
     *
     * @param to   Recipient phone number in E.164 format (e.g. "+14155551234")
     * @param text Message body (max 1600 characters; auto-split by Twilio if > 160)
     * @return Message SID if successful, null otherwise
     */
    suspend fun sendMessage(
        to: String,
        text: String,
    ): String? {
        val response = httpClient.post("$baseUrl/Messages.json") {
            basicAuth(accountSid, authToken)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    "From" to fromNumber,
                    "To" to to,
                    "Body" to text,
                ).formUrlEncode(),
            )
        }

        return try {
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            if (response.status.value in 200..299) {
                body["sid"]?.jsonPrimitive?.content
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Twilio sendMessage response" }
            null
        }
    }

    /**
     * Send an MMS message with a media URL.
     */
    suspend fun sendMediaMessage(
        to: String,
        text: String,
        mediaUrl: String,
    ): String? {
        val response = httpClient.post("$baseUrl/Messages.json") {
            basicAuth(accountSid, authToken)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    "From" to fromNumber,
                    "To" to to,
                    "Body" to text,
                    "MediaUrl" to mediaUrl,
                ).formUrlEncode(),
            )
        }

        return try {
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            if (response.status.value in 200..299) {
                body["sid"]?.jsonPrimitive?.content
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Twilio sendMediaMessage response" }
            null
        }
    }

    /**
     * SMS has no typing indicator concept.
     * Provided for interface consistency — this is a no-op.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun sendTypingAction(to: String) {
        // SMS protocol has no typing indicator.
        // No-op for API consistency with other channels.
    }

    // ── Utility ─────────────────────────────────────────────

    /**
     * Check the delivery status of a message by SID.
     */
    suspend fun getMessageStatus(messageSid: String): String? {
        val response = httpClient.get("$baseUrl/Messages/$messageSid.json") {
            basicAuth(accountSid, authToken)
        }
        return try {
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["status"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            logger.debug(e) { "Failed to get Twilio message status for messageSid=$messageSid" }
            null
        }
    }

    /**
     * Generate a TwiML response for incoming webhooks.
     * Useful when Twilio expects a TwiML response to an incoming SMS.
     */
    fun twimlResponse(message: String): String = """<?xml version="1.0" encoding="UTF-8"?><Response><Message>$message</Message></Response>"""
}
