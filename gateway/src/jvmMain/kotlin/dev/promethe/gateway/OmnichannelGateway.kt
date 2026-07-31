package dev.promethe.gateway

import dev.promethe.api.*
import dev.promethe.core.*
import dev.promethe.core.MemoryLayer
import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.gateway.mcp.mcpServerRoutes
import dev.promethe.gateway.voice.AudioSessionManager
import dev.promethe.gateway.voice.VoiceProviderRegistry
import dev.promethe.gateway.providers.routes.installProviderCatalogRoutes
import dev.promethe.gateway.providers.DefaultProviderCatalogSource
import dev.promethe.gateway.voice.ttsRoutes
import dev.promethe.gateway.voice.voiceConfigRoutes
import dev.promethe.gateway.voice.voiceRoutes
import dev.promethe.gateway.auth.oauthRoutes
import dev.promethe.gateway.auth.OwnerAuthService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import io.ktor.server.sse.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import okio.Path.Companion.toPath

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

class OmnichannelGateway(
    private val agent: AIAgent,
    private val database: PrometheDatabaseApi,
    private val llmAdapter: KoogLlmAdapter,
    private val feedbackCollector: FeedbackCollector,
    private val mcpBridge: McpBridge,
    private val memoryLayer: MemoryLayer,
    private val taskScheduler: dev.promethe.core.TaskScheduler? = null,
    private val hookManager: dev.promethe.core.hooks.HookManager? = null,
    private val pluginLoader: dev.promethe.core.PluginLoader? = null,
    private val orchestrator: dev.promethe.core.AgentOrchestrator? = null,
    private val config: AgentConfig? = null,
    private val approvalGate: ToolApprovalGate? = null,
    private val skillLoader: dev.promethe.core.SkillLoader? = null,
    private val skillWriter: dev.promethe.core.SkillWriter? = null,
    private val skillCurator: dev.promethe.core.SkillCurator? = null,
    private val skillsDir: okio.Path? = null,
    private val port: Int = 8080,
    private val apiKey: String = "",
    private val securityConfig: GatewaySecurityConfig,
    private val ownerAuthService: OwnerAuthService,
    private val actionExecutor: dev.promethe.core.ActionExecutor,
    private val sandboxManager: dev.promethe.core.sandbox.SandboxManager,
    private val sandboxRuntimePolicy: dev.promethe.core.sandbox.SandboxRuntimePolicy,
    private val oauthManager: dev.promethe.gateway.auth.OAuthManager? = null,
) {
    private var server: EmbeddedServer<*, *>? = null
    private val startTime = System.currentTimeMillis()

    fun start() {
        // Restore LLM stats from SQLite before serving
        kotlinx.coroutines.runBlocking { llmAdapter.restoreStats() }

        // ── Ensure skills directory exists + seed system skills ──
        val effectiveSkillsDir = skillsDir ?: dev.promethe.core.PrometheHome.skillsDir.absolutePath.replace("/", java.io.File.separator).toPath()
        val fs = okio.FileSystem.SYSTEM
        if (!fs.exists(effectiveSkillsDir)) {
            fs.createDirectories(effectiveSkillsDir)
        }
        SkillSeeder.seed(effectiveSkillsDir, fs)

        val effectiveSkillLoader = skillLoader ?: dev.promethe.core.SkillLoader(fs, effectiveSkillsDir)
        val effectiveSkillWriter = skillWriter ?: dev.promethe.core.SkillWriter(fs, effectiveSkillsDir)
        val effectiveSkillCurator =
            skillCurator ?: dev.promethe.core.SkillCurator(effectiveSkillLoader, effectiveSkillWriter, llmAdapter, config ?: AgentConfig())
        val gepaJobManager = GepaJobManager(
            agent = agent,
            llmAdapter = llmAdapter,
            config = config,
            skillLoader = effectiveSkillLoader,
            skillWriter = effectiveSkillWriter,
            database = database,
        )
        val agentExecutionService = AgentExecutionService(agent, database)
        val providerCatalogSource = DefaultProviderCatalogSource.instance

        server =
            embeddedServer(CIO, host = securityConfig.bindHost, port = port) {
                install(SSE)
                install(WebSockets)

                installGatewayCorsPolicy(securityConfig)
//                install(StatusPages) {
//                    exception<Throwable> { call, cause ->
//                        call.respond(
//                            HttpStatusCode.InternalServerError,
//                            mapOf("error" to (cause.message ?: "Unknown error")),
//                        )
//                    }
//                }
                AuthMiddleware.install(this, apiKey, securityConfig, ownerAuthService)

                // Rate limiter — 60 requests/min per IP
                RateLimiter.install(this)

                routing {
                    installGatewayContentNegotiation()

                    route("/") {
                        // ── Agent Card Discovery (standard well-known paths) ──
                        route(".well-known") {
                            // A2A standard discovery
                            get("agent.json") {
                                val agentCard = PrometheA2A.buildAgentCard(
                                    "http://localhost:$port",
                                    skillLoader,
                                    database,
                                )
                                call.respond(agentCard)
                            }
                        }

                        // ── OpenAI-Compatible API ──
                        openAiCompatRoutes(agentExecutionService, llmAdapter)

                        // ── MCP Server (expose Promethe tools via MCP protocol) ──
                        mcpServerRoutes(
                            allowedOrigins = securityConfig.allowedOrigins.map { it.value }.toSet(),
                            secureToolExecutor = actionExecutor,
                        )

                        capabilityRoutes(providerCatalogSource)
                        installProviderCatalogRoutes(providerCatalogSource)
                        openApiRoutes()

                        // ── Shared voice registry (used by REST config + WS routes) ──
                        val voiceRegistry = VoiceProviderRegistry(database)

                        // ── A2A Internal Client (shared by goal routes, webhooks, etc.) ──
                        val a2aClient = A2AInternalClient(agentExecutionService)

                        // ACP is a protocol surface and stays at /.well-known and /acp.
                        acpRoutes(a2aClient)

                        // ── Task Executor (runs scheduled tasks through A2A pipeline) ──
                        val taskExecutor = TaskExecutor(database, a2aClient)
                        if (taskScheduler != null) {
                            taskExecutor.start()
                            logger.info { "Task executor started (A2A pipeline)" }
                        }

                        route("api") {
                            route("v1") {
                                // ===================== Extracted Route Modules =====================
                                sessionRoutes(database)

                                agentProfileRoutes(database)
                                memoryRoutes(memoryLayer)
                                contextRoutes()

                                // ── Scheduler Routes ──
                                if (taskScheduler != null) {
                                    schedulerRoutes(database, taskScheduler)
                                }

                                // ── Webhook Routes ──
                                webhookRoutes(
                                    database,
                                    dev.promethe.core.WebhookManager(
                                        database,
                                        agentExecutionService,
                                        io.ktor.client.HttpClient(),
                                    ),
                                )

                                // ── Channel Management Routes ──
                                channelRoutes()

                                // ── Config Env Routes ──
                                configEnvRoutes()

                                // ── Plugins Routes ──
                                if (pluginLoader != null) {
                                    pluginRoutes(pluginLoader)
                                }

                                // ── RAG Routes ──
                                ragRoutes()

                                // ── Skills Routes (always mounted) ──
                                skillRoutes(
                                    skillLoader = effectiveSkillLoader,
                                    skillWriter = effectiveSkillWriter,
                                    skillCurator = effectiveSkillCurator,
                                    fs = okio.FileSystem.SYSTEM,
                                    skillsDir = effectiveSkillsDir,
                                )

                                // ── Orchestrator Routes ──
                                orchestratorRoutes(orchestrator)

                                // ── Goal / Mode Autonome ──
                                goalRoutes(a2aClient)

                                // ── Status Dashboard ──
                                statusRoutes(
                                    llmAdapter = llmAdapter,
                                    memoryLayer = memoryLayer,
                                    hookManager = hookManager,
                                    taskScheduler = taskScheduler,
                                    taskExecutor = taskExecutor,
                                    pluginLoader = pluginLoader,
                                    config = config,
                                    database = database,
                                    providerCatalogSource = providerCatalogSource,
                                )

                                // ── Voice Config Routes (REST) ──
                                voiceConfigRoutes(voiceRegistry)

                                // ── TTS Synthesis Routes (REST) ──
                                ttsRoutes()

                                // ── Checkpoint & Rollback ──
                                get("/sessions/{id}/checkpoint") {
                                    val sessionId =
                                        call.parameters["id"]
                                            ?: return@get call.respond(HttpStatusCode.BadRequest)
                                    val checkpoint = database.getLatestCheckpoint(sessionId)
                                    if (checkpoint != null) {
                                        call.respondText(checkpoint, ContentType.Application.Json)
                                    } else {
                                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No checkpoint found"))
                                    }
                                }

                                post("/sessions/{id}/rollback") {
                                    val sessionId =
                                        call.parameters["id"]
                                            ?: return@post call.respond(HttpStatusCode.BadRequest)
                                    val checkpoint = database.getLatestCheckpoint(sessionId)
                                    if (checkpoint != null) {
                                        call.respond(
                                            mapOf(
                                                "status" to "rollback_ready",
                                                "checkpoint" to Json.parseToJsonElement(checkpoint),
                                            ),
                                        )
                                    } else {
                                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "No checkpoint to rollback to"))
                                    }
                                }

                                delete("/sessions/{id}/checkpoints") {
                                    val sessionId =
                                        call.parameters["id"]
                                            ?: return@delete call.respond(HttpStatusCode.BadRequest)
                                    database.clearCheckpoints(sessionId)
                                    call.respond(mapOf("status" to "cleared"))
                                }

                                feedbackRoutes(feedbackCollector, llmAdapter, agent, gepaJobManager)

                                mcpManagementRoutes(mcpBridge, database, dev.promethe.core.security.SecretCipher.fromConfig())

                                approvalRoutes(approvalGate)
                                sandboxSecurityRoutes(sandboxManager, sandboxRuntimePolicy)
                            }
                        }

                        // ── Voice WebSocket Routes (outside api/ — WS at root routing level) ──
                        val voiceSessionManager = AudioSessionManager()
                        voiceRoutes(
                            sessionManager = voiceSessionManager,
                            registry = voiceRegistry,
                            database = database,
                            executionService = agentExecutionService,
                            memoryLayer = memoryLayer,
                        )

                        // ===================== Webhooks =====================

                        channelWebhookRoutes(a2aClient)

                        systemRoutes(startTime)
                        authRoutes(ownerAuthService, securityConfig)

                        // ── OAuth Routes — mounted only when an OAuthManager was injected
                        // (GatewayModule builds one when OAUTH_ENABLED=true and at least
                        // one OAUTH_<PROVIDER>_CLIENT_ID/_SECRET pair is configured) ──
                        if (oauthManager != null) {
                            oauthRoutes(oauthManager)
                        }
                    }
                    route("agents") {
                        // ── A2A Protocol ──
                        PrometheA2A.installRoutes(
                            this,
                            agentExecutionService,
                            database,
                            "http://localhost:$port",
                            skillLoader,
                        )
                    }

                    // ── WebSocket: real-time agent events for Monitor screen ──
                    webSocket("/ws/agents") {
                        logger.info { "Monitor WebSocket client connected" }
                        try {
                            AgentEventBus.events.collect { event ->
                                val json = kotlinx.serialization.json.Json.encodeToString(
                                    AgentExecutionEvent.serializer(),
                                    event,
                                )
                                send(Frame.Text(json))
                            }
                        } catch (e: Exception) {
                            logger.debug(e) { "Monitor WebSocket client disconnected" }
                        }
                    }

                    // ── WASM Web UI static serving ──
                    val wasmDir =
                        java.io.File(
                            System.getenv("PROMETHE_WEB_DIR")
                                ?.takeIf { it.isNotBlank() }
                                ?: "composeApp/build/dist/wasmJs/productionExecutable",
                        ).canonicalFile
                    if (wasmDir.exists() && wasmDir.isDirectory) {
                        staticFiles("/", wasmDir) {
                            default("index.html")
                        }
                        logger.info { "Web UI serving from ${wasmDir.absolutePath}" }
                    } else {
                        logger.warn { "Web UI assets are unavailable at ${wasmDir.absolutePath}" }
                    }
                }
            }.start(wait = false)
        logger.info { "Promethe Gateway started on ${securityConfig.bindHost}:$port — REST + WebSocket ready" }
    }

    fun stop() {
        server?.stop(1000, 2000)
        logger.info { "Promethe Gateway stopped" }
    }

    /**
     * Auto-generates a short title for a session after its first user message.
     * Runs in background — does not block the response.
     */
    private suspend fun autoGenerateTitle(
        sessionId: String,
        userMessage: String,
    ) {
        try {
            val messages = database.getMessagesForSession(sessionId)
            // Only generate title on the first exchange (user message just sent)
            if (messages.size <= 2) {
                val sessions = database.getAllSessions()
                val session = sessions.find { it.id == sessionId }
                if (session?.title == null) {
                    // Generate a short title from the user's message
                    val title =
                        userMessage
                            .take(80)
                            .replace("\n", " ")
                            .trim()
                            .let { if (it.length > 50) it.take(50) + "…" else it }
                    database.updateSessionTitle(sessionId, title)
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to auto-generate session title for $sessionId" }
            // Title generation is best-effort, don't fail the request
        }
    }
}
