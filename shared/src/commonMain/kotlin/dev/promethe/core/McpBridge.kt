package dev.promethe.core

import ai.koog.agents.core.tools.ToolBase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * McpBridge — manages connections to MCP servers and exposes their tools
 * in Prométhé's ToolRegistry.
 *
 * Architecture:
 *   McpBridge ─┬─ StdioServer("filesystem", "npx @mcp/filesystem")
 *              ├─ SseServer("search", "http://localhost:3001/sse")
 *              ├─ StreamableHttpServer("custom", "http://localhost:3002/mcp")
 *              └─ StdioServer("github", "npx @mcp/github")
 *                   ↓
 *              Each server's tools auto-register into ToolRegistry
 *
 * MCP servers are sourced from the process environment or encrypted persistent configuration.
 */
class McpBridge {
    // ── Data models ──────────────────────────────────────────────

    /**
     * Configuration for an MCP server connection.
     *
     * Supports three transport types:
     * - "stdio": Run a local sub-process and communicate via stdio.
     * - "sse": Connect to a persistent SSE stream for events/requests.
     * - "streamable-http": A stateless HTTP-based connection where each request is sent independently.
     *
     * @property id Unique identifier for the server.
     * @property name Human-readable name.
     * @property transport Transport type: "stdio", "sse", or "streamable-http".
     * @property command Command to launch a local server process (for "stdio").
     * @property url Target URL (for "sse" or "streamable-http").
     * @property env Environment variables for local process (for "stdio").
     * @property headers Custom HTTP headers (for "sse" or "streamable-http").
     * @property enabled Whether this server is active.
     */
    @Serializable
    data class McpServerConfig(
        val id: String,
        val name: String,
        val transport: String,
        val command: String = "",
        val url: String = "",
        val env: Map<String, String> = emptyMap(),
        val headers: Map<String, String> = emptyMap(),
        /**
         * Original MCP tool names manually certified as read-only by the local
         * owner. Server-provided annotations do not populate this list.
         */
        val certifiedReadOnlyTools: Set<String> = emptySet(),
        val enabled: Boolean = true,
    )

    @Serializable
    data class McpToolInfo(
        val serverId: String,
        val toolName: String,
        val description: String,
        val inputSchema: JsonObject? = null,
        val certifiedReadOnly: Boolean = false,
    )

    enum class ServerStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    data class ServerState(
        val config: McpServerConfig,
        val status: ServerStatus = ServerStatus.DISCONNECTED,
        val tools: List<McpToolInfo> = emptyList(),
        val error: String? = null,
    )

    // ── State ────────────────────────────────────────────────────

    private val mutex = Mutex()
    private val servers = mutableMapOf<String, ServerState>()
    private val mcpTools = mutableMapOf<String, ToolBase<*, *>>()
    private val transports = mutableMapOf<String, McpTransportApi>()

    /**
     * Platform-agnostic transport interface.
     * Implemented by McpStdioTransport and McpSseTransport on JVM.
     */
    interface McpTransportApi {
        suspend fun initialize(): JsonObject

        suspend fun listTools(): List<McpToolInfo>

        suspend fun callTool(
            name: String,
            arguments: JsonObject,
        ): String

        fun close()
    }

    // ── Public API ───────────────────────────────────────────────

    /**
     * Register an MCP server configuration.
     * Does NOT connect yet — call [connectServer] or [connectAll].
     */
    suspend fun registerServer(config: McpServerConfig) =
        mutex.withLock {
            val previous = servers[config.id]
            if (previous != null) clearServerResources(previous)
            servers[config.id] = ServerState(config = config)
        }

