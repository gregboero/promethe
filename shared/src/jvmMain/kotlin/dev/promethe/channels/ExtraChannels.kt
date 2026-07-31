package dev.promethe.channels

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * LineChannel — LINE Messaging API.
 * https://developers.line.biz/en/docs/messaging-api/
 *
 * Constructor: channelAccessToken
 */
class LineChannel(
    private val channelAccessToken: String,
    private val httpClient: HttpClient,
) {
    data class IncomingMessage(
        val replyToken: String,
        val userId: String,
        val text: String,
        val messageId: String,
    )

    fun parseUpdate(body: String): List<IncomingMessage> {
        return try {
            val json = Json.parseToJsonElement(body).jsonObject
            val events = json["events"]?.jsonArray ?: return emptyList()
            events.mapNotNull { event ->
                val obj = event.jsonObject
                if (obj["type"]?.jsonPrimitive?.content != "message") return@mapNotNull null
                val message = obj["message"]?.jsonObject ?: return@mapNotNull null
                if (message["type"]?.jsonPrimitive?.content != "text") return@mapNotNull null
                IncomingMessage(
                    replyToken = obj["replyToken"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    userId = obj["source"]?.jsonObject?.get("userId")?.jsonPrimitive?.content ?: "",
                    text = message["text"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    messageId = message["id"]?.jsonPrimitive?.content ?: "",
                )
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse LINE webhook update" }
            emptyList()
        }
    }

    suspend fun replyMessage(
        replyToken: String,
        text: String,
    ): Boolean =
        try {
            val response = httpClient.post("https://api.line.me/v2/bot/message/reply") {
                header("Authorization", "Bearer $channelAccessToken")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("replyToken", replyToken)
                        putJsonArray("messages") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                },
                            )
                        }
                    }.toString(),
                )
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send LINE reply message" }
            false
        }

    suspend fun pushMessage(
        userId: String,
        text: String,
    ): Boolean =
        try {
            val response = httpClient.post("https://api.line.me/v2/bot/message/push") {
                header("Authorization", "Bearer $channelAccessToken")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("to", userId)
                        putJsonArray("messages") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                },
                            )
                        }
                    }.toString(),
                )
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send LINE push message to user $userId" }
            false
        }

    fun sendTypingAction(userId: String) { /* LINE does not expose typing indicator via API */ }
}

/**
 * QqChannel — QQ Bot via Official QQ Bot API.
 * https://bot.q.qq.com/wiki/develop/api-v2/
 *
 * Constructor: appId, appSecret, token
 */
class QqChannel(
    private val appId: String,
    private val token: String,
    private val httpClient: HttpClient,
) {
    private val apiBase = "https://api.sgroup.qq.com"

    data class IncomingMessage(
        val channelId: String,
        val messageId: String,
        val content: String,
        val authorId: String,
    )

    fun parseUpdate(body: String): IncomingMessage? {
        return try {
            val json = Json.parseToJsonElement(body).jsonObject
            val d = json["d"]?.jsonObject ?: return null
            IncomingMessage(
                channelId = d["channel_id"]?.jsonPrimitive?.content ?: return null,
                messageId = d["id"]?.jsonPrimitive?.content ?: "",
                content = d["content"]?.jsonPrimitive?.content ?: return null,
                authorId = d["author"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: "",
            )
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse QQ bot webhook update" }
            null
        }
    }

    suspend fun sendMessage(
        channelId: String,
        text: String,
        msgId: String? = null,
    ): Boolean =
        try {
            val response = httpClient.post("$apiBase/channels/$channelId/messages") {
                header("Authorization", "Bot $appId.$token")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("content", text)
                        if (msgId != null) put("msg_id", msgId)
                    }.toString(),
                )
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send QQ bot message to channel $channelId" }
            false
        }

    fun sendTypingAction(channelId: String) { /* QQ Bot API does not support typing indicator */ }
}

