package dev.promethe.core.sandbox

import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.api.SandboxStatus
import dev.promethe.api.SandboxedExecutionRequest
import dev.promethe.api.SandboxedExecutionResult

interface SandboxManager {
    suspend fun execute(request: SandboxedExecutionRequest): SandboxedExecutionResult

    suspend fun cancel(executionId: String): Boolean

    suspend fun status(): SandboxStatus

    suspend fun selfTest(): SandboxStatus
}

fun interface SandboxProcessLauncher {
    suspend fun execute(request: SandboxedExecutionRequest): SandboxedExecutionResult
}

interface SandboxCommandExecutor {
    suspend fun executeCommand(
        executable: String,
        arguments: List<String>,
        workingDirectory: String,
        sessionId: String,
        timeoutMillis: Long,
    ): SandboxedExecutionResult

    fun approvalContext(): String
}

interface WorkspaceAccessBroker {
    fun resolveReadablePath(
        workspaceRoot: String,
        requestedPath: String,
        profile: SandboxPermissionProfile,
    ): String

    fun resolveWritablePath(
        workspaceRoot: String,
        requestedPath: String,
        profile: SandboxPermissionProfile,
    ): String
}

fun SandboxedExecutionResult.renderCommandOutput(): String {
    errorCode?.let { code ->
        return "[SANDBOX ${code.name}] ${errorMessage ?: "Execution denied."}"
    }
    val output =
        buildString {
            if (stdout.isNotBlank()) append(stdout.trimEnd())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(stderr.trimEnd())
            }
        }.ifBlank { "(no output)" }
    val prefix = exitCode?.takeIf { it != 0 }?.let { "[EXIT $it]\n" }.orEmpty()
    val suffix = if (truncated) "\n--- [TRUNCATED] ---" else ""
    return prefix + output + suffix
}
