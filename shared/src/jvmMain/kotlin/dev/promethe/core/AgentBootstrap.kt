package dev.promethe.core

import dev.promethe.api.SandboxApprovalPolicy
import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxNetworkMode
import dev.promethe.core.config.ConfigProvider
import dev.promethe.core.coding.LocalCodingAgentService
import dev.promethe.core.hooks.*
import dev.promethe.core.memory.*
import dev.promethe.core.sandbox.NativeSandboxManager
import dev.promethe.core.sandbox.NativeSandboxProcessLauncher
import dev.promethe.core.sandbox.SandboxRuntimePolicy
import dev.promethe.core.sandbox.SandboxManager
import dev.promethe.core.sandbox.SandboxPolicyFileAccess
import dev.promethe.core.sandbox.SandboxedCommandRunner
import dev.promethe.core.tools.builtin.*
import dev.promethe.db.DatabaseFactory
import dev.promethe.db.PrometheDatabaseApi
import io.ktor.client.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import okio.Path.Companion.toPath

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// Factory to bootstrap the full agent stack from credentials.
// Used by gateway/Main.kt and composeApp/Main.kt to avoid duplicating init logic.
data class AgentStack(
    val agent: AIAgent,
    val database: PrometheDatabaseApi,
    val llmAdapter: KoogLlmAdapter,
    val feedbackCollector: FeedbackCollector,
    val orchestrator: AgentOrchestrator,
    val mcpBridge: McpBridge,
    val config: AgentConfig,
    val httpClient: HttpClient,
    val registry: AgentA2ARegistry,
    val memoryLayer: MemoryLayer,
    val taskScheduler: TaskScheduler,
    val hookManager: HookManager,
    val pluginLoader: PluginLoader? = null,
    val skillCurator: SkillCurator? = null,
    val approvalGate: ToolApprovalGate? = null,
    val skillLoader: SkillLoader? = null,
    val skillWriter: SkillWriter? = null,
    val skillsDir: okio.Path? = null,
    val actionExecutor: ActionExecutor? = null,
    val sandboxManager: SandboxManager,
    val sandboxRuntimePolicy: SandboxRuntimePolicy,
    val sandboxCommandRunner: SandboxedCommandRunner,
    val localCodingAgentService: LocalCodingAgentService,
)

