package dev.promethe.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.promethe.app.cli.CliRepl
import dev.promethe.core.AgentBootstrap
import dev.promethe.core.CredentialsStore
import dev.promethe.di.KoinBootstrap
import dev.promethe.gateway.OmnichannelGateway
import dev.promethe.gateway.di.gatewayModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.java.KoinJavaComponent.inject
import dev.promethe.core.config.ConfigProvider

/**
 * Unified Prométhé entry point — multi-mode launcher.
 *
 * Usage:
 *   promethe                     → Desktop UI (GUI mode, default)
 *   promethe --cli               → Embedded CLI (gateway + REPL in same process)
 *   promethe --connect <url>     → CLI client connecting to an existing gateway
 *   promethe --daemon            → Headless server (gateway only, no UI, no REPL)
 *   --allow-local-exec           → Legacy alias selecting the native local sandbox
 */
fun main(args: Array<String>) {
    val mode = parseMode(args)

    // Legacy compatibility flag: "local" now means the native sandbox and never bypasses isolation.
    // Registered as a ConfigProvider override — the highest-priority config source —
    // so it wins over credentials.json and env vars wherever EXEC_BACKEND is read.
    val allowLocalExec = "--allow-local-exec" in args ||
        System.getenv("ALLOW_LOCAL_EXEC")?.lowercase() in listOf("true", "1", "yes")
    if (allowLocalExec) {
        dev.promethe.core.config.ConfigProvider.setOverride("EXEC_BACKEND", "local")
    }

    when (mode) {
        LaunchMode.Desktop -> launchDesktop()
        LaunchMode.Daemon -> launchDaemon()
        is LaunchMode.Cli -> launchCli()
        is LaunchMode.Connect -> launchConnect(mode.url)
    }
}

// ── Mode detection ──────────────────────────────────────────

private sealed interface LaunchMode {
    data object Desktop : LaunchMode

    data object Daemon : LaunchMode

    data class Cli(
        val port: Int = 8080,
    ) : LaunchMode

    data class Connect(
        val url: String,
    ) : LaunchMode
}

private fun parseMode(args: Array<String>): LaunchMode {
    if (args.isEmpty()) return LaunchMode.Desktop

    return when {
        "--daemon" in args -> {
            LaunchMode.Daemon
        }

        "--cli" in args -> {
            val portIndex = args.indexOf("--port")
            val port = if (portIndex >= 0 && portIndex + 1 < args.size) {
                args[portIndex + 1].toIntOrNull() ?: 8080
            } else {
                8080
            }
            LaunchMode.Cli(port)
        }

        "--connect" in args -> {
            val urlIndex = args.indexOf("--connect")
            val url = if (urlIndex + 1 < args.size) args[urlIndex + 1] else "http://localhost:8080"
            LaunchMode.Connect(url)
        }

        else -> {
            LaunchMode.Desktop
        }
    }
}

// ── Desktop Mode (GUI) ──────────────────────────────────────

