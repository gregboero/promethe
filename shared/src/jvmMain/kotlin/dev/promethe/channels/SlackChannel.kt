package dev.promethe.channels

import dev.promethe.core.PrometheJson
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val lenientJson = PrometheJson

/**
 * SlackChannel — connects Prométhé to Slack via Web API + Events API.
 *
 * Setup:
 *   1. Create Slack app at api.slack.com
 *   2. Add Bot Token Scopes: chat:write, channels:read
 *   3. Install to workspace, get xoxb- token
 *   4. Subscribe to Events API or use Socket Mode
 */
class SlackChannel(
    private val botToken: String, // xoxb-...
    private val httpClient: HttpClient,
) {
    private val baseUrl = "https://slack.com/api"

    /**
     * Send a message to a Slack channel.
     */
    suspend fun sendMessage(
        channel: String,
        text: String,
        threadTs: String? = null,
    ): Boolean {
        val response =
            httpClient.post("$baseUrl/chat.postMessage") {
                header("Authorization", "Bearer $botToken")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("channel", channel)
                        put("text", text)
                        threadTs?.let { put("thread_ts", it) }
                    }.toString(),
                )
            }
        val body = lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["ok"]?.jsonPrimitive?.boolean == true
    }

    /**
     * Send a message with Block Kit blocks (rich formatting).
     */
    suspend fun sendBlocks(
        channel: String,
        blocks: JsonArray,
        text: String = "", // Fallback text for notifications
    ): Boolean {
        val response =
            httpClient.post("$baseUrl/chat.postMessage") {
                header("Authorization", "Bearer $botToken")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("channel", channel)
                        put("text", text)
                        put("blocks", blocks)
                    }.toString(),
                )
            }
        val body = lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["ok"]?.jsonPrimitive?.boolean == true
    }

    /**
     * Add an emoji reaction to a message.
     */
    suspend fun addReaction(
        channel: String,
        timestamp: String,
        emoji: String,
    ): Boolean {
        val response =
            httpClient.post("$baseUrl/reactions.add") {
                header("Authorization", "Bearer $botToken")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("channel", channel)
                        put("timestamp", timestamp)
                        put("name", emoji) // Without colons, e.g. "thumbsup"
                    }.toString(),
                )
            }
        val body = lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["ok"]?.jsonPrimitive?.boolean == true
    }

    /**
     * Parse a Slack Events API payload.
     * Handles url_verification challenge automatically.
     */
    fun parseEvent(body: String): Pair<String, JsonObject?> {
        val json = lenientJson.parseToJsonElement(body).jsonObject
        val type = json["type"]?.jsonPrimitive?.content ?: "unknown"

        return when (type) {
            "url_verification" -> {
                val challenge = json["challenge"]?.jsonPrimitive?.content ?: ""
                "challenge" to buildJsonObject { put("challenge", challenge) }
            }

            "event_callback" -> {
                val event = json["event"]?.jsonObject
                "event" to event
            }

            else -> {
                type to json
            }
        }
    }
}
