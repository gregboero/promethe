package dev.promethe.core.acp

import kotlinx.serialization.Serializable

/**
 * ACP (Agent Communication Protocol) — data models.
 *
 * ACP is Hermes Agent's native protocol for agent-to-agent communication.
 * It provides service discovery, capability advertisement, and task invocation.
 */

// ── Agent Card (discovery) ───────────────────────────────────────────

@Serializable
data class AcpAgentCard(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "1.0.0",
    val url: String,
    val capabilities: List<AcpCapability> = emptyList(),
    val inputFormats: List<String> = listOf("text/plain"),
    val outputFormats: List<String> = listOf("text/plain"),
    val authentication: AcpAuth? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class AcpCapability(
    val id: String,
    val name: String,
    val description: String = "",
    val parameters: List<AcpParameter> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class AcpParameter(
    val name: String,
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false,
    val default: String? = null,
)

@Serializable
data class AcpAuth(
    val type: String = "bearer",
    val headerName: String = "Authorization",
)

// ── Request / Response ───────────────────────────────────────────────

@Serializable
data class AcpRequest(
    val capabilityId: String,
    val input: String,
    val parameters: Map<String, String> = emptyMap(),
    val contextId: String? = null,
    val stream: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class AcpResponse(
    val output: String,
    val status: AcpStatus = AcpStatus.COMPLETED,
    val taskId: String? = null,
    val error: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
enum class AcpStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

// ── Registry entry ───────────────────────────────────────────────────

@Serializable
data class AcpRegistryEntry(
    val agentId: String,
    val url: String,
    val lastSeen: Long = 0,
    val healthy: Boolean = true,
    val card: AcpAgentCard? = null,
)
