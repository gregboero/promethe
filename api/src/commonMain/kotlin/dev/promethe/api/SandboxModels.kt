package dev.promethe.api

import kotlinx.serialization.Serializable

const val SANDBOX_PROTOCOL_VERSION = 1

@Serializable
enum class SandboxMode {
    READ_ONLY,
    WORKSPACE_WRITE,
    FULL_ACCESS,
}

@Serializable
enum class SandboxApprovalPolicy {
    ON_REQUEST,
    UNTRUSTED,
    NEVER,
}

@Serializable
enum class SandboxNetworkMode {
    OFF,
    ALLOWLIST,
}

@Serializable
enum class SandboxApprovalScope {
    ONCE,
    SESSION,
    PERSISTENT,
}

@Serializable
enum class SandboxBackend {
    LINUX_BWRAP,
    MACOS_SEATBELT,
    WINDOWS_ELEVATED,
    DOCKER,
    WSL2,
    UNAVAILABLE,
}

@Serializable
enum class SandboxErrorCode {
    INVALID_REQUEST,
    BACKEND_UNAVAILABLE,
    SETUP_REQUIRED,
    POLICY_DENIED,
    NETWORK_DENIED,
    WORKSPACE_VIOLATION,
    RESOURCE_LIMIT,
    TIMED_OUT,
    CANCELLED,
    PROTOCOL_ERROR,
    INTERNAL_ERROR,
}

@Serializable
data class SandboxResourceLimits(
    val timeoutMillis: Long = 30_000,
    val maxOutputBytesPerStream: Int = 50_000,
    val memoryBytes: Long = 512L * 1024L * 1024L,
    val cpuLimit: Double = 1.0,
    val processLimit: Int = 128,
)

@Serializable
data class SandboxPermissionProfile(
    val mode: SandboxMode = SandboxMode.WORKSPACE_WRITE,
    val approvalPolicy: SandboxApprovalPolicy = SandboxApprovalPolicy.ON_REQUEST,
    val networkMode: SandboxNetworkMode = SandboxNetworkMode.OFF,
    val readableRoots: List<String> = emptyList(),
    val writableRoots: List<String> = emptyList(),
    val protectedPaths: List<String> = listOf(".git", ".promethe", ".codex", ".agents"),
    val allowedDomains: List<String> = emptyList(),
    val limits: SandboxResourceLimits = SandboxResourceLimits(),
)

@Serializable
data class SandboxedExecutionRequest(
    val protocolVersion: Int = SANDBOX_PROTOCOL_VERSION,
    val executionId: String,
    val sessionId: String,
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String,
    val environment: Map<String, String> = emptyMap(),
    val sensitiveEnvironmentKeys: Set<String> = emptySet(),
    val profile: SandboxPermissionProfile = SandboxPermissionProfile(),
    val interactive: Boolean = false,
)

@Serializable
data class SandboxedExecutionResult(
    val protocolVersion: Int = SANDBOX_PROTOCOL_VERSION,
    val executionId: String,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val truncated: Boolean = false,
    val durationMillis: Long = 0,
    val errorCode: SandboxErrorCode? = null,
    val errorMessage: String? = null,
)

@Serializable
data class SandboxStatus(
    val protocolVersion: Int = SANDBOX_PROTOCOL_VERSION,
    val available: Boolean,
    val backend: SandboxBackend,
    val mode: SandboxMode,
    val networkMode: SandboxNetworkMode,
    val degraded: Boolean = false,
    val setupRequired: Boolean = false,
    val selfTestPassed: Boolean = false,
    val message: String? = null,
)

@Serializable
enum class SandboxIpcOperation {
    EXECUTE,
    CANCEL,
    STATUS,
    SELF_TEST,
}

@Serializable
data class SandboxIpcRequest(
    val protocolVersion: Int = SANDBOX_PROTOCOL_VERSION,
    val operation: SandboxIpcOperation,
    val execution: SandboxedExecutionRequest? = null,
    val executionId: String? = null,
)

@Serializable
data class SandboxIpcResponse(
    val protocolVersion: Int = SANDBOX_PROTOCOL_VERSION,
    val operation: SandboxIpcOperation,
    val execution: SandboxedExecutionResult? = null,
    val status: SandboxStatus? = null,
    val errorCode: SandboxErrorCode? = null,
    val errorMessage: String? = null,
)
