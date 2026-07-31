package dev.promethe.gateway.channels

import dev.promethe.api.SignalSendRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

/**
 * SignalApiClient — HTTP client for signal-cli-rest-api.
 *
 * Wraps the signal-cli-rest-api REST endpoints:
 *   - POST /v2/send       → send a message
 *   - GET  /v1/receive/{number} → receive pending messages
 *   - GET  /v1/about      → check service health
 *
 * @param httpClient Ktor HttpClient (shared with the gateway)
 * @param baseUrl    signal-cli-rest-api base URL (default: http://localhost:8080)
 */
class SignalApiClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = "http://localhost:8080",
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Send a text message to one or more recipients.
     *
     * @param sender     The registered Signal number (e.g., "+33612345678")
     * @param recipients List of recipient phone numbers
     * @param message    Message text
     * @return The raw API response body
     */
    suspend fun sendMessage(
        sender: String,
        recipients: List<String>,
        message: String,
    ): Result<String> =
        try {
            val request = SignalSendRequest(
                message = message,
                number = sender,
                recipients = recipients,
            )
            val response = httpClient.post("$baseUrl/v2/send") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(SignalSendRequest.serializer(), request))
            }
            val body = response.bodyAsText()
            if (response.status.value in 200..299) {
                Result.success(body)
            } else {
                Result.failure(Exception("Signal API error ${response.status.value}: $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Receive pending messages for a registered number.
     *
     * @param number The registered Signal number
     * @return Raw JSON response with message list
     */
    suspend fun receiveMessages(number: String): Result<String> =
        try {
            val response = httpClient.get("$baseUrl/v1/receive/$number")
            val body = response.bodyAsText()
            if (response.status.value in 200..299) {
                Result.success(body)
            } else {
                Result.failure(Exception("Signal receive error ${response.status.value}: $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Check signal-cli-rest-api health/version.
     */
    suspend fun healthCheck(): Result<String> =
        try {
            val response = httpClient.get("$baseUrl/v1/about")
            Result.success(response.bodyAsText())
        } catch (e: Exception) {
            Result.failure(e)
        }
}
