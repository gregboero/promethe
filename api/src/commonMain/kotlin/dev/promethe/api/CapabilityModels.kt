package dev.promethe.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class CapabilityMaturity {
    STABLE,
    BETA,
    LAB,
    UNAVAILABLE,
}

@Serializable
enum class CapabilityAvailability {
    AVAILABLE,
    MISSING_CONFIGURATION,
    DISABLED,
    NOT_IMPLEMENTED,
}

@Serializable
enum class CapabilityAuthentication {
    AUTHENTICATED,
    SIGNED_OUT,
    UNKNOWN,
    NOT_APPLICABLE,
}

@Serializable
data class CapabilityDescriptor(
    val id: String,
    val name: String,
    val category: String,
    val maturity: CapabilityMaturity,
    val availability: CapabilityAvailability,
    val platforms: List<String> = listOf("gateway"),
    val risk: String = "READ",
    val requiredConfiguration: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val runtimeVersion: String? = null,
    val authentication: CapabilityAuthentication = CapabilityAuthentication.NOT_APPLICABLE,
)

@Serializable
data class CapabilityListResponse(
    val version: String = PrometheVersion.CURRENT,
    val capabilities: List<CapabilityDescriptor>,
)

@Serializable
enum class ToolCallOrigin {
    AGENT,
    A2A,
    ACP,
    MCP_HTTP,
    MCP_STDIO,
    OPENAI_COMPAT,
    CHANNEL,
    VOICE,
    AUTONOMY,
    SCHEDULER,
}

@Serializable
enum class ToolRisk {
    READ,
    WRITE,
    EXECUTE,
    DESTRUCTIVE,
    EXTERNAL_EFFECT,
    DEVICE_CONTROL,
    CONFIG_CHANGE,
}

@Serializable
data class ToolInvocation(
    val toolName: String,
    val arguments: JsonObject,
    val sessionId: String = "unknown",
    val origin: ToolCallOrigin = ToolCallOrigin.AGENT,
    val projectId: String? = null,
    val memoryNamespace: String = "default",
    val workspaceRelativePath: String? = null,
)
