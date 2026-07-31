package dev.promethe.core

import dev.promethe.db.PrometheDatabaseApi
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*

/**
 * WebhookManager — universal inbound/outbound webhook router.
 *
 * Inbound:  Receives events from external platforms (Telegram, Discord, Slack, etc.)
 *           via POST /api/webhooks/in/{channel} and feeds them to AIAgent.
 *
 * Outbound: Dispatches agent responses to configured webhook URLs
 *           (e.g., post a message back to Slack, send a Telegram reply).
 *
 * Channel-agnostic: Each channel is a named configuration with:
 *   - An optional shared secret for signature verification
 *   - An optional outbound URL for dispatching responses
 *   - Platform-specific payload adapters via extractors
 *
 * Architecture:
 *   External Service → POST /api/webhooks/in/telegram → WebhookManager → AIAgent
 *   AIAgent → WebhookManager.dispatch("telegram", response) → External Service
 */
class WebhookManager(
    private val database: PrometheDatabaseApi,
    private val executionService: AgentExecutionPort,
    private val httpClient: HttpClient,
    private val onTrajectory: (suspend (sessionId: String, trajectory: ConversationTrajectory) -> Unit)? = null,
) {
    private val json = PrometheJson

    /**
     * Platform-specific payload extractors.
     * Each extractor knows how to pull (senderId, senderName, content) from
     * a platform's raw webhook JSON.
     */
    private val payloadExtractors =
        mapOf<String, (JsonObject) -> Triple<String, String, String>>(
            "telegram" to { payload ->
                val message =
                    payload["message"]?.jsonObject
                        ?: payload["edited_message"]?.jsonObject
                        ?: payload["channel_post"]?.jsonObject
                val from = message?.get("from")?.jsonObject
                Triple(
                    from?.get("id")?.jsonPrimitive?.content ?: "",
                    listOfNotNull(
                        from?.get("first_name")?.jsonPrimitive?.content,
                        from?.get("last_name")?.jsonPrimitive?.content,
                    ).joinToString(" ").ifBlank { "unknown" },
                    message?.get("text")?.jsonPrimitive?.content ?: "[non-text message]",
                )
            },
            "discord" to { payload ->
                val author = payload["author"]?.jsonObject
                Triple(
                    author?.get("id")?.jsonPrimitive?.content ?: "",
                    author?.get("username")?.jsonPrimitive?.content ?: "unknown",
                    payload["content"]?.jsonPrimitive?.content ?: "",
                )
            },
            "slack" to { payload ->
                val event = payload["event"]?.jsonObject ?: payload
                Triple(
                    event["user"]?.jsonPrimitive?.content ?: "",
                    event["user_name"]?.jsonPrimitive?.content ?: event["user"]?.jsonPrimitive?.content ?: "unknown",
                    event["text"]?.jsonPrimitive?.content ?: "",
                )
            },
            "signal" to { payload ->
                val envelope = payload["envelope"]?.jsonObject
                val dataMsg = envelope?.get("dataMessage")?.jsonObject
                val source = envelope?.get("sourceNumber")?.jsonPrimitive?.content
                    ?: envelope?.get("source")?.jsonPrimitive?.content ?: ""
                val name = envelope?.get("sourceName")?.jsonPrimitive?.content ?: source
                val text = dataMsg?.get("message")?.jsonPrimitive?.content ?: "[non-text]"
                Triple(source, name, text)
            },
        )

    /**
     * Handle an inbound webhook event.
     * 1. Look up the channel config
     * 2. Extract sender/content via platform extractor (or generic fallback)
     * 3. Feed to AIAgent
     * 4. Optionally dispatch response back via outbound URL
     *
     * @return The agent's response text, or an error message
     */
    suspend fun handleInbound(
        channelName: String,
        rawBody: String,
        headers: Map<String, String>,
    ): String {
        val channel =
            database.getWebhookChannel(channelName)
                ?: return "[ERROR] Unknown webhook channel: $channelName"

        if (!channel.enabled) return "[IGNORED] Channel $channelName is disabled"

        // Verify signature if secret is configured
        if (channel.secret.isNotBlank()) {
            val signature = headers[channel.headerName.ifBlank { "x-signature" }]
            if (signature == null || !verifySignature(rawBody, channel.secret, signature)) {
                return "[ERROR] Invalid webhook signature"
            }
        }

        // Extract message content
        val (senderId, senderName, content) =
            try {
                val payload = json.parseToJsonElement(rawBody).jsonObject
                val extractor = payloadExtractors[channelName.lowercase()]
                extractor?.invoke(payload) ?: extractGeneric(payload)
            } catch (e: Exception) {
                Triple("unknown", "webhook", rawBody.take(500))
            }

        if (content.isBlank()) return "[IGNORED] Empty message content"

        // Create a session for this webhook interaction
        val sessionId = "webhook-$channelName-${System.currentTimeMillis()}"
        val prompt =
            buildString {
                appendLine("[Webhook: $channelName] Message from $senderName (ID: $senderId):")
                appendLine(content)
            }

        val response =
            try {
                var finalResponse = ""
                executionService.execute(
                    AgentExecutionRequest(
                        sessionId = sessionId,
                        text = prompt,
                        origin = AgentExecutionOrigin.WEBHOOK,
                        channelHint = channelName,
                    ),
                ).collect { event ->
                    when (event) {
                        is AgentExecutionEvent.Step -> {
                            onTrajectory?.invoke(sessionId, event.trajectory)
                        }

                        is AgentExecutionEvent.Completed -> {
                            finalResponse = event.response
                        }

                        is AgentExecutionEvent.Failed -> {
                            finalResponse = "[ERROR] ${event.message}"
                        }
                    }
                }
                finalResponse
            } catch (e: Exception) {
                "[ERROR] Agent execution failed: ${e.message}"
            }

        // Dispatch outbound response if channel has an outbound URL
        if (channel.outboundUrl.isNotBlank() && response.isNotBlank()) {
            dispatch(channelName, response)
        }

        return response
    }

    /**
     * Dispatch a message to an outbound webhook channel.
     */
    suspend fun dispatch(
        channelName: String,
        content: String,
    ): WebhookDispatchResult {
        val channel =
            database.getWebhookChannel(channelName)
                ?: return WebhookDispatchResult(false, channelName, 0, "Unknown channel: $channelName")

        if (channel.outboundUrl.isBlank()) {
            return WebhookDispatchResult(false, channelName, 0, "No outbound URL configured")
        }

        return try {
            val body = formatOutboundPayload(channelName, content, channel.payloadTemplate)
            val resp =
                httpClient.post(channel.outboundUrl) {
                    contentType(ContentType.Application.Json)
                    if (channel.secret.isNotBlank()) {
                        header("Authorization", "Bearer ${channel.secret}")
                    }
                    setBody(body)
                }
            WebhookDispatchResult(
                success = resp.status.isSuccess(),
                channel = channelName,
                statusCode = resp.status.value,
            )
        } catch (e: Exception) {
            WebhookDispatchResult(false, channelName, 0, e.message ?: "Dispatch failed")
        }
    }

    /**
     * Format outbound payload for platform-specific format.
     */
    private fun formatOutboundPayload(
        channel: String,
        content: String,
        template: String,
    ): String {
        // If a custom template is provided, use simple mustache-like replacement
        if (template.isNotBlank()) {
            return template.replace("{{content}}", content)
        }

        // Platform-specific defaults
        return when (channel.lowercase()) {
            "telegram" -> {
                buildJsonObject {
                    put("text", content)
                    put("parse_mode", "Markdown")
                }.toString()
            }

            "discord" -> {
                buildJsonObject {
                    put("content", content)
                }.toString()
            }

            "slack" -> {
                buildJsonObject {
                    put("text", content)
                }.toString()
            }

            else -> {
                buildJsonObject {
                    put("message", content)
                    put("source", "promethe")
                    put("timestamp", System.currentTimeMillis())
                }.toString()
            }
        }
    }

    /**
     * Generic payload extraction for unknown platforms.
     */
    private fun extractGeneric(payload: JsonObject): Triple<String, String, String> {
        // Try common field names
        val content =
            payload["message"]?.jsonPrimitive?.content
                ?: payload["text"]?.jsonPrimitive?.content
                ?: payload["content"]?.jsonPrimitive?.content
                ?: payload["body"]?.jsonPrimitive?.content
                ?: payload.toString().take(500)

        val sender =
            payload["sender"]?.jsonPrimitive?.content
                ?: payload["user"]?.jsonPrimitive?.content
                ?: payload["from"]?.jsonPrimitive?.content
                ?: "unknown"

        return Triple(sender, sender, content)
    }

    /**
     * Verify webhook signature (HMAC-based).
     * Simple string comparison for now — extend with proper HMAC-SHA256 later.
     */
    private fun verifySignature(
        body: String,
        secret: String,
        signature: String,
    ): Boolean {
        // Basic: compare provided signature against expected
        // For production: implement HMAC-SHA256(body, secret) == signature
        return signature == secret || signature.contains(secret.take(8))
    }

    data class WebhookDispatchResult(
        val success: Boolean,
        val channel: String,
        val statusCode: Int,
        val error: String? = null,
    )
}
