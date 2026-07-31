package dev.promethe.channels

import dev.promethe.core.PrometheJson
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}
private val lenientJson = PrometheJson

/**
 * DiscordChannel — connects Prométhé to Discord via Bot API.
 *
 * Setup:
 *   1. Create application at discord.com/developers
 *   2. Add bot, get token
 *   3. Set up Gateway or webhook interaction endpoint
 *
 * This class handles REST API operations (sending messages, reactions).
 * For real-time events, a WebSocket Gateway listener would be needed.
 */
class DiscordChannel(
    private val botToken: String,
    private val httpClient: HttpClient,
) {
    private val baseUrl = "https://discord.com/api/v10"

    /**
     * Send a message to a Discord channel.
     */
    suspend fun sendMessage(
        channelId: String,
        content: String,
        replyTo: String? = null,
    ): Boolean {
        val response =
            httpClient.post("$baseUrl/channels/$channelId/messages") {
                header("Authorization", "Bot $botToken")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("content", content)
                        replyTo?.let {
                            putJsonObject("message_reference") {
                                put("message_id", it)
                            }
                        }
                    }.toString(),
                )
            }
        return response.status.value in 200..299
    }

    /**
     * Send a rich embed message.
     */
    suspend fun sendEmbed(
        channelId: String,
        title: String,
        description: String,
        color: Int = 0x5865F2, // Discord blurple
    ): Boolean {
        val response =
            httpClient.post("$baseUrl/channels/$channelId/messages") {
                header("Authorization", "Bot $botToken")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        putJsonArray("embeds") {
                            addJsonObject {
                                put("title", title)
                                put("description", description)
                                put("color", color)
                            }
                        }
                    }.toString(),
                )
            }
        return response.status.value in 200..299
    }

    /**
     * Add a reaction to a message.
     */
    suspend fun addReaction(
        channelId: String,
        messageId: String,
        emoji: String,
    ): Boolean {
        val response =
            httpClient.put("$baseUrl/channels/$channelId/messages/$messageId/reactions/$emoji/@me") {
                header("Authorization", "Bot $botToken")
            }
        return response.status.value in 200..299
    }

    /**
     * Parse a Discord interaction (slash command or message component).
     */
    fun parseInteraction(body: String): JsonObject? =
        try {
            lenientJson.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse Discord interaction payload" }
            null
        }
}