private fun launchDesktop() {
    var credentials = CredentialsStore.loadOrCreate()
    val isFirstRun = !CredentialsStore.isConfigured()
    val isLocalMode = credentials.gatewayUrl.contains("localhost") || credentials.gatewayUrl.contains("127.0.0.1")

    var gateway: OmnichannelGateway? = null
    val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun startGateway(creds: CredentialsStore.Credentials) {
        if (!isLocalMode) return
        appScope.launch {
            try {
                // Initialize ConfigProvider from credentials (same as standalone gateway)
                dev.promethe.core.config.ConfigProvider.initialize(
                    dev.promethe.core.config.MergedConfigProvider { dev.promethe.core.CredentialsStore.load()?.toEnvMap() ?: emptyMap() },
                )
                val stack = AgentBootstrap.create(creds)
                val port = ConfigProvider.get().getInt("PORT", 8080)

                // ── Initialize Koin DI container ──
                val koinApp = KoinBootstrap.initFromStack(stack)
                koinApp.modules(
                    gatewayModule(
                        port = port,
                        apiKey = creds.apiKey,
                    ),
                )

                // Get gateway from Koin (all deps injected)
                val gw: OmnichannelGateway by inject(OmnichannelGateway::class.java)
                gateway = gw

                // Seed and align default profile with credentials
                dev.promethe.gateway.ProfileSeeder.seedIfEmpty(stack.database, creds)

                gw.start()
                println("[Desktop] Embedded gateway started on port $port")
            } catch (e: Exception) {
                System.err.println("[Desktop] Failed to start embedded gateway: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // Start gateway immediately if already configured
    if (!isFirstRun) {
        startGateway(credentials)
        Thread.sleep(2000)
    }

    application {
        Window(
            onCloseRequest = {
                gateway?.stop()
                exitApplication()
            },
            title = "Promethe",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            App(
                apiKey = credentials.apiKey,
                isFirstRun = isFirstRun,
                initialRoute = dev.promethe.app.navigation.PrometheRoute.Sessions,
                onSetupComplete = { provider, apiKey, model, localUrl, contextFiles, remUser, remPass ->
                    val newCreds =
                        CredentialsStore.update { current ->
                            current.copy(
                                apiKey = current.apiKey.ifBlank { credentials.apiKey },
                                llmProvider = provider,
                                llmApiKey = apiKey,
                                llmModel = model,
                                gatewayUrl = current.gatewayUrl,
                                ollamaUrl =
                                    when (provider) {
                                        "ollama" -> localUrl.ifBlank { "http://localhost:11434" }
                                        "litellm" -> localUrl.ifBlank { "http://localhost:4000" }
                                        else -> current.ollamaUrl
                                    },
                            )
                        }
                    credentials = newCreds

                    // Boot gateway after setup
                    startGateway(newCreds)

                    // Owner credentials never enter credentials.json. When remote
                    // access is explicitly enabled, configure the owner through
                    // the loopback-only gateway endpoint after startup.
                    if (remUser.isNotBlank() && remPass.isNotBlank()) {
                        appScope.launch {
                            kotlinx.coroutines.delay(3000)
                            try {
                                dev.promethe.app.network.PrometheClient(apiKey = newCreds.apiKey)
                                    .setupRemoteOwner(remUser, remPass)
                            } catch (error: Exception) {
                                System.err.println("[Desktop] Remote owner was not configured: ${error.message}")
                            }
                        }
                    }

                    // Push context files to gateway after it starts
                    if (contextFiles.isNotEmpty()) {
                        appScope.launch {
                            // Wait for gateway to be ready
                            kotlinx.coroutines.delay(3000)
                            val client =
                                dev.promethe.app.network
                                    .PrometheClient(apiKey = newCreds.apiKey)
                            for ((name, content) in contextFiles) {
                                try {
                                    val body = kotlinx.serialization.json.buildJsonObject {
                                        put("content", kotlinx.serialization.json.JsonPrimitive(content))
                                    }.toString()
                                    client.putJson(
                                        "/api/v1/context/files/$name",
                                        body,
                                    )
                                    println("[Desktop] Saved context file: $name")
                                } catch (e: Exception) {
                                    System.err.println("[Desktop] Failed to save context file $name: ${e.message}")
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

// ── Daemon Mode (headless server) ───────────────────────────

private fun launchDaemon() {
    val credentials = CredentialsStore.loadOrCreate()
    if (!CredentialsStore.isConfigured()) {
        System.err.println("[Daemon] Not configured. Run 'promethe' (desktop) or 'promethe --cli' first.")
        System.exit(1)
    }

    println("[Daemon] Starting Prométhé in headless server mode...")

    // Same config resolution as desktop/standalone gateway: credentials.json > env.
    dev.promethe.core.config.ConfigProvider.initialize(
        dev.promethe.core.config.MergedConfigProvider { dev.promethe.core.CredentialsStore.load()?.toEnvMap() ?: emptyMap() },
    )

    val stack = runBlocking { AgentBootstrap.create(credentials) }
    val port = ConfigProvider.get().getInt("PORT", 8080)

    val koinApp = KoinBootstrap.initFromStack(stack)
    koinApp.modules(
        gatewayModule(
            port = port,
            apiKey = credentials.apiKey,
        ),
    )

    val gw: OmnichannelGateway by inject(OmnichannelGateway::class.java)
    runBlocking { dev.promethe.gateway.ProfileSeeder.seedIfEmpty(stack.database, credentials) }

    gw.start()
    println("[Daemon] Gateway running on port $port (Ctrl+C to stop)")

    // Block main thread
    Thread.currentThread().join()
}

// ── CLI Mode (embedded gateway + REPL) ──────────────────────

private fun launchCli() {
    val credentials = CredentialsStore.loadOrCreate()
    if (!CredentialsStore.isConfigured()) {
        System.err.println("[CLI] Not configured. Run 'promethe' (desktop) first to set up credentials.")
        System.exit(1)
    }

    println("[CLI] Starting Prométhé in embedded CLI mode...")

    // Same config resolution as desktop/standalone gateway: credentials.json > env.
    dev.promethe.core.config.ConfigProvider.initialize(
        dev.promethe.core.config.MergedConfigProvider { dev.promethe.core.CredentialsStore.load()?.toEnvMap() ?: emptyMap() },
    )

    val stack = runBlocking { AgentBootstrap.create(credentials) }
    val port = ConfigProvider.get().getInt("PORT", 8080)

    val koinApp = KoinBootstrap.initFromStack(stack)
    koinApp.modules(
        gatewayModule(
            port = port,
            apiKey = credentials.apiKey,
        ),
    )

    val gw: OmnichannelGateway by inject(OmnichannelGateway::class.java)
    runBlocking { dev.promethe.gateway.ProfileSeeder.seedIfEmpty(stack.database, credentials) }

    gw.start()
    println("[CLI] Embedded gateway running on port $port")

    // Launch CLI REPL connected to local gateway
    val repl = CliRepl(
        gatewayUrl = "http://localhost:$port",
        credential = credentials.apiKey,
    )
    repl.run()

    gw.stop()
}

// ── Connect Mode (remote CLI client) ────────────────────────

private fun launchConnect(
    url: String,
) {
    println("[Connect] Connecting to existing gateway at $url...")

    val console = System.console()
    val username = console?.readLine("Owner username: ") ?: run {
        print("Owner username: ")
        readLine().orEmpty()
    }
    val password = console?.readPassword("Owner password: ")?.concatToString() ?: run {
        print("Owner password: ")
        readLine().orEmpty()
    }
    require(username.isNotBlank() && password.isNotBlank()) { "Owner credentials are required for --connect" }
    val session =
        runBlocking {
            dev.promethe.app.network.PrometheClient().login(url.trimEnd('/'), username.trim(), password)
        }
    val token = session.accessToken ?: error("Gateway did not return a native session token")

    val repl = CliRepl(
        gatewayUrl = url,
        credential = token,
    )
    repl.run()
}
