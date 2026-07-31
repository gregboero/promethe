package dev.promethe.channels

import dev.promethe.core.PrometheJson
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * MattermostChannel — connects Prométhé to Mattermost via REST API v4 + outgoing webhooks.
 *
 * Setup:
 *   1. Create a bot account or personal access token in Mattermost
 *   2. Configure an outgoing webhook to receive messages
 *   3. Grant the bot permission to post in target channels
 *
 * API Reference: https://api.mattermost.com/
 *
 * This class handles:
 *   - Sending messages to channels/DMs
 *   - Parsing incoming webhook payloads
 *   - Posting with attachments (rich formatting)
 *   - Sending typing indicators via WebSocket command (via REST workaround)
 */
class MattermostChannel(
    private val baseUrl: String, // e.g. "https://mattermost.example.com"
    private val accessToken: String, // Personal access token or bot token
    private val httpClient: HttpClient,
) {
    private val apiUrl = "${baseUrl.trimEnd('/')}/api/v4"
    private val json = PrometheJson

    // ── Data models ────────────────────────────────────────

    @kotlinx.serialization.Serializable
    data class WebhookPayload(
        val token: String? = null,
        val team_id: String? = null,
        val team_domain: String? = null,
        val channel_id: String? = null,
        val channel_name: String? = null,
        val user_id: String? = null,
        val user_name: String? = null,
        val post_id: String? = null,
        val text: String? = null,
        val trigger_word: String? = null,
    )

    @kotlinx.serialization.Serializable
    data class Post(
        val id: String? = null,
        val channel_id: String,
        val user_id: String? = null,
        val message: String,
        val create_at: Long? = null,
        val root_id: String? = null,
    )

    // ── Incoming ────────────────────────────────────────────

    /**
     * Parse an outgoing webhook payload from Mattermost.
     * Mattermost outgoing webhooks send form-encoded or JSON data.
     */
    fun parseWebhook(body: String): WebhookPayload? =
        try {
            json.decodeFromString<WebhookPayload>(body)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse Mattermost outgoing webhook payload" }
            null
        }

    /**
     * Parse a post event from Mattermost's WebSocket event payload.
     * The "post" field in WebSocket events is a JSON-encoded string.
     */
    fun parsePostEvent(body: String): Post? =
        try {
            val root = json.parseToJsonElement(body).jsonObject
            val event = root["event"]?.jsonPrimitive?.content
            if (event == "posted") {
                val data = root["data"]?.jsonObject
                val postStr = data?.get("post")?.jsonPrimitive?.content
                postStr?.let { json.decodeFromString<Post>(it) }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse Mattermost WebSocket post event" }
            null
        }

    // ── Outgoing ────────────────────────────────────────────

    /**
     * Send a message to a Mattermost channel.
     *
     * @param channelId  The channel ID to post to
     * @param text       Message text (supports Markdown)
     * @param rootId     Optional: reply to a thread by providing the root post ID
     */
    suspend fun sendMessage(
        channelId: String,
        text: String,
        rootId: String? = null,
    ): Boolean {
        val body = buildJsonObject {
            put("channel_id", channelId)
            put("message", text)
            rootId?.let { put("root_id", it) }
        }

        val response = httpClient.post("$apiUrl/posts") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return response.status.value in 200..299
    }

    /**
     * Send a message with rich attachments (Mattermost message attachments).
     */
    suspend fun sendMessageWithAttachment(
        channelId: String,
        text: String,
        attachmentTitle: String,
        attachmentText: String,
        color: String = "#0076B4",
    ): Boolean {
        val body = buildJsonObject {
            put("channel_id", channelId)
            put("message", text)
            putJsonObject("props") {
                putJsonArray("attachments") {
                    addJsonObject {
                        put("title", attachmentTitle)
                        put("text", attachmentText)
                        put("color", color)
                    }
                }
            }
        }

        val response = httpClient.post("$apiUrl/posts") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return response.status.value in 200..299
    }

    /**
     * Send a typing indicator.
     * Uses the REST API userTyping action.
     * Note: Mattermost typing is primarily WebSocket-based; this uses the REST equivalent.
     */
    suspend fun sendTypingAction(channelId: String) {
        httpClient.post("$apiUrl/users/me/typing") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("channel_id", channelId)
                }.toString(),
            )
        }
    }

    // ── Utility ─────────────────────────────────────────────

    /**
     * Get channel information by ID.
     */
    suspend fun getChannel(channelId: String): JsonObject? {
        val response = httpClient.get("$apiUrl/channels/$channelId") {
            header("Authorization", "Bearer $accessToken")
        }
        return try {
            json.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get Mattermost channel info for channelId=$channelId" }
            null
        }
    }

    /**
     * Create a direct message channel with a user.
     * Returns the channel ID if successful.
     */
    suspend fun createDirectChannel(
        myUserId: String,
        otherUserId: String,
    ): String? {
        val response = httpClient.post("$apiUrl/channels/direct") {
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonArray {
                    add(myUserId)
                    add(otherUserId)
                }.toString(),
            )
        }
        return try {
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["id"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            logger.warn(e) { "Failed to create Mattermost direct channel" }
            null
        }
    }

    /**
     * Get the authenticated user's info (for bot self-identification).
     */
    suspend fun getMe(): JsonObject? {
        val response = httpClient.get("$apiUrl/users/me") {
            header("Authorization", "Bearer $accessToken")
        }
        return try {
            json.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get Mattermost authenticated user info" }
            null
        }
    }
}