object AgentBootstrap {
    suspend fun create(
        credentials: CredentialsStore.Credentials,
        database: PrometheDatabaseApi? = null,
    ): AgentStack {
        val selection = LlmSelectionResolver.resolve(credentials)
        val resolvedProvider = selection.provider
        val resolvedModel = selection.model

        if (resolvedModel.isNullOrBlank() || resolvedProvider.isNullOrBlank()) {
            error(
                "LLM not configured. Run Prométhé in desktop mode to complete setup, " +
                    "or set LLM_MODEL and LLM_PROVIDER environment variables.",
            )
        }

        val xaiModeStr = ConfigProvider.get().get("XAI_API_MODE", "RESPONSES").uppercase()
        val xaiApiMode = try {
            dev.promethe.api.XaiApiMode.valueOf(xaiModeStr)
        } catch (e: Exception) {
            error("Invalid value for XAI_API_MODE: '$xaiModeStr'. Must be one of: RESPONSES, CHAT_COMPLETIONS")
        }

        val config =
            AgentConfig(
                xaiApiMode = xaiApiMode,
                modelName = resolvedModel,
                provider = resolvedProvider,
                temperature = ConfigProvider.get().getFloat("TEMPERATURE", 0.2f).toDouble(),
                maxTokens = ConfigProvider.get().getInt("MAX_TOKENS", 4096),
                executionBackend = ConfigProvider.get().get("EXEC_BACKEND", "local"),
                executionTimeoutMs = ConfigProvider.get().getLong("EXECUTION_TIMEOUT_MS", 30_000),
                maxOutputBytes = ConfigProvider.get().getInt("MAX_OUTPUT_BYTES", 50_000),
                sandboxBackend = ConfigProvider.get().get("SANDBOX_BACKEND", "auto"),
                sandboxMode =
                    enumConfig(
                        "SANDBOX_MODE",
                        SandboxMode.WORKSPACE_WRITE,
                    ),
                sandboxApprovalPolicy =
                    enumConfig(
                        "SANDBOX_APPROVAL_POLICY",
                        SandboxApprovalPolicy.ON_REQUEST,
                    ),
                sandboxNetworkMode =
                    enumConfig(
                        "SANDBOX_NETWORK_MODE",
                        SandboxNetworkMode.OFF,
                    ),
                sandboxAllowedDomains =
                    ConfigProvider.get()
                        .get("SANDBOX_ALLOWED_DOMAINS", "")
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotEmpty),
                sandboxReadableRoots = parseSandboxRoots(ConfigProvider.get().get("SANDBOX_READABLE_ROOTS", "")),
                sandboxWritableRoots = parseSandboxRoots(ConfigProvider.get().get("SANDBOX_WRITABLE_ROOTS", "")),
                honchoBaseUrl = ConfigProvider.get().get("HONCHO_URL", ""),
                honchoApiKey = ConfigProvider.get().get("HONCHO_API_KEY", ""),
                tracingBackend = ConfigProvider.get().get("TRACING_BACKEND", "console"),
                langfusePublicKey = ConfigProvider.get().get("LANGFUSE_PUBLIC_KEY", ""),
                langfuseSecretKey = ConfigProvider.get().get("LANGFUSE_SECRET_KEY", ""),
                langfuseHost = ConfigProvider.get().get("LANGFUSE_HOST", "https://cloud.langfuse.com"),
                otlpEndpoint = ConfigProvider.get().get("OTLP_ENDPOINT", ""),
                maxContextTokens = ConfigProvider.get().getInt("MAX_CONTEXT_TOKENS", 100_000),
                compressionThreshold = ConfigProvider.get().getFloat("COMPRESSION_THRESHOLD", 0.8f).toDouble(),
                // SSH backend config
                sshHost = ConfigProvider.get().get("SSH_HOST", ""),
                sshUser = ConfigProvider.get().get("SSH_USER", ""),
                sshKeyPath = ConfigProvider.get().get("SSH_KEY_PATH", ""),
                sshPort = ConfigProvider.get().getInt("SSH_PORT", 22),
                // Tool approval gate
                approvalMode = ConfigProvider.get().get("APPROVAL_MODE", "dangerous"),
                approvalTimeoutMs = ConfigProvider.get().getLong("APPROVAL_TIMEOUT_MS", 120_000),
                // GEPA self-evolution
                gepaEnabled = ConfigProvider.get().getBoolean("GEPA_ENABLED", false),
                gepaIntervalMinutes = ConfigProvider.get().getLong("GEPA_INTERVAL_MINUTES", 60),
                gepaAutoApply = ConfigProvider.get().getBoolean("GEPA_AUTO_APPLY", false),
                // Memory provider config (enables Docker compose overlays)
                memoryProvider = ConfigProvider.get().get("MEMORY_PROVIDER", "embedded"),
                tencentMemoryUrl = ConfigProvider.get().get("TENCENT_MEMORY_URL", ""),
                tencentMemoryServiceId = ConfigProvider.get().get("TENCENT_SERVICE_ID", ""),
                tencentMemoryApiKey = ConfigProvider.get().get("TENCENT_MEMORY_API_KEY", ""),
                // LLM fallback chain (experimental, disabled by default)
                fallbackChainEnabled = ConfigProvider.get().getBoolean("FALLBACK_CHAIN_ENABLED", false),
            )

        // Resolve profileDirectory: if empty (default), use ~/.promethe
        val resolvedConfig = if (config.profileDirectory.isBlank()) {
            PrometheHome.ensureDirectories()
            config.copy(profileDirectory = PrometheHome.absolutePath)
        } else {
            config
        }
        require(
            resolvedConfig.sandboxMode != SandboxMode.FULL_ACCESS ||
                !ConfigProvider.get().getBoolean("REMOTE_ACCESS_ENABLED", false),
        ) {
            "SANDBOX_MODE=FULL_ACCESS cannot be used while REMOTE_ACCESS_ENABLED=true"
        }

