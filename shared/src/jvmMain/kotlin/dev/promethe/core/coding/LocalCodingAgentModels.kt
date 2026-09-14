package dev.promethe.core.coding

import dev.promethe.api.CapabilityAuthentication
import java.nio.file.Path

enum class LocalCodingAgentKind(
    val id: String,
    val displayName: String,
    val executableName: String,
    val configuredPathKey: String,
    val toolName: String,
) {
    CODEX("codex-local", "Codex local", "codex", "CODEX_CLI_PATH", "codex_delegate"),
    CLAUDE_CODE("claude-code", "Claude Code", "claude", "CLAUDE_CODE_CLI_PATH", "claude_code_delegate"),
}

enum class LocalCodingAccessMode {
    READ_ONLY,
    WORKSPACE_WRITE,
}

data class LocalCodingAgentRequest(
    val task: String,
    val accessMode: LocalCodingAccessMode,
    val prometheSessionId: String,
    val externalSessionId: String? = null,
    val workspaceRelativePath: String? = null,
)

data class LocalCodingAgentResult(
    val summary: String,
    val externalSessionId: String? = null,
)

data class LocalCodingAgentStatus(
    val kind: LocalCodingAgentKind,
    val available: Boolean,
    val version: String? = null,
    val authentication: CapabilityAuthentication = CapabilityAuthentication.UNKNOWN,
    val limitations: List<String> = emptyList(),
    internal val executable: Path? = null,
    internal val sha256: String? = null,
)

internal data class TrustedExecutable(
    val path: Path,
    val sha256: String,
)

internal interface LocalCodingAgentAdapter {
    val kind: LocalCodingAgentKind

    suspend fun execute(
        executable: TrustedExecutable,
        request: LocalCodingAgentRequest,
    ): LocalCodingAgentResult
}
