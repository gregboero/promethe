package dev.promethe.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Lightweight A2A Protocol models for the KMP client.
 *
 * These mirror the A2A spec (v0.3) but are pure Kotlin/Serialization
 * so they work on all platforms (JVM, WasmJS, iOS, Android).
 *
 * The gateway's Koog A2AServer handles the full spec;
 * these models are just for the client side.
 */

// ── JSON-RPC envelope ────────────────────────────────────────

@Serializable
data class A2AJsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonElement,
)

@Serializable
data class A2AJsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: String? = null,
    val result: JsonElement? = null,
    val error: A2AJsonRpcError? = null,
)

@Serializable
data class A2AJsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

// ── A2A Messages ─────────────────────────────────────────────

@Serializable
data class A2AMessage(
    val messageId: String,
    val role: String, // "user" | "agent"
    val parts: List<A2APart>,
    val kind: String = "message",
    val contextId: String? = null,
    val metadata: JsonObject? = null,
)

@Serializable
sealed class A2APart {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        val kind: String = "text",
    ) : A2APart()
}

// ── message/send params ──────────────────────────────────────

@Serializable
data class A2AMessageSendParams(
    val message: A2AMessage,
)

// ── Agent Card (discovery) ───────────────────────────────────

@Serializable
data class A2AAgentCard(
    val name: String,
    val description: String,
    val url: String,
    val version: String,
    val skills: List<A2ASkill> = emptyList(),
    val capabilities: A2ACapabilities? = null,
    val defaultInputModes: List<String> = listOf("text/plain"),
    val defaultOutputModes: List<String> = listOf("text/plain"),
    val provider: A2AProvider? = null,
)

@Serializable
data class A2ASkill(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String> = emptyList(),
)

@Serializable
data class A2ACapabilities(
    val streaming: Boolean = false,
    val pushNotifications: Boolean = false,
    val stateTransitionHistory: Boolean = false,
)

@Serializable
data class A2AProvider(
    val organization: String,
    val url: String? = null,
)