        // One-shot migration from old relative paths to ~/.promethe
        migrateToPrometheHome()

        val apiKeys = CredentialsStore.resolveApiKeys(credentials)
        val database = database ?: DatabaseFactory.create()

        val httpClient =
            HttpClient {
                install(ContentNegotiation) { json() }
                install(HttpTimeout) {
                    requestTimeoutMillis = 120_000
                    connectTimeoutMillis = 10_000
                }
            }

        val honchoClient =
            resolvedConfig.honchoBaseUrl.takeIf { it.isNotBlank() }?.let {
                HonchoClient(httpClient, it, resolvedConfig.honchoApiKey)
            }

        val llmAdapter = KoogLlmAdapter(resolvedConfig, database)
        TracySetup.initialize(resolvedConfig)
        llmAdapter.initializeWithRouter(apiKeys, database)

        val fs = getFileSystem()
        val profileDir = getProfileDirectoryPath(resolvedConfig)
        val workspaceDirectory =
            java.io
                .File(ConfigProvider.get().get("SANDBOX_WORKSPACE", PrometheHome.workspaceDir.absolutePath))
                .canonicalFile
                .also { directory ->
                    require(directory.mkdirs() || directory.isDirectory) {
                        "Unable to create sandbox workspace: ${directory.path}"
                    }
                }
        val workDir = workspaceDirectory.absolutePath.toPath()
        val sandboxManager = NativeSandboxManager.discover()
        val sandboxRuntimePolicy = SandboxRuntimePolicy(workDir.toString(), resolvedConfig)
        val sandboxFileAccess = SandboxPolicyFileAccess(sandboxRuntimePolicy)
        val sandboxCommandRunner =
            SandboxedCommandRunner(
                launcher = NativeSandboxProcessLauncher(sandboxManager),
                runtimePolicy = sandboxRuntimePolicy,
                workspaceRoot = workDir.toString(),
            )
        LocalProcessSandbox.install(sandboxCommandRunner)
        val sandboxStatus = sandboxManager.selfTest()
        if (sandboxStatus.available && sandboxStatus.selfTestPassed) {
            logger.info { "Sandbox initialized (${sandboxStatus.backend})" }
        } else {
            logger.warn { "Sandbox unavailable; process tools will fail closed: ${sandboxStatus.message}" }
        }
        ToolRegistry.register(
            FileReadTool(
                fs = fs,
                basePath = workDir,
                pathResolver = CanonicalWorkspacePathResolver,
                secureReader = sandboxFileAccess,
            ),
        )
        ToolRegistry.register(
            FileWriteTool(
                fs = fs,
                basePath = workDir,
                pathResolver = CanonicalWorkspacePathResolver,
                secureWriter = sandboxFileAccess,
            ),
        )
        val outboundPolicy = JvmOutboundUrlPolicy()
        ToolRegistry.register(
            HttpFetchTool(
                httpClient = httpClient,
                urlPolicy = outboundPolicy,
                fetcher = PinnedJvmOutboundHttpFetcher(outboundPolicy),
            ),
        )

        val profileManager = ProfileManager(honchoClient)

        // ── Hook Manager ─────────────────────────────────────────
        val hookManager = HookManager()
        // Register built-in hooks
        val guardrailPreset = ConfigProvider.get().get("GUARDRAIL_PRESET", "dev-safe")
        kotlinx.coroutines.runBlocking {
            hookManager.register(LoggingHook())
            hookManager.register(MetricsHook())
            // Guardrail: block dangerous commands via configurable presets
            val preset = GuardrailPresets.resolve(guardrailPreset)
            hookManager.register(
                GuardrailHook(
                    blockedTools = preset.blockedTools,
                    blockedArgPatterns = preset.blockedArgPatterns,
                ),
            )
            logger.info { "Guardrail preset '$guardrailPreset' loaded (${preset.blockedArgPatterns.size} patterns, ${preset.blockedTools.size} blocked tools)" }
            // Optional webhook hook
            val webhookUrl = ConfigProvider.get().get("PROMETHE_WEBHOOK_URL", "")
            if (webhookUrl.isNotBlank()) {
                hookManager.register(WebhookDispatchHook(webhookUrl))
                logger.info { "Webhook hook registered -> $webhookUrl" }
            }
        }
        logger.info { "Hook manager initialized (${kotlinx.coroutines.runBlocking { hookManager.listHooks().size }} hooks)" }

