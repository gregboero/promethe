package dev.promethe.channels

import dev.promethe.core.PrometheJson
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * FeishuChannel — connects Prométhé to Feishu (Lark) via Bot API.
 *
 * Setup:
 *   1. Create an app at open.feishu.cn (or open.larksuite.com for Lark)
 *   2. Get App ID and App Secret from the app credentials page
 *   3. Enable "Bot" capability and subscribe to im.message.receive_v1 event
 *   4. Configure Event Subscription URL for incoming messages
 *
 * API Reference: https://open.feishu.cn/document/server-docs/im-v1/message/create
 *
 * This class handles:
 *   - Obtaining tenant access tokens
 *   - Sending text, interactive card, and rich text messages
 *   - Parsing incoming event callbacks
 *   - URL verification challenge responses
 */
class FeishuChannel(
    private val appId: String,
    private val appSecret: String,
    private val httpClient: HttpClient,
    private val isLark: Boolean = false, // true for Lark (international), false for Feishu (China)
) {
    private val baseUrl = if (isLark) "https://open.larksuite.com/open-apis" else "https://open.feishu.cn/open-apis"
    private val json = PrometheJson

    // Cached tenant access token
    private var tenantAccessToken: String? = null
    private var tokenExpiresAt: Long = 0

    // ── Data models ────────────────────────────────────────

    @kotlinx.serialization.Serializable
    data class EventCallback(
        val schema: String? = null, // "2.0" for v2 events
        val header: EventHeader? = null,
        val event: JsonObject? = null,
        // v1 fields
        val type: String? = null,
        val challenge: String? = null,
        val token: String? = null,
    )

    @kotlinx.serialization.Serializable
    data class EventHeader(
        val event_id: String? = null,
        val event_type: String? = null,
        val create_time: String? = null,
        val token: String? = null,
        val app_id: String? = null,
        val tenant_key: String? = null,
    )

    // ── Authentication ──────────────────────────────────────

    /**
     * Obtain a tenant access token for API calls.
     * Tokens are valid for 2 hours; this method caches and refreshes automatically.
     */
    suspend fun getTenantAccessToken(): String? {
        val now = System.currentTimeMillis()
        if (tenantAccessToken != null && now < tokenExpiresAt) {
            return tenantAccessToken
        }

        val response = httpClient.post("$baseUrl/auth/v3/tenant_access_token/internal") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("app_id", appId)
                    put("app_secret", appSecret)
                }.toString(),
            )
        }

        return try {
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val code = body["code"]?.jsonPrimitive?.int
            if (code == 0) {
                tenantAccessToken = body["tenant_access_token"]?.jsonPrimitive?.content
                val expire = body["expire"]?.jsonPrimitive?.int ?: 7200
                tokenExpiresAt = now + (expire - 300) * 1000L // Refresh 5 min early
                tenantAccessToken
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to obtain Feishu tenant access token" }
            null
        }
    }

    // ── Incoming ────────────────────────────────────────────

    /**
     * Parse an incoming Feishu event callback.
     *
     * Handles:
     * - URL verification challenge (returns challenge JSON to respond with)
     * - v2 event callbacks (im.message.receive_v1 etc.)
     */
    fun parseEvent(body: String): Pair<String, JsonObject?> {
        return try {
            val callback = json.decodeFromString<EventCallback>(body)

            // URL Verification challenge
            if (callback.type == "url_verification" || callback.challenge != null) {
                val challenge = callback.challenge ?: ""
                return "challenge" to buildJsonObject { put("challenge", challenge) }
            }

            // v2 event
            if (callback.schema == "2.0" && callback.header != null) {
                val eventType = callback.header.event_type ?: "unknown"
                return eventType to callback.event
            }

            // Fallback
            "unknown" to json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse Feishu event callback" }
            "error" to null
        }
    }

    /**
     * Extract text message from a im.message.receive_v1 event.
     * Returns (senderId, chatId, text) or null.
     */
    fun extractTextMessage(event: JsonObject): Triple<String, String, String>? {
        return try {
            val sender = event["sender"]?.jsonObject
            val senderId = sender?.get("sender_id")?.jsonObject?.get("open_id")?.jsonPrimitive?.content ?: return null
            val message = event["message"]?.jsonObject ?: return null
            val chatId = message["chat_id"]?.jsonPrimitive?.content ?: return null
            val msgType = message["message_type"]?.jsonPrimitive?.content
            if (msgType != "text") return null
            val content = message["content"]?.jsonPrimitive?.content ?: return null
            val textObj = json.parseToJsonElement(content).jsonObject
            val text = textObj["text"]?.jsonPrimitive?.content ?: return null
            Triple(senderId, chatId, text)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to extract Feishu text message from event" }
            null
        }
    }

    // ── Outgoing ────────────────────────────────────────────

    /**
     * Send a text message.
     *
     * @param receiveId    Recipient ID (open_id, user_id, email, or chat_id)
     * @param text         Message text
     * @param receiveIdType  Type of receive_id: "open_id", "user_id", "email", or "chat_id"
     */
    suspend fun sendMessage(
        receiveId: String,
        text: String,
        receiveIdType: String = "chat_id",
    ): Boolean {
        val token = getTenantAccessToken() ?: return false

        val content = buildJsonObject {
            put("text", text)
        }.toString()

        val body = buildJsonObject {
            put("receive_id", receiveId)
            put("msg_type", "text")
            put("content", content)
        }

        val response = httpClient.post("$baseUrl/im/v1/messages") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            parameter("receive_id_type", receiveIdType)
            setBody(body.toString())
        }

        return try {
            val result = json.parseToJsonElement(response.bodyAsText()).jsonObject
            result["code"]?.jsonPrimitive?.int == 0
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Feishu sendMessage response for receiveId=$receiveId" }
            false
        }
    }

    /**
     * Send an interactive card message (Feishu message cards).
     */
    suspend fun sendCard(
        receiveId: String,
        cardJson: JsonObject,
        receiveIdType: String = "chat_id",
    ): Boolean {
        val token = getTenantAccessToken() ?: return false

        val body = buildJsonObject {
            put("receive_id", receiveId)
            put("msg_type", "interactive")
            put("content", cardJson.toString())
        }

        val response = httpClient.post("$baseUrl/im/v1/messages") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            parameter("receive_id_type", receiveIdType)
            setBody(body.toString())
        }

        return try {
            val result = json.parseToJsonElement(response.bodyAsText()).jsonObject
            result["code"]?.jsonPrimitive?.int == 0
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Feishu sendCard response for receiveId=$receiveId" }
            false
        }
    }

    /**
     * Reply to a specific message.
     */
    suspend fun replyMessage(
        messageId: String,
        text: String,
    ): Boolean {
        val token = getTenantAccessToken() ?: return false

        val content = buildJsonObject {
            put("text", text)
        }.toString()

        val body = buildJsonObject {
            put("msg_type", "text")
            put("content", content)
        }

        val response = httpClient.post("$baseUrl/im/v1/messages/$messageId/reply") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

        return try {
            val result = json.parseToJsonElement(response.bodyAsText()).jsonObject
            result["code"]?.jsonPrimitive?.int == 0
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Feishu replyMessage response for messageId=$messageId" }
            false
        }
    }

    /**
     * Typing action: Feishu does not expose a typing indicator API for bots.
     * No-op for interface consistency.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun sendTypingAction(chatId: String) {
        // Feishu/Lark bots do not have a typing indicator API.
    }
}
