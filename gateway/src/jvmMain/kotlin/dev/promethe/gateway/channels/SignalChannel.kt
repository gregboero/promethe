package dev.promethe.gateway.channels

import dev.promethe.api.SignalChannelConfig
import dev.promethe.api.SignalWebhookPayload
import dev.promethe.core.ToolCallOrigin
import dev.promethe.gateway.A2AInternalClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * SignalChannel — bidirectional Signal messaging channel.
 *
 * Two modes of operation:
 *   1. **Webhook mode**: signal-cli-rest-api POSTs inbound messages
 *      to `/api/webhooks/in/signal`. The WebhookManager routes them
 *      through the `signal` payload extractor.
 *
 *   2. **Poll mode**: When webhook isn't configured, this channel
 *      actively polls signal-cli-rest-api for new messages and
 *      processes them through the agent.
 *
 * Outbound: After the agent responds, the response is sent back
 * via signal-cli-rest-api to the original sender.
 */
class SignalChannel(
    private val config: SignalChannelConfig,
    private val a2aClient: A2AInternalClient,
    httpClient: HttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val apiClient = SignalApiClient(httpClient, config.apiUrl)
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Start the polling loop (for non-webhook setups).
     * Polls every 5 seconds for new messages.
     */
    fun startPolling() {
        if (!config.enabled) {
            logger.warn { "Signal channel disabled, not starting poll loop" }
            return
        }
        logger.info { "Starting Signal poll mode (${config.apiUrl}, sender=${config.senderNumber})" }
        scope.launch {
            while (isActive) {
                try {
                    pollAndProcess()
                } catch (e: Exception) {
                    logger.error(e) { "Signal poll error" }
                }
                delay(5000)
            }
        }
    }

    /**
     * Handle an inbound message (from webhook or poll).
     * Feeds the message to the agent and sends the response back.
     */
    suspend fun handleInbound(message: SignalWebhookPayload): String {
        val envelope = message.envelope ?: return "[IGNORED] No envelope"
        val content = envelope.dataMessage?.message
            ?: envelope.syncMessage?.sentMessage?.message
            ?: return "[IGNORED] No text content"

        val sender = envelope.sourceNumber.ifBlank { envelope.source }
        val senderName = envelope.sourceName.ifBlank { sender }

        // Access control
        if (config.allowedNumbers.isNotEmpty() && sender !in config.allowedNumbers) {
            logger.warn { "Rejected message from unauthorized number: $sender" }
            return "[REJECTED] Unauthorized sender"
        }

        val sessionId = "signal-$sender-${System.currentTimeMillis()}"
        val prompt = buildString {
            appendLine("[Signal] Message from $senderName ($sender):")
            appendLine(content)
        }

        // Execute agent via A2A internal bridge
        val response = try {
            val result = a2aClient.execute(sessionId, prompt, channelHint = "signal", origin = ToolCallOrigin.CHANNEL)
            result.ifBlank { "Message reçu." }
        } catch (e: Exception) {
            "[ERROR] Agent failed: ${e.message}"
        }

        // Send response back
        if (config.senderNumber.isNotBlank()) {
            val sendResult = apiClient.sendMessage(
                sender = config.senderNumber,
                recipients = listOf(sender),
                message = response.take(4096),
            )
            sendResult.onFailure { e ->
                logger.error(e) { "Failed to send Signal reply to $sender" }
            }
        }

        return response
    }

    /**
     * Poll signal-cli-rest-api for pending messages and process them.
     */
    private suspend fun pollAndProcess() {
        if (config.senderNumber.isBlank()) return

        val result = apiClient.receiveMessages(config.senderNumber)
        result.onSuccess { body ->
            if (body.isBlank() || body == "[]") return@onSuccess
            try {
                val messages = json.decodeFromString<List<SignalWebhookPayload>>(body)
                for (msg in messages) {
                    handleInbound(msg)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse Signal messages" }
            }
        }
    }

    /**
     * Send a proactive message (not in response to an inbound).
     */
    suspend fun sendMessage(
        recipient: String,
        text: String,
    ): Result<String> {
        if (config.senderNumber.isBlank()) {
            return Result.failure(Exception("Signal sender number not configured"))
        }
        return apiClient.sendMessage(
            sender = config.senderNumber,
            recipients = listOf(recipient),
            message = text,
        )
    }
}
