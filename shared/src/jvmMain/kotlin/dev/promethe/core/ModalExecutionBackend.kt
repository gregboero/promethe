package dev.promethe.core

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * ModalExecutionBackend — execute commands on Modal serverless GPU/CPU functions.
 *
 * Modal provides on-demand compute with pre-warmed containers.
 * This backend calls a deployed Modal function via its REST webhook.
 *
 * Setup:
 * 1. Deploy a Modal function that accepts {"command": "..."} and returns {"output": "..."}
 * 2. Set MODAL_WEBHOOK_URL and MODAL_TOKEN_ID/MODAL_TOKEN_SECRET
 *
 * Configure via environment:
 * - MODAL_WEBHOOK_URL: The webhook URL of the deployed Modal function
 * - MODAL_TOKEN_ID: Modal token ID for auth
 * - MODAL_TOKEN_SECRET: Modal token secret for auth
 */
class ModalExecutionBackend(
    private val httpClient: HttpClient,
    private val webhookUrl: String,
    private val tokenId: String = "",
    private val tokenSecret: String = "",
    private val timeoutMs: Long = 120_000, // Modal cold starts can take time
    private val maxOutputBytes: Int = 50_000,
) {
    @Serializable
    data class ModalRequest(
        val command: String,
    )

    /**
     * Execute a command via Modal webhook.
     */
    suspend fun execute(command: String): String =
        withContext(ioDispatcher) {
            try {
                val response =
                    httpClient.post(webhookUrl) {
                        contentType(ContentType.Application.Json)
                        if (tokenId.isNotBlank() && tokenSecret.isNotBlank()) {
                            basicAuth(tokenId, tokenSecret)
                        }
                        setBody(Json.encodeToString(ModalRequest.serializer(), ModalRequest(command)))
                    }

                if (!response.status.isSuccess()) {
                    return@withContext "[Modal] HTTP ${response.status.value}: ${response.bodyAsText().take(500)}"
                }

                val body = response.bodyAsText()

                // Try to parse as JSON with "output" field
                try {
                    val json = Json.parseToJsonElement(body).jsonObject
                    val output = json["output"]?.jsonPrimitive?.content ?: body
                    val exitCode = json["exit_code"]?.jsonPrimitive?.intOrNull ?: 0

                    val result =
                        if (output.length > maxOutputBytes) {
                            output.take(maxOutputBytes) + "\n[Modal] Output truncated"
                        } else {
                            output
                        }

                    if (exitCode != 0) {
                        "$result\n[Modal] Exit code: $exitCode"
                    } else {
                        result
                    }
                } catch (e: Exception) {
                    // Not JSON — return raw body
                    logger.debug(e) { "Modal response is not JSON, returning raw body" }
                    if (body.length > maxOutputBytes) {
                        body.take(maxOutputBytes) + "\n[Modal] Output truncated"
                    } else {
                        body
                    }
                }
            } catch (e: Exception) {
                "[Modal] Error: ${e.message}"
            }
        }

    /**
     * Test connectivity by running `echo ok`.
     */
    suspend fun testConnection(): Boolean =
        withContext(ioDispatcher) {
            try {
                val result = execute("echo ok")
                result.trim() == "ok"
            } catch (e: Exception) {
                logger.warn(e) { "Modal connection test failed for webhook: $webhookUrl" }
                false
            }
        }
}