    /**
     * Connect to a specific MCP server, discover its tools, and register them.
     */
    suspend fun connectServer(serverId: String): Result<List<McpToolInfo>> =
        mutex.withLock {
            val state =
                servers[serverId]
                    ?: return@withLock Result.failure(IllegalArgumentException("Unknown server: $serverId"))

            if (!state.config.enabled) {
                return@withLock Result.failure(IllegalStateException("Server $serverId is disabled"))
            }

            clearServerResources(state)

            servers[serverId] = state.copy(status = ServerStatus.CONNECTING)

            try {
                val tools = discoverTools(state.config)
                val registeredTools = mutableListOf<McpToolInfo>()

                tools.forEach { toolInfo ->
                    val qualifiedName = "mcp_${serverId}_${toolInfo.toolName}"
                    val certifiedReadOnly = toolInfo.toolName in state.config.certifiedReadOnlyTools
                    val mcpTool =
                        McpProxyTool(
                            name = qualifiedName,
                            description = "[MCP:${state.config.name}] ${toolInfo.description}",
                            serverId = serverId,
                            originalToolName = toolInfo.toolName,
                            inputSchema = toolInfo.inputSchema,
                            bridge = this@McpBridge,
                        )
                    ToolRegistry.register(mcpTool)
                    if (certifiedReadOnly) {
                        ToolApprovalPolicy.certifyReadOnlyMcpTool(qualifiedName)
                    }
                    mcpTools[qualifiedName] = mcpTool
                    registeredTools.add(
                        toolInfo.copy(
                            toolName = qualifiedName,
                            certifiedReadOnly = certifiedReadOnly,
                        ),
                    )
                }

                servers[serverId] =
                    state.copy(
                        status = ServerStatus.CONNECTED,
                        tools = registeredTools,
                        error = null,
                    )
                Result.success(registeredTools)
            } catch (e: Exception) {
                servers[serverId] =
                    state.copy(
                        status = ServerStatus.ERROR,
                        error = e.message,
                    )
                Result.failure(e)
            }
        }

    /**
     * Connect all registered and enabled servers.
     */
    suspend fun connectAll(): Map<String, Result<List<McpToolInfo>>> {
        val configs = mutex.withLock { servers.keys.toList() }
        return configs.associateWith { connectServer(it) }
    }

    /**
     * Disconnect a server and unregister its tools.
     */
    suspend fun disconnectServer(serverId: String) =
        mutex.withLock {
            val state = servers[serverId] ?: return@withLock
            clearServerResources(state)
            servers[serverId] =
                state.copy(
                    status = ServerStatus.DISCONNECTED,
                    tools = emptyList(),
                )
        }

    /** Disconnect and remove a server configuration from the runtime bridge. */
    suspend fun removeServer(serverId: String) =
        mutex.withLock {
            val state = servers.remove(serverId) ?: return@withLock
            clearServerResources(state)
        }

    /**
     * Execute a tool call on an MCP server (used by McpProxyTool).
     */
    suspend fun executeToolCall(
        serverId: String,
        toolName: String,
        arguments: JsonObject,
    ): String {
        val state =
            mutex.withLock { servers[serverId] }
                ?: throw IllegalArgumentException("Unknown server: $serverId")

        if (state.status != ServerStatus.CONNECTED) {
            throw IllegalStateException("Server $serverId is not connected (status=${state.status})")
        }

        val transport =
            transports[serverId]
                ?: throw IllegalStateException("No transport for server $serverId")

        return transport.callTool(toolName, arguments)
    }

    /**
     * List all registered servers and their status.
     */
    suspend fun listServers(): List<ServerState> =
        mutex.withLock {
            servers.values.toList()
        }

    /**
     * Get all tools from connected MCP servers.
     */
    suspend fun listMcpTools(): List<McpToolInfo> =
        mutex.withLock {
            servers.values.flatMap { it.tools }
        }

    /**
     * Register a platform-specific transport factory.
     * Called by JVM bootstrap to provide Stdio/SSE transports.
     */
    fun setTransportFactory(factory: TransportFactory) {
        this.transportFactory = factory
    }

    interface TransportFactory {
        fun createTransport(config: McpServerConfig): McpTransportApi
    }

    private var transportFactory: TransportFactory? = null

    // ── Private ──────────────────────────────────────────────────

    private suspend fun discoverTools(config: McpServerConfig): List<McpToolInfo> {
        val factory =
            transportFactory
                ?: throw IllegalStateException("No MCP transport factory registered. Call setTransportFactory() first.")

        val transport = factory.createTransport(config)
        transport.initialize()
        val tools = transport.listTools()
        transports[config.id] = transport
        return tools
    }

    private suspend fun clearServerResources(state: ServerState) {
        state.tools.forEach { tool ->
            mcpTools.remove(tool.toolName)
            ToolRegistry.unregister(tool.toolName)
            ToolApprovalPolicy.revokeReadOnlyMcpToolCertification(tool.toolName)
        }
        transports[state.config.id]?.close()
        transports.remove(state.config.id)
    }
}