        // ── Tool Approval Gate (must be created BEFORE ActionExecutor) ──
        // The gate is always available: APPROVAL_MODE=auto still permits safe
        // tools automatically, but security-sensitive tools remain mandatory.
        val approvalGate =
            ToolApprovalGate(resolvedConfig).also {
                logger.info { "Tool approval gate initialized (mode=${resolvedConfig.approvalMode})" }
            }

        val actionExecutor =
            ActionExecutor(
                resolvedConfig,
                httpClient,
                hookManager = hookManager,
                approvalGate = approvalGate,
                sandboxCommandExecutor = sandboxCommandRunner,
                toolIntentLedger = PersistentToolIntentLedger(database),
            )

        val localCodingAgentService =
            LocalCodingAgentService(
                workspace = workspaceDirectory.toPath(),
                database = database,
                approvalGate = approvalGate,
            ).also { service ->
                val detected = service.refresh()
                logger.info {
                    "Local coding agents detected: " +
                        detected.joinToString { status ->
                            "${status.kind.id}=${status.available}/${status.authentication}"
                        }
                }
            }

        // ── Memory Provider ────────────────────────────────────
        val embeddedProvider = EmbeddedMemoryProvider(database)
        val memoryProvider: MemoryProvider =
            when (resolvedConfig.memoryProvider) {
                "honcho" -> {
                    if (honchoClient != null) {
                        FallbackMemoryProvider(
                            primary = HonchoMemoryProvider(honchoClient),
                            fallback = embeddedProvider,
                        )
                    } else {
                        logger.warn { "Honcho selected but not configured — falling back to embedded" }
                        embeddedProvider
                    }
                }

                "tencent" -> {
                    if (resolvedConfig.tencentMemoryUrl.isNotBlank()) {
                        FallbackMemoryProvider(
                            primary =
                                TencentMemoryProvider(
                                    httpClient = httpClient,
                                    baseUrl = resolvedConfig.tencentMemoryUrl,
                                    serviceId = resolvedConfig.tencentMemoryServiceId,
                                    apiKey = resolvedConfig.tencentMemoryApiKey,
                                ),
                            fallback = embeddedProvider,
                        )
                    } else {
                        logger.warn { "TencentDB Memory selected but URL not configured — falling back to embedded" }
                        embeddedProvider
                    }
                }

                else -> {
                    embeddedProvider
                }
            }
        val memoryLayer = MemoryLayer(database, llmAdapter, memoryProvider)
        logger.info { "Memory provider: ${memoryProvider.name}" }

        val skillsDir = profileDir / "skills"
        if (!fs.exists(skillsDir)) fs.createDirectories(skillsDir)
        val skillLoader = SkillLoader(fs, skillsDir)
        val skillWriter = SkillWriter(fs, skillsDir)
        val trajectoryEvaluator = TrajectoryEvaluator(llmAdapter, resolvedConfig)
        val feedbackCollector = FeedbackCollector(database)
        val rewardSignal = RewardSignal()

        // ── A2A Registry ────────────────────────────────────────
        val registry = AgentA2ARegistry()

        // Sub-agent orchestrator (with A2A registry for routing)
        val orchestrator =
            AgentOrchestrator(
                llmAdapter = llmAdapter,
                database = database,
                config = resolvedConfig,
                profileManager = profileManager,
                actionExecutor = actionExecutor,
                skillLoader = skillLoader,
                skillWriter = skillWriter,
                trajectoryEvaluator = trajectoryEvaluator,
                registry = registry,
            )

