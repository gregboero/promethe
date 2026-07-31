package dev.promethe.channels

import dev.promethe.core.PrometheJson
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * TeamsChannel — connects Prométhé to Microsoft Teams via Bot Framework REST API.
 *
 * Setup:
 *   1. Register a bot in Azure Bot Service (or Azure Bot Framework portal)
 *   2. Get Microsoft App ID and App Secret
 *   3. Obtain OAuth token from login.microsoftonline.com
 *   4. Set messaging endpoint for incoming activities
 *
 * API Reference: https://learn.microsoft.com/en-us/azure/bot-service/rest-api/bot-framework-rest-connector-api-reference
 *
 * This class handles:
 *   - Parsing incoming Bot Framework activities (messages, conversationUpdate, etc.)
 *   - Replying to activities
 *   - Sending proactive messages
 *   - Sending typing indicators
 */
class TeamsChannel(
    private val appId: String,
    private val appSecret: String,
    private val httpClient: HttpClient,
) {
    private val json = PrometheJson
    private val loginUrl = "https://login.microsoftonline.com/botframework.com/oauth2/v2.0/token"

    // ── Data models ────────────────────────────────────────

    @kotlinx.serialization.Serializable
    data class Activity(
        val type: String = "message",
        val id: String? = null,
        val timestamp: String? = null,
        val serviceUrl: String? = null,
        val channelId: String? = null,
        val from: ChannelAccount? = null,
        val conversation: ConversationAccount? = null,
        val recipient: ChannelAccount? = null,
        val text: String? = null,
        val replyToId: String? = null,
    )

    @kotlinx.serialization.Serializable
    data class ChannelAccount(
        val id: String,
        val name: String? = null,
    )

    @kotlinx.serialization.Serializable
    data class ConversationAccount(
        val id: String,
        val tenantId: String? = null,
        val isGroup: Boolean? = null,
    )

    // ── Incoming ────────────────────────────────────────────

    /**
     * Parse an incoming Bot Framework activity from the webhook body.
     */
    fun parseActivity(body: String): Activity? =
        try {
            json.decodeFromString<Activity>(body)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse Teams Bot Framework activity" }
            null
        }

    /**
     * Extract the message text and sender info from an activity.
     * Returns null if the activity is not a message type.
     */
    fun extractMessage(activity: Activity): Triple<String, String, String>? {
        if (activity.type != "message" || activity.text == null) return null
        val senderId = activity.from?.id ?: return null
        val conversationId = activity.conversation?.id ?: return null
        return Triple(activity.text, senderId, conversationId)
    }

    // ── Authentication ──────────────────────────────────────

    /**
     * Obtain a Bot Framework OAuth token.
     * Tokens are valid for ~3600 seconds; callers should cache and refresh.
     */
    suspend fun obtainToken(): String? {
        val response = httpClient.post(loginUrl) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                listOf(
                    "grant_type" to "client_credentials",
                    "client_id" to appId,
                    "client_secret" to appSecret,
                    "scope" to "https://api.botframework.com/.default",
                ).formUrlEncode(),
            )
        }
        return try {
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            body["access_token"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            logger.warn(e) { "Failed to obtain Teams Bot Framework OAuth token" }
            null
        }
    }

    // ── Outgoing ────────────────────────────────────────────

    /**
     * Reply to an incoming activity.
     *
     * @param serviceUrl    The serviceUrl from the incoming activity
     * @param conversationId  The conversation ID
     * @param activityId    The activity ID to reply to
     * @param text          Reply text
     * @param token         Bot Framework OAuth token (from obtainToken)
     */
    suspend fun sendMessage(
        serviceUrl: String,
        conversationId: String,
        activityId: String,
        text: String,
        token: String,
    ): Boolean {
        // Bot Framework reply URL: POST {serviceUrl}/v3/conversations/{conversationId}/activities/{activityId}
        val url = "${serviceUrl.trimEnd('/')}/v3/conversations/$conversationId/activities/$activityId"

        val body = buildJsonObject {
            put("type", "message")
            put("text", text)
            put("replyToId", activityId)
        }

        val response = httpClient.post(url) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return response.status.value in 200..299
    }

    /**
     * Send a proactive message to a conversation (no reply context).
     */
    suspend fun sendProactiveMessage(
        serviceUrl: String,
        conversationId: String,
        text: String,
        token: String,
    ): Boolean {
        val url = "${serviceUrl.trimEnd('/')}/v3/conversations/$conversationId/activities"

        val body = buildJsonObject {
            put("type", "message")
            put("text", text)
        }

        val response = httpClient.post(url) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return response.status.value in 200..299
    }

    /**
     * Send a typing indicator to the conversation.
     */
    suspend fun sendTypingAction(
        serviceUrl: String,
        conversationId: String,
        token: String,
    ) {
        val url = "${serviceUrl.trimEnd('/')}/v3/conversations/$conversationId/activities"

        val body = buildJsonObject {
            put("type", "typing")
        }

        httpClient.post(url) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
    }

    /**
     * Convenience: parse activity, reply, and send typing in one flow.
     */
    suspend fun handleAndReply(
        incomingBody: String,
        replyGenerator: suspend (text: String, senderId: String) -> String,
    ): Boolean {
        val activity = parseActivity(incomingBody) ?: return false
        val (text, senderId, conversationId) = extractMessage(activity) ?: return false
        val serviceUrl = activity.serviceUrl ?: return false
        val activityId = activity.id ?: return false

        val token = obtainToken() ?: return false

        sendTypingAction(serviceUrl, conversationId, token)

        val reply = replyGenerator(text, senderId)
        return sendMessage(serviceUrl, conversationId, activityId, reply, token)
    }
}