/**
 * WeixinChannel — WeChat Official Account (公众号) via Message API.
 * https://developers.weixin.qq.com/doc/offiaccount/
 *
 * Note: Personal WeChat requires native client; this is for Official Accounts.
 * Constructor: appId, appSecret
 */
class WeixinChannel(
    private val appId: String,
    private val appSecret: String,
    private val httpClient: HttpClient,
) {
    private var accessToken: String = ""
    private var tokenExpiresAt: Long = 0L

    data class IncomingMessage(
        val fromUser: String,
        val toUser: String,
        val content: String,
        val msgId: String,
        val createTime: Long,
    )

    fun parseUpdate(xmlBody: String): IncomingMessage? {
        return try {
            // Simple XML parsing for WeChat message format
            fun extract(tag: String): String =
                Regex("<$tag><!\\[CDATA\\[(.*?)]]></$tag>|<$tag>(.*?)</$tag>")
                    .find(xmlBody)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: ""
            val msgType = extract("MsgType")
            if (msgType != "text") return null
            IncomingMessage(
                fromUser = extract("FromUserName"),
                toUser = extract("ToUserName"),
                content = extract("Content"),
                msgId = extract("MsgId"),
                createTime = extract("CreateTime").toLongOrNull() ?: 0L,
            )
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse WeChat XML message" }
            null
        }
    }

    fun buildXmlReply(
        toUser: String,
        fromUser: String,
        text: String,
    ): String {
        val timestamp = System.currentTimeMillis() / 1000
        return """
            <xml>
            <ToUserName><![CDATA[$toUser]]></ToUserName>
            <FromUserName><![CDATA[$fromUser]]></FromUserName>
            <CreateTime>$timestamp</CreateTime>
            <MsgType><![CDATA[text]]></MsgType>
            <Content><![CDATA[$text]]></Content>
            </xml>
            """.trimIndent()
    }

    suspend fun refreshAccessToken() {
        if (System.currentTimeMillis() < tokenExpiresAt - 300_000) return
        try {
            val response = httpClient.get("https://api.weixin.qq.com/cgi-bin/token") {
                parameter("grant_type", "client_credential")
                parameter("appid", appId)
                parameter("secret", appSecret)
            }
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            accessToken = json["access_token"]?.jsonPrimitive?.content ?: ""
            val expiresIn = json["expires_in"]?.jsonPrimitive?.long ?: 7200
            tokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000
        } catch (e: Exception) {
            logger.warn(e) { "Failed to refresh WeChat access token" }
        }
    }

    suspend fun sendCustomMessage(
        toUser: String,
        text: String,
    ): Boolean {
        refreshAccessToken()
        return try {
            val response = httpClient.post("https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token=$accessToken") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("touser", toUser)
                        put("msgtype", "text")
                        putJsonObject("text") { put("content", text) }
                    }.toString(),
                )
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send WeChat custom message to user $toUser" }
            false
        }
    }

    fun sendTypingAction(toUser: String) { /* WeChat does not support typing indicators */ }
}

/**
 * BlueBubblesChannel — iMessage via BlueBubbles server.
 * https://bluebubbles.app/
 *
 * Constructor: serverUrl, password
 */
class BlueBubblesChannel(
    private val serverUrl: String,
    private val password: String,
    private val httpClient: HttpClient,
) {
    private val apiBase = serverUrl.trimEnd('/')

    data class IncomingMessage(
        val guid: String,
        val chatGuid: String,
        val text: String,
        val senderHandle: String,
        val dateCreated: Long,
    )

    fun parseUpdate(body: String): IncomingMessage? {
        return try {
            val json = Json.parseToJsonElement(body).jsonObject
            IncomingMessage(
                guid = json["guid"]?.jsonPrimitive?.content ?: return null,
                chatGuid = json["chats"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("guid")?.jsonPrimitive?.content ?: "",
                text = json["text"]?.jsonPrimitive?.content ?: return null,
                senderHandle = json["handle"]?.jsonObject?.get("address")?.jsonPrimitive?.content ?: "",
                dateCreated = json["dateCreated"]?.jsonPrimitive?.long ?: 0L,
            )
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse BlueBubbles webhook update" }
            null
        }
    }

    suspend fun sendMessage(
        chatGuid: String,
        text: String,
    ): Boolean =
        try {
            val response = httpClient.post("$apiBase/api/v1/message/text") {
                parameter("password", password)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("chatGuid", chatGuid)
                        put("message", text)
                        put("method", "apple-script")
                    }.toString(),
                )
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send BlueBubbles message to chat $chatGuid" }
            false
        }

    fun sendTypingAction(chatGuid: String) { /* BlueBubbles typing not reliably supported */ }
}

