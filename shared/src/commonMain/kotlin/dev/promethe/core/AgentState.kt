package dev.promethe.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import dev.promethe.api.ReasoningEffort
import dev.promethe.api.SandboxApprovalPolicy
import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxNetworkMode
import dev.promethe.api.XaiApiMode

@Serializable
data class AgentConfig(
    val modelName: String = "nousresearch/hermes-3-llama-3.1-405b",
    val provider: String = "openrouter",
    val temperature: Double = 0.2,
    val maxTokens: Int = 4096,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.AUTO,
    val xaiApiMode: XaiApiMode = XaiApiMode.RESPONSES,
    val profileDirectory: String = "", // Resolved to ~/.promethe at JVM layer (see AgentBootstrap)
    val executionBackend: String = "local", // native sandbox, docker, singularity, modal, daytona
    val executionTimeoutMs: Long = 30_000, // Command timeout (default 30s)
    val maxOutputBytes: Int = 50_000, // Truncate output beyond 50KB
    val sandboxBackend: String = "auto",
    val sandboxMode: SandboxMode = SandboxMode.WORKSPACE_WRITE,
    val sandboxApprovalPolicy: SandboxApprovalPolicy = SandboxApprovalPolicy.ON_REQUEST,
    val sandboxNetworkMode: SandboxNetworkMode = SandboxNetworkMode.OFF,
    val sandboxAllowedDomains: List<String> = emptyList(),
    val customBaseUrl: String = "", // For LiteLLM proxy or any OpenAI-compatible endpoint
    val honchoBaseUrl: String = "",
    val honchoApiKey: String = "",
    val memoryProvider: String = "embedded", // "embedded", "honcho", "tencent"
    val tencentMemoryUrl: String = "",
    val tencentMemoryServiceId: String = "",
    val tencentMemoryApiKey: String = "",
    val tracingBackend: String = "console", // "console", "langfuse", "otlp"
    val langfusePublicKey: String = "",
    val langfuseSecretKey: String = "",
    val langfuseHost: String = "https://cloud.langfuse.com",
    val otlpEndpoint: String = "",
    val maxContextTokens: Int = 100_000, // Model context window size
    val compressionThreshold: Double = 0.8, // Compress when usage exceeds 80%
    // SSH execution backend
    val sshHost: String = "",
    val sshUser: String = "",
    val sshKeyPath: String = "",
    val sshPort: Int = 22,
    // Tool approval gate
    val approvalMode: String = "dangerous", // Legacy migration input: "auto", "dangerous", "all"
    val approvalTimeoutMs: Long = 120_000, // 2 minutes
    // GEPA self-evolution
    val gepaEnabled: Boolean = false,
    val gepaIntervalMinutes: Long = 60,
    val gepaAutoApply: Boolean = false,
    // LLM fallback chain (experimental — disabled by default in v0.1.0)
    val fallbackChainEnabled: Boolean = false,
)

@Serializable
data class ConversationMessage(
    val role: String,
    val content: String,
    val timestamp: Long,
)

@Serializable
data class Action(
    val toolName: String,
    val args: JsonObject,
)

@Serializable
sealed interface EvaluationResult {
    @Serializable
    data class Success(
        val toolName: String,
        val arguments: JsonObject,
    ) : EvaluationResult

    @Serializable
    data class Failure(
        val errorMessage: String,
    ) : EvaluationResult
}

@Serializable
data class ConversationTrajectory(
    val inputs: Map<String, String>,
    val outputs: Map<String, String>,
    val thought: String? = null,
    val action: Action? = null,
    val observation: String? = null,
)