        // Register delegation tools (default session — will be overridden per-session)
        ToolRegistry.register(DelegateTaskTool(orchestrator, parentSessionId = "bootstrap"))
        ToolRegistry.register(GetSubtaskResultTool(orchestrator))
        // Agent management tools — discover, create, promote, cleanup agent profiles
        ToolRegistry.register(ListAgentsTool(database))
        // NOTE: CreateAgentTool registered AFTER A2A bootstrap (needs hot-register callback)
        ToolRegistry.register(PromoteAgentTool(database))
        ToolRegistry.register(CleanupEphemeralAgentsTool(database))
        logger.info { "Delegation & agent management tools registered (5 tools, create_agent deferred)" }

        // ── Memory & Skill Tools (Learning Loop) ─────────────
        ToolRegistry.register(MemorySaveTool(memoryLayer))
        ToolRegistry.register(MemorySearchTool(memoryLayer))
        ToolRegistry.register(MemoryForgetTool(memoryLayer))
        ToolRegistry.register(MemoryListTool(memoryLayer))
        ToolRegistry.register(SkillSearchTool(skillLoader))
        ToolRegistry.register(SkillLoadTool(skillLoader))
        ToolRegistry.register(SkillCreateTool(skillWriter, skillLoader))
        ToolRegistry.register(SkillImproveTool(skillWriter, skillLoader))
        ToolRegistry.register(SkillListTool(skillLoader))
        logger.info { "Learning loop tools registered (4 memory + 5 skill)" }

        // ── Introspection Tools (Self-awareness) ────────────
        ToolRegistry.register(AgentStatusTool(llmAdapter, database))
        ToolRegistry.register(SessionHistoryTool(database))
        ToolRegistry.register(TokenBudgetTool(llmAdapter))
        logger.info { "Introspection tools registered (3 tools)" }

        val resilience = ResilienceStrategy(llmAdapter)
        val contextCompressor = ContextCompressor(llmAdapter, resolvedConfig)
        val agent =
            AIAgent(
                config = resolvedConfig,
                database = database,
                llmAdapter = llmAdapter,
                profileManager = profileManager,
                actionExecutor = actionExecutor,
                skillLoader = skillLoader,
                trajectoryEvaluator = trajectoryEvaluator,
                skillWriter = skillWriter,
                memoryLayer = memoryLayer,
                rewardSignal = rewardSignal,
                resilience = resilience,
                hookManager = hookManager,
                contextCompressor = contextCompressor,
                dryRun = false,
            )
        logger.info { "Context compressor enabled (threshold=${resolvedConfig.compressionThreshold}, max=${resolvedConfig.maxContextTokens} tokens)" }
        // MCP bridge with JVM transport factory + auto-connect
        val mcpBridge = McpBridge()
        mcpBridge.setTransportFactory(JvmMcpTransportFactory())

        // MCP_SERVERS is process-owned; legacy files are imported once into the
        // encrypted database, which is otherwise the UI source of truth.
        val mcpConfigs = dev.promethe.core.mcp.McpConfigLoader.loadConfigs(database, dev.promethe.core.security.SecretCipher.fromConfig())
        if (mcpConfigs.isNotEmpty()) {
            mcpConfigs.forEach { cfg -> mcpBridge.registerServer(cfg) }
            val results = mcpBridge.connectAll()
            val totalTools = results.values.sumOf { it.getOrNull()?.size ?: 0 }
            val connected = results.count { it.value.isSuccess }
            val failed = results.count { it.value.isFailure }
            logger.info { "MCP: $connected server(s) connected, $failed failed, $totalTools tool(s) discovered" }
            results.forEach { (id, result) ->
                result.onSuccess { tools ->
                    logger.info { "  ✅ $id: ${tools.size} tools (${tools.joinToString { it.toolName }})" }
                }
                result.onFailure { err ->
                    logger.warn { "  ❌ $id: ${err.message}" }
                }
            }
        } else {
            logger.info { "MCP: No servers configured (create mcp.json or set MCP_SERVERS env)" }
        }

        // ── Register agents in A2A registry ───────────────────
        val a2aBootstrap =
            AgentA2ABootstrap(
                registry = registry,
                executionService = AgentExecutionService(agent, database),
                database = database,
                toolRegistry = ToolRegistry,
            )
        a2aBootstrap.registerMainAgent()
        a2aBootstrap.registerProfileAgents()

