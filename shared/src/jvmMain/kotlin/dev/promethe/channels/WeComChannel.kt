package dev.promethe.channels

import dev.promethe.core.PrometheJson
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * WeComChannel — connects Prométhé to WeCom (WeChat Work / 企业微信) via Group Robot Webhook + Server API.
 *
 * Two modes of operation:
 *
 * **Mode 1 - Group Robot Webhook** (simple, outgoing only):
 *   - Uses webhook URL: https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key={key}
 *   - No authentication needed beyond the key in the URL
 *   - Only supports sending to a specific group
 *
 * **Mode 2 - Server API** (full bidirectional):
 *   - Uses corpId + secret to obtain access_token
 *   - Can send to any user/department/tag via application messages
 *   - Can receive messages via callback URL
 *
 * API Reference: https://developer.work.weixin.qq.com/document/path/91770
 *
 * This class handles:
 *   - Sending text, markdown, and card messages via webhook
 *   - Sending application messages via Server API
 *   - Access token management
 *   - Parsing incoming callback payloads
 */
class WeComChannel(
    private val corpId: String = "", // Corp ID for Server API mode
    private val corpSecret: String = "", // Secret for Server API mode
    private val webhookKey: String = "", // Webhook key for robot mode
    private val agentId: Int = 0, // Agent ID for application messages
    private val httpClient: HttpClient,
) {
    private val json = PrometheJson
    private val webhookUrl = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=$webhookKey"
    private val apiBase = "https://qyapi.weixin.qq.com/cgi-bin"

    // Cached access token
    private var accessToken: String? = null
    private var tokenExpiresAt: Long = 0

    // ── Data models ────────────────────────────────────────

    @kotlinx.serialization.Serializable
    data class CallbackMessage(
        val MsgType: String? = null,
        val Content: String? = null,
        val FromUserName: String? = null,
        val CreateTime: Long? = null,
        val MsgId: String? = null,
        val AgentID: String? = null,
    )

    // ── Authentication (Server API mode) ────────────────────

    /**
     * Obtain an access token for WeCom Server API.
     * Valid for 7200 seconds; cached with early refresh.
     */
    suspend fun getAccessToken(): String? {
        val now = System.currentTimeMillis()
        if (accessToken != null && now < tokenExpiresAt) {
            return accessToken
        }

        val response = httpClient.get("$apiBase/gettoken") {
            parameter("corpid", corpId)
            parameter("corpsecret", corpSecret)
        }

        return try {
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val errcode = body["errcode"]?.jsonPrimitive?.int
            if (errcode == 0) {
                accessToken = body["access_token"]?.jsonPrimitive?.content
                val expiresIn = body["expires_in"]?.jsonPrimitive?.int ?: 7200
                tokenExpiresAt = now + (expiresIn - 300) * 1000L
                accessToken
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to obtain WeCom access token" }
            null
        }
    }

    // ── Incoming ────────────────────────────────────────────

    /**
     * Parse an incoming WeCom callback payload.
     *
     * Note: WeCom callbacks are XML-encrypted. In production, you need to:
     * 1. Decrypt the XML using AES (EncodingAESKey from WeCom console)
     * 2. Parse the decrypted XML
     *
     * This method handles a pre-decrypted JSON representation.
     * For full XML decryption, see WeCom's WXBizMsgCrypt library.
     */
    fun parseEvent(body: String): CallbackMessage? =
        try {
            json.decodeFromString<CallbackMessage>(body)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse WeCom event callback" }
            null
        }

    /**
     * Parse raw JSON event and extract text + sender.
     * Returns (userId, text) or null.
     */
    fun extractMessage(body: String): Pair<String, String>? {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val userId = obj["FromUserName"]?.jsonPrimitive?.content ?: return null
            val content = obj["Content"]?.jsonPrimitive?.content ?: return null
            userId to content
        } catch (e: Exception) {
            logger.debug(e) { "Failed to extract WeCom message from payload" }
            null
        }
    }

    /**
     * Verify a callback URL.
     * WeCom sends GET with msg_signature, timestamp, nonce, echostr.
     * Returns the decrypted echostr to confirm the URL.
     *
     * Note: Full verification requires AES decryption of echostr.
     * This is a placeholder — use WXBizMsgCrypt for production.
     */
    fun verifyCallback(echostr: String): String = echostr

    // ── Outgoing: Webhook mode ──────────────────────────────

    /**
     * Send a text message via group robot webhook.
     *
     * @param text            Message text
     * @param mentionedList   User IDs to @mention (use "@all" for everyone)
     * @param mentionedMobileList  Phone numbers to @mention
     */
    suspend fun sendMessage(
        text: String,
        mentionedList: List<String> = emptyList(),
        mentionedMobileList: List<String> = emptyList(),
    ): Boolean {
        val body = buildJsonObject {
            put("msgtype", "text")
            putJsonObject("text") {
                put("content", text)
                if (mentionedList.isNotEmpty()) {
                    putJsonArray("mentioned_list") {
                        mentionedList.forEach { add(it) }
                    }
                }
                if (mentionedMobileList.isNotEmpty()) {
                    putJsonArray("mentioned_mobile_list") {
                        mentionedMobileList.forEach { add(it) }
                    }
                }
            }
        }

        val response = httpClient.post(webhookUrl) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

        return try {
            val result = json.parseToJsonElement(response.bodyAsText()).jsonObject
            result["errcode"]?.jsonPrimitive?.int == 0
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse WeCom sendMessage webhook response" }
            false
        }
    }

    /**
     * Send a Markdown message via webhook.
     */
    suspend fun sendMarkdown(markdownText: String): Boolean {
        val body = buildJsonObject {
            put("msgtype", "markdown")
            putJsonObject("markdown") {
                put("content", markdownText)
            }
        }

        val response = httpClient.post(webhookUrl) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

        return try {
            val result = json.parseToJsonElement(response.bodyAsText()).jsonObject
            result["errcode"]?.jsonPrimitive?.int == 0
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse WeCom sendMarkdown webhook response" }
            false
        }
    }

    /**
     * Send a news card message via webhook.
     */
    suspend fun sendNewsCard(
        title: String,
        description: String,
        url: String,
        picUrl: String? = null,
    ): Boolean {
        val body = buildJsonObject {
            put("msgtype", "news")
            putJsonObject("news") {
                putJsonArray("articles") {
                    addJsonObject {
                        put("title", title)
                        put("description", description)
                        put("url", url)
                        picUrl?.let { put("picurl", it) }
                    }
                }
            }
        }

        val response = httpClient.post(webhookUrl) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

        return try {
            val result = json.parseToJsonElement(response.bodyAsText()).jsonObject
            result["errcode"]?.jsonPrimitive?.int == 0
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse WeCom sendNewsCard webhook response" }
            false
        }
    }

    // ── Outgoing: Server API mode ───────────────────────────

    /**
     * Send a text message to specific users via WeCom Server API (application message).
     *
     * @param toUser  Pipe-delimited user IDs, e.g. "user1|user2" or "@all"
     * @param text    Message text
     */
    suspend fun sendAppMessage(
        toUser: String,
        text: String,
    ): Boolean {
        val token = getAccessToken() ?: return false

        val body = buildJsonObject {
            put("touser", toUser)
            put("msgtype", "text")
            put("agentid", agentId)
            putJsonObject("text") {
                put("content", text)
            }
        }

        val response = httpClient.post("$apiBase/message/send") {
            parameter("access_token", token)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

        return try {
            val result = json.parseToJsonElement(response.bodyAsText()).jsonObject
            result["errcode"]?.jsonPrimitive?.int == 0
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse WeCom sendAppMessage response" }
            false
        }
    }

    /**
     * Typing action: WeCom does not support typing indicators.
     * No-op for interface consistency.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun sendTypingAction(target: String) {
        // WeCom has no typing indicator API.
    }
}
