package dev.promethe.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ══════════════════════════════════════════════════════════════
// OpenAI-Compatible API DTOs
//
// These follow the official OpenAI API specification exactly,
// so any OpenAI SDK/client can consume Promethe responses.
// ══════════════════════════════════════════════════════════════

// ── Request ─────────────────────────────────────────────────

@Serializable
data class OpenAIChatRequest(
    val model: String,
    val messages: List<OpenAIChatMessage>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean? = false,
    @SerialName("top_p") val topP: Double? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Double? = null,
    @SerialName("presence_penalty") val presencePenalty: Double? = null,
    val stop: List<String>? = null,
    val user: String? = null,
)

@Serializable
data class OpenAIChatMessage(
    val role: String,
    val content: String,
)

// ── Non-Streaming Response ──────────────────────────────────

@Serializable
data class OpenAIChatResponse(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<OpenAIChatChoice>,
    val usage: OpenAIUsage,
)

@Serializable
data class OpenAIChatChoice(
    val index: Int = 0,
    val message: OpenAIChatMessage,
    @SerialName("finish_reason") val finishReason: String = "stop",
)

@Serializable
data class OpenAIUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)

// ── Streaming Chunk Response ────────────────────────────────

@Serializable
data class OpenAIStreamChunk(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<OpenAIStreamChoice>,
    val usage: OpenAIUsage? = null,
)

@Serializable
data class OpenAIStreamChoice(
    val index: Int = 0,
    val delta: OpenAIDelta,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class OpenAIDelta(
    val role: String? = null,
    val content: String? = null,
)

// ── Models List ─────────────────────────────────────────────

@Serializable
data class OpenAIModelList(
    @SerialName("object") val objectType: String = "list",
    val data: List<OpenAIModel>,
)

@Serializable
data class OpenAIModel(
    val id: String,
    @SerialName("object") val objectType: String = "model",
    val created: Long,
    @SerialName("owned_by") val ownedBy: String,
)

// ── Error ───────────────────────────────────────────────────

@Serializable
data class OpenAIErrorResponse(
    val error: OpenAIError,
)

@Serializable
data class OpenAIError(
    val message: String,
    val type: String = "server_error",
    val code: String? = null,
)
