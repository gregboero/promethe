package dev.promethe.gateway

import dev.promethe.core.AgentBootstrap
import dev.promethe.core.CredentialsStore
import dev.promethe.core.config.ConfigProvider
import dev.promethe.core.config.MergedConfigProvider
import dev.promethe.core.security.SecretCipher
import dev.promethe.core.security.SecretRotationService
import dev.promethe.gateway.auth.OwnerAuthService
import dev.promethe.di.KoinBootstrap
import dev.promethe.gateway.di.gatewayModule
import dev.promethe.gateway.mcp.McpStdioServerMode
import dev.promethe.gateway.mcp.McpTaskManager
import dev.promethe.gateway.mcp.McpRoundTripManager
import dev.promethe.gateway.mcp.McpToolExporter
import dev.promethe.core.ToolCallOrigin
import dev.promethe.db.DatabaseBackupService
import dev.promethe.db.DatabaseFactory
import java.io.File
import kotlinx.coroutines.coroutineScope
import org.koin.core.context.GlobalContext.get as getKoin
import org.koin.java.KoinJavaComponent.inject

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

suspend fun main(args: Array<String>) =
    coroutineScope {
        val isMcpStdio = args.contains("--mcp-stdio")

        // Load stored credentials (auto-creates API key if needed)
        val credentials = CredentialsStore.loadOrCreate()

        // Initialize ConfigProvider from credentials (priority: credentials > env vars)
        ConfigProvider.initialize(MergedConfigProvider { CredentialsStore.load()?.toEnvMap() ?: emptyMap() })
        // Do this before building the agent stack so an unsafe remote/OAuth
        // configuration cannot leave a partially started process behind.
        SecretCipher.requireForEnabledFeatures()

        val databaseUrl = DatabaseFactory.resolveUrl()
        when (args.firstOrNull()) {
            "backup" -> {
                val output = File(requireOption(args, "--output"))
                val backup = DatabaseBackupService.backup(databaseUrl, output)
                println("Backup created: ${backup.absolutePath}")
                return@coroutineScope
            }

            "restore" -> {
                val input = File(requireOption(args, "--input"))
                val result = DatabaseBackupService.restore(databaseUrl, input)
                println("Database restored: ${result.restoredDatabase.absolutePath}")
                result.previousDatabaseBackup?.let { println("Previous database preserved: ${it.absolutePath}") }
                return@coroutineScope
            }
        }

        val earlyDb = DatabaseFactory.create(databaseUrl)
        if (args.contains("--rotate-secrets")) {
            val cipher = SecretCipher.fromConfig(required = true)!!
            val result = SecretRotationService(earlyDb, cipher).rotate()
            logger.info { "Secret rotation complete: OAuth=${result.oauthConnections}, MCP=${result.mcpServers}" }
            return@coroutineScope
        }
        if (args.take(2) == listOf("owner", "init")) {
            require(args.contains("--password-stdin")) { "owner init requires --password-stdin" }
            require(ConfigProvider.get().getBoolean("REMOTE_ACCESS_ENABLED", false)) {
                "owner init requires REMOTE_ACCESS_ENABLED=true"
            }
            val userIndex = args.indexOf("--user")
            val username = if (userIndex >= 0) args.getOrNull(userIndex + 1).orEmpty() else "owner"
            val password = readlnOrNull()?.trimEnd('\r', '\n').orEmpty()
            require(password.isNotEmpty()) { "No owner password was received on stdin" }
            val cipher = SecretCipher.fromConfig(required = true)!!
            val ttl = ConfigProvider.get().getLong("REMOTE_SESSION_TTL_MINUTES", 60)
            val replaced = OwnerAuthService(earlyDb, ttl, csrfSigner = cipher).configureOwner(username, password, "local-cli")
            logger.info { if (replaced) "Remote owner replaced and sessions revoked" else "Remote owner initialized" }
            return@coroutineScope
        }

        // Seed system agent profiles BEFORE bootstrap (MultiModelRouter needs them)
        ProfileSeeder.seedIfEmpty(earlyDb, credentials)

        // Bootstrap agent stack (LLM, tools, DB, etc.)
        val stack = AgentBootstrap.create(credentials, database = earlyDb)

        // ── Initialize Koin DI container ──
        val port = ConfigProvider.get().getInt("PORT", 8080)
        val koinApp = KoinBootstrap.initFromStack(stack)
        koinApp.modules(
            gatewayModule(
                port = port,
                apiKey = credentials.apiKey,
            ),
        )
        logger.info { "Koin DI initialized" }

        // ── MCP Stdio Mode ──
        if (isMcpStdio) {
            val toolExporter =
                McpToolExporter(
                    secureToolExecutor = stack.actionExecutor,
                    origin = ToolCallOrigin.MCP_STDIO,
                    exposeApprovalRequiredTools = false,
                    taskManager = McpTaskManager(earlyDb, this),
                    roundTripManager = McpRoundTripManager(this),
                )
            val stdioServer = McpStdioServerMode(toolExporter)
            stdioServer.run()
            return@coroutineScope
        }

        // ── Normal Gateway Mode ──

        val gatewaySecurity = GatewaySecurityConfig.fromConfig()
        val authMode = if (gatewaySecurity.remoteAccessEnabled) "Local key + owner sessions" else "Local API key (loopback)"
        val remoteStatus = if (gatewaySecurity.remoteAccessEnabled) "enabled; owner configured locally" else "disabled"
        val mcpToolCount = stack.mcpBridge.listMcpTools().size

        logger.info {
            """
            ╔══════════════════════════════════════════╗
            ║       Promethe Gateway v1.0.0            ║
            ║──────────────────────────────────────────║
            ║  Provider : ${stack.config.provider.padEnd(28)}║
            ║  Model    : ${stack.config.modelName.take(28).padEnd(28)}║
            ║  Auth     : ${authMode.padEnd(28)}║
            ║  Port     : ${port.toString().padEnd(28)}║
            ║  Remote   : ${remoteStatus.take(28).padEnd(28)}║
            ║  MCP Tools: ${mcpToolCount.toString().padEnd(28)}║
            ║  MCP Srv  : /mcp (POST JSON-RPC)        ║
            ║  DI       : Koin 4.2.1                   ║
            ╚══════════════════════════════════════════╝
            """.trimIndent()
        }

        // ── Get gateway from Koin (all deps injected automatically) ──
        val gateway: OmnichannelGateway by inject(OmnichannelGateway::class.java)

        gateway.start()

        logger.info { "Promethe Gateway running on http://${gatewaySecurity.bindHost}:$port" }
        logger.info { "MCP Server available at POST http://localhost:$port/mcp" }
        logger.info { "Press Ctrl+C to stop" }

        // Keep alive
        Thread.currentThread().join()
    }

private fun requireOption(
    args: Array<String>,
    option: String,
): String {
    val index = args.indexOf(option)
    require(index >= 0 && !args.getOrNull(index + 1).isNullOrBlank()) { "Missing required option: $option" }
    return args[index + 1]
}