/**
 * NtfyChannel — ntfy.sh push notification channel.
 * https://ntfy.sh/docs/
 *
 * Constructor: serverUrl (default: https://ntfy.sh), topic
 */
class NtfyChannel(
    private val serverUrl: String = "https://ntfy.sh",
    private val defaultTopic: String,
    private val httpClient: HttpClient,
) {
    private val apiBase = serverUrl.trimEnd('/')

    data class IncomingMessage(
        val id: String,
        val topic: String,
        val message: String,
        val title: String?,
        val time: Long,
    )

    fun parseUpdate(body: String): IncomingMessage? {
        return try {
            val json = Json.parseToJsonElement(body).jsonObject
            if (json["event"]?.jsonPrimitive?.content != "message") return null
            IncomingMessage(
                id = json["id"]?.jsonPrimitive?.content ?: return null,
                topic = json["topic"]?.jsonPrimitive?.content ?: "",
                message = json["message"]?.jsonPrimitive?.content ?: return null,
                title = json["title"]?.jsonPrimitive?.contentOrNull,
                time = json["time"]?.jsonPrimitive?.long ?: 0L,
            )
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse ntfy webhook update" }
            null
        }
    }

    suspend fun sendMessage(
        text: String,
        topic: String? = null,
        title: String? = null,
    ): Boolean {
        val targetTopic = topic ?: defaultTopic
        return try {
            val response = httpClient.post("$apiBase/$targetTopic") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("message", text)
                        if (title != null) put("title", title)
                    }.toString(),
                )
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send ntfy notification to topic ${topic ?: defaultTopic}" }
            false
        }
    }

    fun sendTypingAction(topic: String) { /* ntfy is push-only, no typing indicator */ }
}

/**
 * HomeAssistantChannel — Home Assistant conversation channel.
 * Uses the HA conversation API to receive and respond to voice/text commands.
 *
 * Constructor: haUrl, haToken
 */
class HomeAssistantChannel(
    private val haUrl: String,
    private val haToken: String,
    private val httpClient: HttpClient,
) {
    private val apiBase = haUrl.trimEnd('/')

    data class IncomingMessage(
        val text: String,
        val conversationId: String?,
        val language: String,
    )

    fun parseUpdate(body: String): IncomingMessage? {
        return try {
            val json = Json.parseToJsonElement(body).jsonObject
            IncomingMessage(
                text = json["text"]?.jsonPrimitive?.content ?: return null,
                conversationId = json["conversation_id"]?.jsonPrimitive?.contentOrNull,
                language = json["language"]?.jsonPrimitive?.content ?: "en",
            )
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse Home Assistant webhook update" }
            null
        }
    }

    suspend fun sendMessage(
        text: String,
        conversationId: String? = null,
    ): String =
        try {
            val response = httpClient.post("$apiBase/api/conversation/process") {
                header("Authorization", "Bearer $haToken")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("text", text)
                        if (conversationId != null) put("conversation_id", conversationId)
                        put("language", "en")
                    }.toString(),
                )
            }
            response.bodyAsText()
        } catch (e: Exception) {
            """{"error": "${e.message}"}"""
        }

    fun sendTypingAction(conversationId: String) { /* HA does not support typing indicators */ }
}