        // Register CreateAgentTool with A2A hot-registration callback
        ToolRegistry.register(
            CreateAgentTool(
                database = database,
                onAgentCreated = { profileRow -> a2aBootstrap.registerSingleAgent(profileRow) },
            ),
        )
        logger.info { "create_agent tool registered with A2A hot-registration ✅" }

        // ── Task Scheduler (CRUD-only; execution tick runs in gateway's TaskExecutor) ──
        val taskScheduler = TaskScheduler(database)
        logger.info { "Task scheduler initialized (CRUD-only, execution in gateway)" }

        // ── Skill Curator (auto-grades and prunes skills daily) ──
        val skillCurator =
            SkillCurator(
                skillLoader = skillLoader,
                skillWriter = skillWriter,
                llmAdapter = llmAdapter,
                config = resolvedConfig,
            )
        logger.info { "Skill curator initialized" }

        // ── GEPA Self-Evolution Engine ──────────────────────────
        val gepaScheduler =
            dev.promethe.core.evolution.GepaScheduler(
                database = database,
                llmAdapter = llmAdapter,
                config = resolvedConfig,
                skillLoader = skillLoader,
                skillWriter = skillWriter,
                autoApply = resolvedConfig.gepaAutoApply,
            )
        if (resolvedConfig.gepaEnabled) {
            gepaScheduler.startPeriodicEvolution(intervalMinutes = resolvedConfig.gepaIntervalMinutes)
            logger.info { "GEPA self-evolution started (every ${resolvedConfig.gepaIntervalMinutes}min, autoApply=${resolvedConfig.gepaAutoApply})" }
        } else {
            logger.info { "GEPA self-evolution disabled (set GEPA_ENABLED=true to activate)" }
        }
        // Register GEPA feedback hook to capture trajectories
        kotlinx.coroutines.runBlocking {
            hookManager.register(dev.promethe.core.hooks.GepaFeedbackHook(gepaScheduler))
        }
        logger.info { "GEPA feedback hook registered" }

        // ── Integration & Extended Tools (extracted to IntegrationRegistrar) ──
        LiveProviderKeys.replace(apiKeys)
        val providerRegistry = dev.promethe.core.providers.ProviderRegistry(LiveProviderKeys)
        val capabilityRouter = dev.promethe.core.providers.CapabilityRouter(providerRegistry)
        IntegrationRegistrar.registerIntegrationTools(
            httpClient,
            workDir,
            LiveProviderKeys,
            capabilityRouter,
            resolvedConfig,
            sandboxCommandRunner,
        )

        val workDirStr = workDir.toString()
        IntegrationRegistrar.registerExtendedTools(
            workDirStr,
            httpClient,
            resolvedConfig,
            database,
            llmAdapter,
            taskScheduler,
            workDir,
            LiveProviderKeys,
            sandboxCommandRunner,
            sandboxFileAccess,
        )

        // ── Auto-Healing Executor ──────────────────────────────
        val autoHealing =
            AutoHealingExecutor(
                delegate = actionExecutor,
                resilience = resilience,
            )
        logger.info { "Auto-healing executor enabled" }

        // ── Plugin Loader ───────────────────────────────────────
        val labPluginsEnabled =
            System.getenv("PROMETHE_ENABLE_LAB_PLUGINS")
                ?.equals("true", ignoreCase = true)
                ?: false
        val pluginLoader =
            PluginLoader(
                pluginsDir = (profileDir / "plugins").toString(),
                runtimeEnabled = labPluginsEnabled,
                commandRunner = sandboxCommandRunner,
                approvalGate = approvalGate,
            )
        val plugins = pluginLoader.discover()
        val enabledPlugins = pluginLoader.getEnabledPlugins()
        if (enabledPlugins.isNotEmpty()) {
            logger.info { "Loaded ${enabledPlugins.size} LAB plugin(s): ${enabledPlugins.map { it.name }}" }
            // Register plugin hooks into the hook manager
            pluginLoader.loadAndRegisterHooks(hookManager)
        } else if (plugins.isNotEmpty()) {
            logger.info {
                "Discovered ${plugins.size} plugin(s), but LAB plugins are disabled. " +
                    "Set PROMETHE_ENABLE_LAB_PLUGINS=true in the local process environment to enable them."
            }
        }

