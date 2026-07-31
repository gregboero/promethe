package dev.promethe.channels

import dev.promethe.core.PrometheJson
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * DingTalkChannel — connects Prométhé to DingTalk via Robot Webhook + Event Callback.
 *
 * Setup:
 *   1. Create a Custom Robot in a DingTalk group
 *   2. Choose "Custom Keywords" or "Sign" (HMAC-SHA256) security mode
 *   3. For receiving messages: configure an Event Subscription callback URL
 *
 * API Reference: https://open.dingtalk.com/document/orgapp/custom-robots-send-group-messages
 *
 * This class handles:
 *   - Sending text, markdown, and action card messages
 *   - HMAC-SHA256 signature generation for signed webhooks
 *   - Parsing incoming event callback payloads
 */
class DingTalkChannel(
    private val webhookUrl: String, // Full webhook URL including access_token
    private val secret: String = "", // HMAC-SHA256 secret (empty if using keyword mode)
    private val httpClient: HttpClient,
) {
    private val json = PrometheJson

    // ── Data models ────────────────────────────────────────

    @kotlinx.serialization.Serializable
    data class EventCallback(
        val msgtype: String? = null,
        val text: TextContent? = null,
        val msgId: String? = null,
        val createAt: Long? = null,
        val conversationType: String? = null, // "1" = single, "2" = group
        val senderId: String? = null,
        val senderNick: String? = null,
        val conversationId: String? = null,
    )

    @kotlinx.serialization.Serializable
    data class TextContent(
        val content: String? = null,
    )

    // ── Signing ─────────────────────────────────────────────

    /**
     * Generate the signed webhook URL.
     * DingTalk requires timestamp + HMAC-SHA256 signature in query params.
     */
    private fun signedUrl(): String {
        if (secret.isEmpty()) return webhookUrl

        val timestamp = System.currentTimeMillis()
        val stringToSign = "$timestamp\n$secret"

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signData = mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8))
        val sign = java.util.Base64.getEncoder().encodeToString(signData)
        val encodedSign = java.net.URLEncoder.encode(sign, "UTF-8")

        val separator = if (webhookUrl.contains("?")) "&" else "?"
        return "${webhookUrl}${separator}timestamp=$timestamp&sign=$encodedSign"
    }

    // ── Incoming ────────────────────────────────────────────

    /**
     * Parse an incoming DingTalk event callback payload.
     */
    fun parseEvent(body: String): EventCallback? =
        try {
            json.decodeFromString<EventCallback>(body)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse DingTalk event callback" }
            null
        }

    /**
     * Parse the raw JSON and extract the text content + sender.
     * Returns (senderNick, text, conversationId) or null.
     */
    fun extractMessage(body: String): Triple<String, String, String>? {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val text = obj["text"]?.jsonObject?.get("content")?.jsonPrimitive?.content?.trim()
                ?: return null
            val sender = obj["senderNick"]?.jsonPrimitive?.content ?: "unknown"
            val convId = obj["conversationId"]?.jsonPrimitive?.content ?: ""
            Triple(sender, text, convId)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to extract DingTalk message from payload" }
            null
        }
    }

    // ── Outgoing ────────────────────────────────────────────

    /**
     * Send a text message to the DingTalk group.
     *
     * @param text    Message text
     * @param atAll   Whether to @everyone in the group
     * @param atMobiles  List of phone numbers to @mention
     */
    suspend fun sendMessage(
        text: String,
        atAll: Boolean = false,
        atMobiles: List<String> = emptyList(),
    ): Boolean {
        val body = buildJsonObject {
            put("msgtype", "text")
            putJsonObject("text") {
                put("content", text)
            }
            putJsonObject("at") {
                put("isAtAll", atAll)
                if (atMobiles.isNotEmpty()) {
                    putJsonArray("atMobiles") {
                        atMobiles.forEach { add(it) }
                    }
                }
            }
        }

        val response = httpClient.post(signedUrl()) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

        return try {
            val result = json.parseToJsonElement(response.bodyAsText()).jsonObject
            result["errcode"]?.jsonPrimitive?.int == 0
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse DingTalk sendMessage response" }
            false
        }
    }

    /**
     * Send a Markdown-formatted message.
     */
    suspend fun sendMarkdown(
        title: String,
        markdownText: String,
        atAll: Boolean = false,
    ): Boolean {
        val body = buildJsonObject {
            put("msgtype", "markdown")
            putJsonObject("markdown") {
                put("title", title)
                put("text", markdownText)
            }
            putJsonObject("at") {
                put("isAtAll", atAll)
            }
        }

        val response = httpClient.post(signedUrl()) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

        return try {
            val result = json.parseToJsonElement(response.bodyAsText()).jsonObject
            result["errcode"]?.jsonPrimitive?.int == 0
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse DingTalk sendMarkdown response" }
            false
        }
    }

    /**
     * Send an ActionCard message (single button or multi-button).
     */
    suspend fun sendActionCard(
        title: String,
        text: String,
        singleTitle: String,
        singleUrl: String,
    ): Boolean {
        val body = buildJsonObject {
            put("msgtype", "actionCard")
            putJsonObject("actionCard") {
                put("title", title)
                put("text", text)
                put("singleTitle", singleTitle)
                put("singleURL", singleUrl)
            }
        }

        val response = httpClient.post(signedUrl()) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

        return try {
            val result = json.parseToJsonElement(response.bodyAsText()).jsonObject
            result["errcode"]?.jsonPrimitive?.int == 0
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse DingTalk sendActionCard response" }
            false
        }
    }

    /**
     * Typing action: DingTalk robot webhooks don't support typing indicators.
     * No-op for interface consistency.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun sendTypingAction(conversationId: String) {
        // DingTalk robot webhooks have no typing indicator concept.
    }
}
