package dev.promethe.channels

import dev.promethe.core.PrometheJson
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * TelegramChannel — connects Prométhé to Telegram via Bot API.
 *
 * Setup:
 *   1. Create a bot with @BotFather → get token
 *   2. Set webhook or use long polling
 *   3. Messages are routed to AIAgent
 *
 * This class handles:
 *   - Sending messages (text, markdown, photos)
 *   - Parsing incoming webhook updates
 *   - Keyboard/button support
 */
class TelegramChannel(
    private val botToken: String,
    private val httpClient: HttpClient,
) {
    private val baseUrl = "https://api.telegram.org/bot$botToken"
    private val json = PrometheJson

    // ── Incoming ────────────────────────────────────────────

    @kotlinx.serialization.Serializable
    data class TelegramUpdate(
        val update_id: Long,
        val message: TelegramMessage? = null,
    )

    @kotlinx.serialization.Serializable
    data class TelegramMessage(
        val message_id: Long,
        val chat: TelegramChat,
        val text: String? = null,
        val from: TelegramUser? = null,
    )

    @kotlinx.serialization.Serializable
    data class TelegramChat(
        val id: Long,
        val type: String = "private",
    )

    @kotlinx.serialization.Serializable
    data class TelegramUser(
        val id: Long,
        val first_name: String = "",
        val username: String? = null,
    )

    /**
     * Parse a webhook update from Telegram.
     */
    fun parseUpdate(body: String): TelegramUpdate? =
        try {
            json.decodeFromString<TelegramUpdate>(body)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse Telegram webhook update" }
            null
        }

    // ── Outgoing ────────────────────────────────────────────

    /**
     * Send a text message to a chat.
     */
    suspend fun sendMessage(
        chatId: Long,
        text: String,
        parseMode: String = "Markdown",
        replyToMessageId: Long? = null,
    ): Boolean {
        val response =
            httpClient.post("$baseUrl/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("chat_id", chatId)
                        put("text", text)
                        put("parse_mode", parseMode)
                        replyToMessageId?.let { put("reply_to_message_id", it) }
                    }.toString(),
                )
            }
        return response.status == HttpStatusCode.OK
    }

    /**
     * Send a message with an inline keyboard.
     */
    suspend fun sendMessageWithButtons(
        chatId: Long,
        text: String,
        buttons: List<List<Pair<String, String>>>, // rows of (label, callbackData)
    ): Boolean {
        val keyboard =
            buildJsonObject {
                putJsonArray("inline_keyboard") {
                    buttons.forEach { row ->
                        addJsonArray {
                            row.forEach { (label, data) ->
                                addJsonObject {
                                    put("text", label)
                                    put("callback_data", data)
                                }
                            }
                        }
                    }
                }
            }

        val response =
            httpClient.post("$baseUrl/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("chat_id", chatId)
                        put("text", text)
                        put("parse_mode", "Markdown")
                        put("reply_markup", keyboard)
                    }.toString(),
                )
            }
        return response.status == HttpStatusCode.OK
    }

    /**
     * Send a "typing..." indicator.
     */
    suspend fun sendTypingAction(chatId: Long) {
        httpClient.post("$baseUrl/sendChatAction") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("chat_id", chatId)
                    put("action", "typing")
                }.toString(),
            )
        }
    }

    /**
     * Set a webhook URL for incoming updates.
     */
    suspend fun setWebhook(url: String): Boolean {
        val response =
            httpClient.post("$baseUrl/setWebhook") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("url", url)
                    }.toString(),
                )
            }
        return response.status == HttpStatusCode.OK
    }

    /**
     * Get bot info (for verification).
     */
    suspend fun getMe(): String {
        val response = httpClient.get("$baseUrl/getMe")
        return response.bodyAsText()
    }
}