        // ── Hot-Reload Watcher ─────────────────────────────────
        val hotReload =
            HotReloadWatcher.createDefault(
                workDir = profileDir.toString(),
                onContextReload = { files ->
                    logger.info { "Context files updated: ${files.keys}" }
                },
                onEnvReload = { envMap ->
                    logger.info { "Env reloaded: ${envMap.keys.take(5)}..." }
                },
                onPluginChange = { filename ->
                    pluginLoader.discover()
                    logger.info { "Plugins rescanned after: $filename" }
                },
            )
        hotReload.start(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO))
        logger.info { "Hot-reload watcher started" }

        return AgentStack(
            agent = agent,
            database = database,
            llmAdapter = llmAdapter,
            feedbackCollector = feedbackCollector,
            orchestrator = orchestrator,
            mcpBridge = mcpBridge,
            config = resolvedConfig,
            httpClient = httpClient,
            registry = registry,
            memoryLayer = memoryLayer,
            taskScheduler = taskScheduler,
            hookManager = hookManager,
            pluginLoader = pluginLoader,
            skillCurator = skillCurator,
            approvalGate = approvalGate,
            skillLoader = skillLoader,
            skillWriter = skillWriter,
            skillsDir = skillsDir,
            actionExecutor = actionExecutor,
            sandboxManager = sandboxManager,
            sandboxRuntimePolicy = sandboxRuntimePolicy,
            sandboxCommandRunner = sandboxCommandRunner,
            localCodingAgentService = localCodingAgentService,
        )
    }

    private fun parseSandboxRoots(value: String): List<String> =
        if (value.isBlank()) {
            emptyList()
        } else {
            runCatching { PrometheJson.decodeFromString<List<String>>(value) }
                .getOrElse { error("Invalid sandbox roots configuration") }
        }

    /**
     * One-shot migration from old relative paths to ~/.promethe.
     * Copies data if old paths exist and new paths don't.
     */
    private fun migrateToPrometheHome() {
        val home = PrometheHome.dir
        home.mkdirs()

        // Migrate DB: ./data/promethe.db → ~/.promethe/promethe.db
        val oldDb = java.io.File("./data/promethe.db")
        val newDb = PrometheHome.dbFile
        if (oldDb.exists() && !newDb.exists()) {
            oldDb.copyTo(newDb)
            logger.info { "Migrated database: ${oldDb.absolutePath} → ${newDb.absolutePath}" }
        }

        // Migrate skills: ./profiles/developer/skills/ → ~/.promethe/skills/
        val oldSkills = java.io.File("./profiles/developer/skills")
        val newSkills = PrometheHome.skillsDir
        if (oldSkills.exists() && oldSkills.isDirectory && (!newSkills.exists() || newSkills.list()?.isEmpty() == true)) {
            newSkills.mkdirs()
            oldSkills.copyRecursively(newSkills, overwrite = false)
            logger.info { "Migrated skills: ${oldSkills.absolutePath} → ${newSkills.absolutePath}" }
        }

        // Migrate plugins: ./profiles/developer/plugins/ → ~/.promethe/plugins/
        val oldPlugins = java.io.File("./profiles/developer/plugins")
        val newPlugins = PrometheHome.pluginsDir
        if (oldPlugins.exists() && oldPlugins.isDirectory && (!newPlugins.exists() || newPlugins.list()?.isEmpty() == true)) {
            newPlugins.mkdirs()
            oldPlugins.copyRecursively(newPlugins, overwrite = false)
            logger.info { "Migrated plugins: ${oldPlugins.absolutePath} → ${newPlugins.absolutePath}" }
        }
    }
}

private inline fun <reified T : Enum<T>> enumConfig(
    key: String,
    default: T,
): T {
    val raw = ConfigProvider.get().get(key, default.name).trim().uppercase()
    return enumValues<T>().firstOrNull { it.name == raw }
        ?: error("Invalid value for $key: '$raw'. Must be one of: ${enumValues<T>().joinToString { it.name }}")
}
