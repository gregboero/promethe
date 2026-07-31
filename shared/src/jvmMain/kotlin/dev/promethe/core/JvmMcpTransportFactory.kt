package dev.promethe.core

import kotlinx.serialization.json.JsonObject

/**
 * JVM transport factory creates HTTP transports. stdio configurations are
 * represented by a fail-closed transport until sandbox IPC v2 supports
 * managed persistent process sessions.
 *
 * Adapts the JVM-specific transports to the common McpBridge.McpTransportApi.
 */
class JvmMcpTransportFactory : McpBridge.TransportFactory {
    override fun createTransport(config: McpBridge.McpServerConfig): McpBridge.McpTransportApi =
        when (config.transport.lowercase()) {
            "stdio" -> {
                StdioAdapter(
                    McpStdioTransport(
                        command = config.command,
                        env = config.env,
                    ),
                )
            }

            "sse" -> {
                SseAdapter(
                    McpSseTransport(
                        baseUrl = config.url,
                    ),
                )
            }

            "streamable-http" -> {
                StreamableHttpAdapter(
                    McpStreamableHttpTransport(
                        baseUrl = config.url,
                        customHeaders = config.headers,
                    ),
                )
            }

            else -> {
                throw IllegalArgumentException("Unknown MCP transport: ${config.transport}")
            }
        }

    // ── Adapters ────────────────────────────────────────────

    private class StdioAdapter(
        private val transport: McpStdioTransport,
    ) : McpBridge.McpTransportApi {
        override suspend fun initialize(): JsonObject = transport.initialize()

        override suspend fun listTools(): List<McpBridge.McpToolInfo> = transport.listTools()

        override suspend fun callTool(
            name: String,
            arguments: JsonObject,
        ): String = transport.callTool(name, arguments)

        override fun close() = transport.close()
    }

    private class SseAdapter(
        private val transport: McpSseTransport,
    ) : McpBridge.McpTransportApi {
        override suspend fun initialize(): JsonObject = transport.initialize()

        override suspend fun listTools(): List<McpBridge.McpToolInfo> = transport.listTools()

        override suspend fun callTool(
            name: String,
            arguments: JsonObject,
        ): String = transport.callTool(name, arguments)

        override fun close() = transport.close()
    }

    private class StreamableHttpAdapter(
        private val transport: McpStreamableHttpTransport,
    ) : McpBridge.McpTransportApi {
        override suspend fun initialize(): JsonObject = transport.initialize()

        override suspend fun listTools(): List<McpBridge.McpToolInfo> = transport.listTools()

        override suspend fun callTool(
            name: String,
            arguments: JsonObject,
        ): String = transport.callTool(name, arguments)

        override fun close() = transport.close()
    }
}
