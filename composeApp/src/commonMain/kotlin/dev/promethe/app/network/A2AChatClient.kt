package dev.promethe.app.network

import ai.koog.a2a.client.A2AClient
import ai.koog.a2a.client.ExplicitAgentCardResolver
import ai.koog.a2a.model.AgentCapabilities
import ai.koog.a2a.model.AgentCard
import ai.koog.a2a.model.AgentSkill
import ai.koog.a2a.model.Event
import ai.koog.a2a.model.Message
import ai.koog.a2a.model.MessageSendParams
import ai.koog.a2a.model.Role
import ai.koog.a2a.model.Task
import ai.koog.a2a.model.TaskArtifactUpdateEvent
import ai.koog.a2a.model.TaskState
import ai.koog.a2a.model.TaskStatusUpdateEvent
import ai.koog.a2a.model.TextPart
import ai.koog.a2a.transport.Request
import ai.koog.a2a.transport.client.jsonrpc.http.HttpJSONRPCClientTransport
import dev.promethe.api.ChatEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * A2A Chat Client backed by the official Koog A2AClient.
 *
 * Replaces the manual SSE/JSON-RPC parsing with the native Koog SDK,
 * handling proper A2A protocol events (Message, Task, TaskStatusUpdate, etc.)
 * across all KMP targets (JVM, WasmJs, iOS, Android).
 */
