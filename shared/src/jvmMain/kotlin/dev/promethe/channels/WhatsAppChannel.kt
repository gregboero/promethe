package dev.promethe.channels

import dev.promethe.core.PrometheJson
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * WhatsAppChannel — connects Prométhé to WhatsApp via Cloud API (Meta).
 *
 * Setup:
 *   1. Create a Meta Business app at developers.facebook.com
 *   2. Add WhatsApp product, get phone number ID and access token
 *   3. Configure webhook for incoming messages (verify token challenge)
 *   4. Subscribe to "messages" webhook field
 *
 * API Reference: https://developers.facebook.com/docs/whatsapp/cloud-api
 *
 * This class handles:
 *   - Sending text messages
 *   - Sending template messages (required for initiating conversations)
 *   - Marking messages as read
 *   - Parsing incoming webhook notifications
 */
class WhatsAppChannel(
    private val phoneNumberId: String,
    private val accessToken: String,
    private val httpClient: HttpClient,
) {
    private val baseUrl = "https://graph.facebook.com/v18.0/$phoneNumberId"
    private val json = PrometheJson

    // ── Data models ────────────────────────────────────────

    @kotlinx.serialization.Serializable
    data class WebhookPayload(
        val entry: List<WebhookEntry> = emptyList(),
    )

    @kotlinx.serialization.Serializable
    data class WebhookEntry(
        val id: String? = null,
        val changes: List<WebhookChange> = emptyList(),
    )

    @kotlinx.serialization.Serializable
    data class WebhookChange(
        val field: String? = null,
        val value: WebhookValue? = null,
    )

    @kotlinx.serialization.Serializable
    data class WebhookValue(
        val messaging_product: String? = null,
        val metadata: WebhookMetadata? = null,
        val contacts: List<WebhookContact>? = null,
        val messages: List<IncomingMessage>? = null,
        val statuses: List<MessageStatus>? = null,
    )

    @kotlinx.serialization.Serializable
    data class WebhookMetadata(
        val display_phone_number: String? = null,
        val phone_number_id: String? = null,
    )

    @kotlinx.serialization.Serializable
    data class WebhookContact(
        val profile: ContactProfile? = null,
        val wa_id: String? = null,
    )

    @kotlinx.serialization.Serializable
    data class ContactProfile(
        val name: String? = null,
    )

    @kotlinx.serialization.Serializable
    data class IncomingMessage(
        val from: String,
        val id: String,
        val timestamp: String? = null,
        val type: String = "text",
        val text: MessageText? = null,
    )

    @kotlinx.serialization.Serializable
    data class MessageText(
        val body: String,
    )

    @kotlinx.serialization.Serializable
    data class MessageStatus(
        val id: String? = null,
        val status: String? = null,
        val timestamp: String? = null,
        val recipient_id: String? = null,
    )

    // ── Incoming ────────────────────────────────────────────

    /**
     * Parse a WhatsApp Cloud API webhook notification.
     * Returns a list of (senderPhoneNumber, messageText, messageId) triples.
     */
    fun parseUpdate(body: String): List<Triple<String, String, String>> {
        val results = mutableListOf<Triple<String, String, String>>()
        try {
            val payload = json.decodeFromString<WebhookPayload>(body)
            for (entry in payload.entry) {
                for (change in entry.changes) {
                    if (change.field != "messages") continue
                    val messages = change.value?.messages ?: continue
                    for (msg in messages) {
                        if (msg.type == "text" && msg.text != null) {
                            results.add(Triple(msg.from, msg.text.body, msg.id))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse WhatsApp Cloud API webhook payload" }
            // Malformed payload
        }
        return results
    }

    /**
     * Verify a webhook subscription challenge.
     * Returns the challenge string if verification succeeds, null otherwise.
     *
     * @param mode        hub.mode query parameter (should be "subscribe")
     * @param token       hub.verify_token query parameter
     * @param challenge   hub.challenge query parameter
     * @param verifyToken Your configured verify token
     */
    fun verifyWebhook(
        mode: String?,
        token: String?,
        challenge: String?,
        verifyToken: String,
    ): String? = if (mode == "subscribe" && token == verifyToken) challenge else null

    // ── Outgoing ────────────────────────────────────────────

    /**
     * Send a text message to a WhatsApp user.
     *
     * @param recipient  Phone number in international format (e.g. "14155552671")
     * @param text       Message text
     * @param previewUrl Whether to show URL previews in the message
     */
    suspend fun sendMessage(
        recipient: String,
        text: String,
        previewUrl: Boolean = false,
    ): Boolean {
        val body = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            put("to", recipient)
            put("type", "text")
            putJsonObject("text") {
                put("preview_url", previewUrl)
                put("body", text)
            }
        }

        val response = httpClient.post("$baseUrl/messages") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return response.status.value in 200..299
    }

    /**
     * Send a template message (required for initiating business conversations).
     *
     * @param recipient     Phone number
     * @param templateName  Approved template name
     * @param languageCode  Template language code (e.g. "en_US")
     * @param parameters    Optional template parameter values
     */
    suspend fun sendTemplate(
        recipient: String,
        templateName: String,
        languageCode: String = "en_US",
        parameters: List<String> = emptyList(),
    ): Boolean {
        val body = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("to", recipient)
            put("type", "template")
            putJsonObject("template") {
                put("name", templateName)
                putJsonObject("language") {
                    put("code", languageCode)
                }
                if (parameters.isNotEmpty()) {
                    putJsonArray("components") {
                        addJsonObject {
                            put("type", "body")
                            putJsonArray("parameters") {
                                parameters.forEach { param ->
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", param)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val response = httpClient.post("$baseUrl/messages") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return response.status.value in 200..299
    }

    /**
     * Mark a message as read (sends blue ticks to the user).
     * This serves as a "typing" equivalent — indicating engagement.
     */
    suspend fun sendTypingAction(messageId: String) {
        httpClient.post("$baseUrl/messages") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("messaging_product", "whatsapp")
                    put("status", "read")
                    put("message_id", messageId)
                }.toString(),
            )
        }
    }

    /**
     * Send a reaction to a message.
     */
    suspend fun sendReaction(
        recipient: String,
        messageId: String,
        emoji: String,
    ): Boolean {
        val body = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            put("to", recipient)
            put("type", "reaction")
            putJsonObject("reaction") {
                put("message_id", messageId)
                put("emoji", emoji)
            }
        }

        val response = httpClient.post("$baseUrl/messages") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return response.status.value in 200..299
    }
}
