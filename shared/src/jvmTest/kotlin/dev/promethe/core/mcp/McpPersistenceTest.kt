package dev.promethe.core.mcp

import dev.promethe.core.McpBridge
import dev.promethe.core.ToolApprovalPolicy
import dev.promethe.core.ToolRegistry
import dev.promethe.core.ToolRisk
import dev.promethe.core.security.SecretCipher
import dev.promethe.db.DatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class McpPersistenceTest {
    private val cipher = SecretCipher.fromBase64Key(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))

    @Test
    fun `MCP secrets are encrypted at rest and restored only for runtime`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val store = McpConfigurationStore(database, cipher)
            val config =
                McpBridge.McpServerConfig(
                    id = "calendar",
                    name = "Calendar",
                    transport = "streamable-http",
                    url = "https://mcp.example.test",
                    headers = mapOf("Authorization" to "Bearer secret"),
                    env = mapOf("MCP_TOKEN" to "secret"),
                )

            store.upsert(config)
            val raw = database.getMcpServerConfigs().single()
            assertFalse(raw.configJson.contains("Bearer secret"))
            assertFalse(raw.encryptedSecrets.contains("Bearer secret"))

            val restored = store.load().single()
            assertEquals(config.headers, restored.headers)
            assertEquals(config.env, restored.env)
        }

    @Test
    fun `removing an MCP server unregisters its discovered tools`() =
        runTest {
            ToolRegistry.clear()
            val bridge = McpBridge()
            bridge.setTransportFactory(
                object : McpBridge.TransportFactory {
                    override fun createTransport(config: McpBridge.McpServerConfig): McpBridge.McpTransportApi =
                        object : McpBridge.McpTransportApi {
                            override suspend fun initialize(): JsonObject = JsonObject(emptyMap())

                            override suspend fun listTools(): List<McpBridge.McpToolInfo> = listOf(McpBridge.McpToolInfo(config.id, "ping", "Ping"))

                            override suspend fun callTool(
                                name: String,
                                arguments: JsonObject,
                            ): String = "pong"

                            override fun close() = Unit
                        }
                },
            )
            bridge.registerServer(McpBridge.McpServerConfig("test", "Test", "streamable-http", url = "https://mcp.example.test"))
            bridge.connectServer("test").getOrThrow()
            assertNotNull(ToolRegistry.getTool("mcp_test_ping"))

            bridge.removeServer("test")
            assertNull(ToolRegistry.getTool("mcp_test_ping"))
            ToolRegistry.clear()
        }

    @Test
    fun `only locally certified MCP tools are read only and certification is revoked`() =
        runTest {
            ToolRegistry.clear()
            val bridge = bridgeWithTools("lookup", "mutate")
            bridge.registerServer(
                McpBridge.McpServerConfig(
                    id = "certified",
                    name = "Certified",
                    transport = "streamable-http",
                    url = "https://mcp.example.test",
                    certifiedReadOnlyTools = setOf("lookup"),
                ),
            )

            bridge.connectServer("certified").getOrThrow()

            assertEquals(ToolRisk.READ, ToolApprovalPolicy.catalogRisk("mcp_certified_lookup"))
            assertEquals(ToolRisk.EXTERNAL_EFFECT, ToolApprovalPolicy.catalogRisk("mcp_certified_mutate"))

            bridge.removeServer("certified")
            assertEquals(ToolRisk.EXTERNAL_EFFECT, ToolApprovalPolicy.catalogRisk("mcp_certified_lookup"))
            ToolRegistry.clear()
        }

    private fun bridgeWithTools(vararg toolNames: String): McpBridge =
        McpBridge().apply {
            setTransportFactory(
                object : McpBridge.TransportFactory {
                    override fun createTransport(config: McpBridge.McpServerConfig): McpBridge.McpTransportApi =
                        object : McpBridge.McpTransportApi {
                            override suspend fun initialize(): JsonObject = JsonObject(emptyMap())

                            override suspend fun listTools(): List<McpBridge.McpToolInfo> =
                                toolNames.map { toolName ->
                                    McpBridge.McpToolInfo(config.id, toolName, toolName)
                                }

                            override suspend fun callTool(
                                name: String,
                                arguments: JsonObject,
                            ): String = "ok"

                            override fun close() = Unit
                        }
                },
            )
        }
}