class A2AChatClient(
    private val baseUrl: String,
    private val apiKey: String = "",
    private val csrfToken: String? = null,
) {
    private val httpClient = createPlatformHttpClient {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(
                kotlinx.serialization.json.Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                },
            )
        }
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 3600_000L
            socketTimeoutMillis = 3600_000L
        }
        if (apiKey.isNotBlank()) {
            defaultRequest {
                header("Authorization", "Bearer $apiKey")
            }
        }
        if (!csrfToken.isNullOrBlank()) {
            defaultRequest {
                header("X-CSRF-Token", csrfToken)
            }
        }
    }

    private val transport = HttpJSONRPCClientTransport("$baseUrl/agents/a2a", httpClient)

    /** Provide a minimal AgentCard so Koog doesn't need to fetch /.well-known/agent.json */
    private val agentCard = AgentCard(
        name = "Prométhé",
        description = "Prométhé AI Agent",
        url = "$baseUrl/agents/a2a",
        version = "1.0.0",
        capabilities = AgentCapabilities(
            streaming = true,
        ),
        defaultInputModes = listOf("text/plain"),
        defaultOutputModes = listOf("text/plain"),
        skills = listOf(
            AgentSkill(
                id = "chat",
                name = "Chat",
                description = "General chat",
                tags = listOf("chat", "conversation"),
            ),
        ),
    )

    private val client = A2AClient(transport, ExplicitAgentCardResolver(agentCard))

    private val connectionMutex = Mutex()
    private var connected = false

    /** Initialize the A2A client (resolves agent card). */
    private suspend fun ensureConnected() {
        if (!connected) {
            connectionMutex.withLock {
                if (!connected) {
                    client.connect()
                    connected = true
                    logger.info { "A2A client connected to $baseUrl" }
                }
            }
        }
    }

    /**
     * Send a message and receive streaming events (thoughts, actions, response).
     *
     * Maps Koog A2A protocol events to Prométhé ChatEvent:
     * - Message → "thought", "action", "observation", or "response" (via metadata.type)
     * - Task status updates → "thought" with status info
     * - Task artifacts → "response" with artifact content
     *
     * @param profileProvider  Optional LLM provider (e.g. "anthropic") to use for this request.
     * @param profileModel     Optional model name (e.g. "claude-opus-4-5") to use for this request.
     *   When set, these are embedded in message metadata so the agent uses the correct model.
     */
    fun sendMessageStreaming(
        sessionId: String,
        text: String,
        profileId: String? = null,
        profileProvider: String? = null,
        profileModel: String? = null,
    ): Flow<ChatEvent> =
        flow {
            ensureConnected()

            // Build metadata with optional model override for per-request profile selection.
            // Message.metadata is JsonObject? in the Koog A2A SDK.
            val msgMetadata: kotlinx.serialization.json.JsonObject? =
                if (profileId != null || profileProvider != null || profileModel != null) {
                    buildJsonObject {
                        profileId?.let { put("profileId", JsonPrimitive(it)) }
                        profileProvider?.let { put("provider", JsonPrimitive(it)) }
                        profileModel?.let { put("model", JsonPrimitive(it)) }
                    }
                } else {
                    null
                }

            val message = Message(
                messageId = generateId(),
                role = Role.User,
                parts = listOf(TextPart(text)),
                contextId = sessionId,
                metadata = msgMetadata,
            )
            logger.debug { "A2A sending message with metadata=$msgMetadata, provider=$profileProvider, model=$profileModel" }

            val params = MessageSendParams(message)
            val request = Request(data = params)

            try {
                client.sendMessageStreaming(request).collect { response ->
                    val event = response.data
                    val chatEvent = mapEventToChatEvent(event)
                    if (chatEvent != null) {
                        emit(chatEvent)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "A2A streaming error for session $sessionId" }
                emit(ChatEvent(type = "error", content = "Streaming error: ${e.message}"))
            }

            emit(ChatEvent(type = "done"))
        }

    /**
     * Map a Koog A2A streaming event to a Prométhé ChatEvent.
     */
    private fun mapEventToChatEvent(event: Event): ChatEvent? =
        when (event) {
            is Message -> {
                mapMessageToChatEvent(event)
            }

            is TaskStatusUpdateEvent -> {
                val msg = event.status.message ?: return null
                val statusText = msg.parts
                    ?.filterIsInstance<TextPart>()
                    ?.joinToString("\n") { it.text }
                if (statusText.isNullOrBlank()) {
                    // Terminal events (COMPLETED/FAILED) with no message text
                    if (event.status.state == TaskState.Completed || event.status.state == TaskState.Failed) {
                        return null // Don't emit empty terminal events as chat
                    }
                    return ChatEvent(type = "thought", content = "Status: ${event.status.state}")
                }
                // Extract type from message metadata (set by PrometheA2AExecutor)
                // Skip lifecycle-only events (no metadata.type = initial Working/Processing)
                val type = msg.metadata
                    ?.get("type")
                    ?.let { (it as? JsonPrimitive)?.content }
                    ?: if (event.status.state == TaskState.Failed) "error" else return null

                // Filter out post-response system events (skill extraction, fact extraction)
                if (type == "system") return null

                // Extract tool info for action events
                val tool = msg.metadata
                    ?.get("tool")
                    ?.let { (it as? JsonPrimitive)?.content }

                val args = msg.metadata
                    ?.get("args")
                    ?.let { it as? JsonObject }

                ChatEvent(type = type, content = statusText, tool = tool, args = args, metadata = msg.metadata)
            }

            is TaskArtifactUpdateEvent -> {
                val text = event.artifact.parts
                    .filterIsInstance<TextPart>()
                    .joinToString("\n") { it.text }
                if (text.isNotBlank()) {
                    ChatEvent(type = "response", content = text)
                } else {
                    null
                }
            }

            is Task -> {
                // Lifecycle event only — not user-facing
                null
            }
        }

    /**
     * Map a Koog A2A Message to a ChatEvent.
     * Uses metadata.type set by PrometheA2AExecutor to distinguish
     * thought/action/observation/response.
     */
    private fun mapMessageToChatEvent(msg: Message): ChatEvent? {
        val text = msg.parts
            .filterIsInstance<TextPart>()
            .joinToString("\n") { it.text }

        if (text.isBlank()) return null

        // Extract type from metadata (set by PrometheA2AExecutor)
        val type = msg.metadata
            ?.get("type")
            ?.let { (it as? JsonPrimitive)?.content }
            ?: "response"

        // Extract tool info for action events
        val tool = msg.metadata
            ?.get("tool")
            ?.let { (it as? JsonPrimitive)?.content }

        val args = msg.metadata
            ?.get("args")
            ?.let { it as? JsonObject }

        return ChatEvent(
            type = type,
            content = text,
            tool = tool,
            args = args,
            metadata = msg.metadata,
        )
    }

    private fun generateId(): String {
        // Simple ID generation — works on all KMP targets
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { chars.random() }.joinToString("")
    }

    fun close() {
        httpClient.close()
    }
}
