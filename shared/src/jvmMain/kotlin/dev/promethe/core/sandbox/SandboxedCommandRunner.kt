package dev.promethe.core.sandbox

import dev.promethe.api.SandboxErrorCode
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.api.SandboxedExecutionRequest
import dev.promethe.api.SandboxedExecutionResult
import dev.promethe.core.AgentConfig
import dev.promethe.core.tools.sys.ShellInvocationPolicy
import java.nio.file.Path
import java.util.UUID

/**
 * Builds the complete, fingerprintable execution request used by process tools.
 * The native helper remains the only component allowed to launch untrusted code.
 */
class SandboxedCommandRunner(
    private val launcher: SandboxProcessLauncher,
    private val runtimePolicy: SandboxRuntimePolicy,
    workspaceRoot: String,
    private val accessBroker: WorkspaceAccessBroker = CanonicalWorkspaceAccessBroker(),
) : SandboxCommandExecutor {
    constructor(
        launcher: SandboxProcessLauncher,
        workspaceRoot: String,
        config: AgentConfig,
        accessBroker: WorkspaceAccessBroker = CanonicalWorkspaceAccessBroker(),
    ) : this(
        launcher = launcher,
        runtimePolicy = SandboxRuntimePolicy(workspaceRoot, config),
        workspaceRoot = workspaceRoot,
        accessBroker = accessBroker,
    )

    private val workspaceRoot = Path.of(workspaceRoot).toRealPath().toString()

    suspend fun execute(
        executable: String,
        arguments: List<String> = emptyList(),
        workingDirectory: String = ".",
        sessionId: String = DEFAULT_SESSION_ID,
        timeoutMillis: Long = runtimePolicy.get().limits.timeoutMillis,
        environment: Map<String, String> = emptyMap(),
        sensitiveEnvironmentKeys: Set<String> = emptySet(),
        interactive: Boolean = false,
    ): SandboxedExecutionResult {
        val profile = runtimePolicy.processProfile(timeoutMillis)
        val resolvedWorkingDirectory =
            accessBroker.resolveReadablePath(
                workspaceRoot = workspaceRoot,
                requestedPath = workingDirectory,
                profile = profile,
            )
        return launcher.execute(
            SandboxedExecutionRequest(
                executionId = UUID.randomUUID().toString(),
                sessionId = sanitizeIdentifier(sessionId),
                executable = executable,
                arguments = arguments,
                workingDirectory = resolvedWorkingDirectory,
                environment = environment,
                sensitiveEnvironmentKeys = sensitiveEnvironmentKeys,
                profile = profile,
                interactive = interactive,
            ),
        )
    }

    override suspend fun executeCommand(
        executable: String,
        arguments: List<String>,
        workingDirectory: String,
        sessionId: String,
        timeoutMillis: Long,
    ): SandboxedExecutionResult {
        ShellInvocationPolicy.validate(executable, arguments)?.let { reason ->
            return SandboxedExecutionResult(
                executionId = UUID.randomUUID().toString(),
                errorCode = SandboxErrorCode.POLICY_DENIED,
                errorMessage = reason,
            )
        }
        return execute(
            executable = executable,
            arguments = arguments,
            workingDirectory = workingDirectory,
            sessionId = sessionId,
            timeoutMillis = timeoutMillis,
        )
    }

    override fun approvalContext(): String =
        SandboxApprovalFingerprint.policySha256(
            profile = runtimePolicy.get(),
            workspaceRoot = workspaceRoot,
        )

    fun permissionProfile(): SandboxPermissionProfile = runtimePolicy.get()

    override fun hasUnconfinedFileAccess(): Boolean = runtimePolicy.get().mode == dev.promethe.api.SandboxMode.FULL_ACCESS

    private fun sanitizeIdentifier(value: String): String {
        val sanitized =
            value
                .take(MAX_IDENTIFIER_LENGTH)
                .map { character ->
                    if (character.isLetterOrDigit() || character in "._-") character else '_'
                }.joinToString("")
        return sanitized.ifBlank { DEFAULT_SESSION_ID }
    }

    private companion object {
        const val DEFAULT_SESSION_ID = "promethe"
        const val MAX_IDENTIFIER_LENGTH = 128
    }
}
