package dev.promethe.channels

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * SignalChannel — Signal messenger via signal-cli REST API.
 *
 * Requires a running signal-cli-rest-api instance:
 *   https://github.com/bbernhard/signal-cli-rest-api
 *
 * Constructor: baseUrl (e.g. http://localhost:8080), registeredNumber
 */
class SignalChannel(
    private val baseUrl: String,
    private val registeredNumber: String,
    private val httpClient: HttpClient,
) {
    private val apiBase = baseUrl.trimEnd('/')

    data class IncomingMessage(
        val source: String,
        val text: String,
        val timestamp: Long,
        val groupId: String? = null,
    )

    fun parseUpdate(body: String): IncomingMessage? {
        return try {
            val json = Json.parseToJsonElement(body).jsonObject
            val envelope = json["envelope"]?.jsonObject ?: return null
            val dataMessage = envelope["dataMessage"]?.jsonObject ?: return null
            IncomingMessage(
                source = envelope["source"]?.jsonPrimitive?.content ?: return null,
                text = dataMessage["message"]?.jsonPrimitive?.content ?: return null,
                timestamp = dataMessage["timestamp"]?.jsonPrimitive?.long ?: 0L,
                groupId = dataMessage["groupInfo"]?.jsonObject?.get("groupId")?.jsonPrimitive?.contentOrNull,
            )
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse Signal webhook update" }
            null
        }
    }

    suspend fun sendMessage(
        recipient: String,
        text: String,
    ): Boolean =
        try {
            val response = httpClient.post("$apiBase/v2/send") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("message", text)
                        put("number", registeredNumber)
                        putJsonArray("recipients") { add(recipient) }
                    }.toString(),
                )
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send Signal message to recipient $recipient" }
            false
        }

    suspend fun sendGroupMessage(
        groupId: String,
        text: String,
    ): Boolean =
        try {
            val response = httpClient.post("$apiBase/v2/send") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("message", text)
                        put("number", registeredNumber)
                        putJsonArray("recipients") { add(groupId) }
                    }.toString(),
                )
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send Signal group message to groupId=$groupId" }
            false
        }

    fun sendTypingAction(recipient: String) {
        // Signal does not have a rich typing indicator API via signal-cli REST
    }
}
