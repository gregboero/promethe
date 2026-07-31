package dev.promethe.core

import kotlinx.serialization.json.JsonObject

/**
 * stdio MCP needs a long-lived, bidirectional process. Sandbox IPC v1 only
 * supports bounded one-shot executions, so launching a server here would
 * bypass the sandbox. Keep this transport deliberately unavailable until the
 * protocol grows managed interactive process sessions.
 */
class McpStdioTransport(
    command: String,
    env: Map<String, String> = emptyMap(),
    workingDir: String? = null,
) {
    private val unavailable =
        McpStdioUnavailableException(
            command = command,
            hasEnvironment = env.isNotEmpty(),
            workingDir = workingDir,
        )

    fun start(): Nothing = throw unavailable

    suspend fun initialize(): JsonObject = throw unavailable

    suspend fun listTools(): List<McpBridge.McpToolInfo> = throw unavailable

    suspend fun callTool(
        name: String,
        arguments: JsonObject,
    ): String = throw unavailable

    fun close() = Unit

    val isRunning: Boolean get() = false
}

/** Machine-readable reason surfaced by MCP connection failures. */
enum class McpStdioUnavailableReason {
    SANDBOX_PROTOCOL_V2_REQUIRED,
}

class McpStdioUnavailableException internal constructor(
    command: String,
    hasEnvironment: Boolean,
    workingDir: String?,
) : IllegalStateException(
        buildString {
            append("MCP stdio is unavailable [")
            append(McpStdioUnavailableReason.SANDBOX_PROTOCOL_V2_REQUIRED.name)
            append("]: sandbox IPC v1 has no managed persistent stdin/stdout sessions. ")
            append("Use streamable-http or SSE, or upgrade the sandbox helper to protocol v2.")
        },
    ) {
    val reason = McpStdioUnavailableReason.SANDBOX_PROTOCOL_V2_REQUIRED
    val commandConfigured = command.isNotBlank()
    val environmentConfigured = hasEnvironment
    val workingDirectoryConfigured = !workingDir.isNullOrBlank()
}
