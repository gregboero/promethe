package dev.promethe.core

import dev.promethe.core.config.ConfigProvider
import dev.promethe.core.sandbox.SandboxedCommandRunner
import dev.promethe.core.tools.builtin.*
import dev.promethe.db.PrometheDatabaseApi
import io.ktor.client.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * IntegrationRegistrar — extracted from AgentBootstrap to reduce god-class size.
 *
 * Handles registration of:
 *   1. Integration tools (conditional on config): GitHub, Email, Calendar, Notion, Jira, Twilio, Browser
 *   2. Extended tools (Phase 2): Filesystem, Git, System, Web, Data, Security, Comm, AI, RAG, Agent-level
 */
object IntegrationRegistrar {
    /**
     * Register conditional integration tools based on configured API keys/URLs.
     * These tools are only registered when their credentials are present.
     */
    suspend fun registerIntegrationTools(
        httpClient: HttpClient,
        workDir: okio.Path,
        apiKeys: Map<String, String>,
        capabilityRouter: dev.promethe.core.providers.CapabilityRouter,
        config: AgentConfig? = null,
        sandboxCommandRunner: SandboxedCommandRunner,
    ) {
        // ── Media Tools (always registered — router handles missing providers) ──
        ToolRegistry.register(ImageGenerationTool(httpClient, apiKeys, capabilityRouter))
        ToolRegistry.register(VisionTool(httpClient, apiKeys, capabilityRouter))
        ToolRegistry.register(TextToSpeechTool(httpClient))
        ToolRegistry.register(
            dev.promethe.core.tools.media.VideoGenerateTool(
                httpClient,
                apiKeys,
                capabilityRouter,
                outputDir = (workDir / "video").toString(),
            ),
        )
        ToolRegistry.register(dev.promethe.core.tools.media.VideoAnalyzeTool(httpClient, apiKeys, capabilityRouter))
        logger.info { "Media tools registered (multi-provider via CapabilityRouter)" }

        // ── Integration Tools (conditional on config keys) ──

        // GitHub
        val githubToken = ConfigProvider.get().get("GITHUB_TOKEN", "")
        if (githubToken.isNotBlank() || ConfigProvider.get().getBoolean("OAUTH_ENABLED", false)) {
            ToolRegistry.register(GitHubTool(httpClient, githubToken) { OAuthTokenRegistry.get("github") })
            logger.info { "GitHub tool registered" }
        }

        // Email (HTTP API — Resend, SendGrid, or Mailgun)
        val emailApiKey = ConfigProvider.get().get("EMAIL_API_KEY", "")
        val emailFrom = ConfigProvider.get().get("EMAIL_FROM", "")
        if (emailApiKey.isNotBlank() && emailFrom.isNotBlank()) {
            ToolRegistry.register(
                EmailTool(
                    httpClient = httpClient,
                    apiKey = emailApiKey,
                    fromAddress = emailFrom,
                    provider = ConfigProvider.get().get("EMAIL_PROVIDER", "resend"),
                ),
            )
            logger.info { "Email tool registered (${ConfigProvider.get().get("EMAIL_PROVIDER", "resend")})" }
        }

        // Google Calendar
        val calendarToken = ConfigProvider.get().get("GOOGLE_CALENDAR_TOKEN", "")
        if (calendarToken.isNotBlank() || ConfigProvider.get().getBoolean("OAUTH_ENABLED", false)) {
            ToolRegistry.register(CalendarTool(httpClient, calendarToken) { OAuthTokenRegistry.get("google") })
            logger.info { "Calendar tool registered" }
        }

        // Notion
        val notionKey = ConfigProvider.get().get("NOTION_API_KEY", "")
        if (notionKey.isNotBlank()) {
            ToolRegistry.register(NotionTool(httpClient, notionKey))
            logger.info { "Notion tool registered" }
        }

        // Jira
        val jiraUrl = ConfigProvider.get().get("JIRA_URL", "")
        val jiraEmail = ConfigProvider.get().get("JIRA_EMAIL", "")
        val jiraToken = ConfigProvider.get().get("JIRA_API_TOKEN", "")
        if (jiraUrl.isNotBlank() && jiraToken.isNotBlank()) {
            ToolRegistry.register(JiraTool(httpClient, jiraUrl, jiraEmail, jiraToken))
            logger.info { "Jira tool registered ($jiraUrl)" }
        }

        // Web Scraper (always available — no API key needed)
        ToolRegistry.register(WebScraperTool(PinnedJvmOutboundHttpFetcher(JvmOutboundUrlPolicy())))

        if (ConfigProvider.get().getBoolean("CODE_EXECUTION_ENABLED", false)) {
            ToolRegistry.register(
                CodeExecutionTool(
                    workDir = workDir.toString(),
                    config = config,
                    commandRunner = sandboxCommandRunner,
                    workspaceFileWriter = SecureJvmWorkspaceFileWriter(workDir.toString()),
                ),
            )
            logger.info { "Code execution tool registered (BETA, approval required)" }
        } else {
            logger.info { "Code execution tool disabled (set CODE_EXECUTION_ENABLED=true to opt in)" }
        }
        logger.info { "Web scraper tool registered" }

        // Twilio SMS/WhatsApp
        val twilioSid = ConfigProvider.get().get("TWILIO_ACCOUNT_SID", "")
        val twilioToken = ConfigProvider.get().get("TWILIO_AUTH_TOKEN", "")
        val twilioPhone = ConfigProvider.get().get("TWILIO_PHONE_NUMBER", "")
        if (twilioSid.isNotBlank() && twilioToken.isNotBlank()) {
            ToolRegistry.register(TwilioTool(httpClient, twilioSid, twilioToken, twilioPhone))
            logger.info { "Twilio tool registered (SMS + WhatsApp)" }
        }

        // Browser Automation — supports CDP (local) and Browserbase (cloud)
        val browserBackendType = ConfigProvider.get().get("BROWSER_BACKEND", "")
        val browserBackend: dev.promethe.core.browser.BrowserBackend? =
            when {
                browserBackendType == "browserbase" -> {
                    val bbApiKey = ConfigProvider.get().get("BROWSERBASE_API_KEY", "")
                    val bbProjectId = ConfigProvider.get().get("BROWSERBASE_PROJECT_ID", "")
                    if (bbApiKey.isNotBlank() && bbProjectId.isNotBlank()) {
                        dev.promethe.core.browser.BrowserbaseBrowserBackend(httpClient, bbApiKey, bbProjectId).also {
                            logger.info { "Browser backend: Browserbase (cloud)" }
                        }
                    } else {
                        null
                    }
                }

                else -> {
                    val cdpPort = ConfigProvider.get().get("BROWSER_CDP_PORT", "")
                    if (cdpPort.isNotBlank()) {
                        val cdpHost = ConfigProvider.get().get("BROWSER_CDP_HOST", "localhost")
                        dev.promethe.core.browser.CdpBrowserBackend(httpClient, cdpHost, cdpPort.toIntOrNull() ?: 9222).also {
                            logger.info { "Browser backend: CDP @ $cdpHost:$cdpPort" }
                        }
                    } else {
                        null
                    }
                }
            }
        browserBackend?.let { backend ->
            BrowserTools.create(backend).forEach { tool -> ToolRegistry.register(tool) }
            logger.info { "Browser tools registered (12 tools)" }
        }

        logger.info { "Integration tools registration complete ✅" }
    }

    // ══════════════════════════════════════════════════════════════
    // Phase 2: Extended tools — extracted from AgentBootstrap to stay
    // under JVM's 64KB method size limit
    // ══════════════════════════════════════════════════════════════
    @Suppress("LongParameterList")
    suspend fun registerExtendedTools(
        workDirStr: String,
        httpClient: HttpClient,
        config: AgentConfig,
        database: PrometheDatabaseApi,
        llmAdapter: KoogLlmAdapter,
        taskScheduler: TaskScheduler,
        workDir: okio.Path,
        apiKeys: Map<String, String>,
        sandboxCommandRunner: SandboxedCommandRunner,
    ) {
        // ── Provider Registry & Capability Router ─────────────────
        val providerRegistry = dev.promethe.core.providers.ProviderRegistry(LiveProviderKeys)
        val capabilityRouter = dev.promethe.core.providers.CapabilityRouter(providerRegistry)

        // ── Filesystem Tools (6) ──────────────────────────────────
        val secureReader = SecureJvmWorkspaceFileReader(workDirStr)
        val secureWriter = SecureJvmWorkspaceFileWriter(workDirStr)
        val secureMutator = SecureJvmWorkspaceFileMutator(workDirStr)
        ToolRegistry.register(dev.promethe.core.tools.fs.FileDeleteTool(workDirStr, secureMutator))
        ToolRegistry.register(dev.promethe.core.tools.fs.FileMoveTool(workDirStr, secureMutator))
        ToolRegistry.register(dev.promethe.core.tools.fs.DirectoryTreeTool(workDirStr))
        ToolRegistry.register(dev.promethe.core.tools.fs.FileSearchTool(workDirStr))
        ToolRegistry.register(dev.promethe.core.tools.fs.CodeGrepTool(workDirStr))
        ToolRegistry.register(dev.promethe.core.tools.fs.PatchTool(workDirStr, secureReader, secureWriter))
        logger.info { "Filesystem tools registered (6 tools)" }

        // ── Git Tools (5) ─────────────────────────────────────────
        ToolRegistry.register(dev.promethe.core.tools.git.GitStatusTool(workDirStr, sandboxCommandRunner))
        ToolRegistry.register(dev.promethe.core.tools.git.GitDiffTool(workDirStr, sandboxCommandRunner))
        ToolRegistry.register(dev.promethe.core.tools.git.GitCommitTool(workDirStr, sandboxCommandRunner))
        ToolRegistry.register(dev.promethe.core.tools.git.GitLogTool(workDirStr, sandboxCommandRunner))
        ToolRegistry.register(dev.promethe.core.tools.git.GitBranchTool(workDirStr, sandboxCommandRunner))
        logger.info { "Git tools registered (5 tools)" }

        // ── System Tools (safe defaults; execution tools are explicit opt-ins) ──
        ToolRegistry.register(dev.promethe.core.tools.sys.ShellTool(workDirStr, sandboxCommandRunner))
        if (ConfigProvider.get().getBoolean("PROCESS_TOOL_ENABLED", false)) {
            ToolRegistry.register(dev.promethe.core.tools.sys.ProcessManagerTool(sandboxCommandRunner))
        }
        ToolRegistry.register(dev.promethe.core.tools.sys.SystemInfoTool(sandboxCommandRunner))
        if (ConfigProvider.get().getBoolean("DOCKER_TOOL_ENABLED", false)) {
            ToolRegistry.register(dev.promethe.core.tools.sys.DockerTool(workDirStr, sandboxCommandRunner))
        }
        ToolRegistry.register(dev.promethe.core.tools.sys.EnvironmentTool())
        logger.info { "System tools registered with public-safe defaults" }

        // ── Web Tools (6) ─────────────────────────────────────────
        val outboundClient = PinnedJvmOutboundHttpFetcher(JvmOutboundUrlPolicy())
        ToolRegistry.register(dev.promethe.core.tools.web.WebCrawlTool(outboundClient))
        ToolRegistry.register(dev.promethe.core.tools.web.RssFeedTool(outboundClient))
        ToolRegistry.register(dev.promethe.core.tools.web.WebScreenshotTool(workDirStr, sandboxCommandRunner))
        ToolRegistry.register(dev.promethe.core.tools.web.WebSearchTool(httpClient))
        ToolRegistry.register(dev.promethe.core.tools.web.WebExtractTool(outboundClient))
        ToolRegistry.register(dev.promethe.core.tools.web.XSearchTool(httpClient))
        logger.info { "Web tools registered (6 tools)" }

        // ── Data & Productivity Tools (6) ─────────────────────────
        val productivityDataDir = PrometheHome.productivityDataDir.apply { mkdirs() }
        val productivityReader = SecureJvmWorkspaceFileReader(productivityDataDir.absolutePath)
        val productivityWriter = SecureJvmWorkspaceFileWriter(productivityDataDir.absolutePath)
        ToolRegistry.register(dev.promethe.core.tools.data.JsonQueryTool())
        ToolRegistry.register(dev.promethe.core.tools.data.CsvTool(workDirStr, secureReader, secureWriter))
        ToolRegistry.register(dev.promethe.core.tools.data.PdfReaderTool(workDirStr, sandboxCommandRunner))
        ToolRegistry.register(dev.promethe.core.tools.data.ApiCallTool(outboundClient))
        ToolRegistry.register(dev.promethe.core.tools.data.TodoTool(productivityReader, productivityWriter))
        ToolRegistry.register(dev.promethe.core.tools.data.NotesTool(productivityReader, productivityWriter))
        logger.info { "Data & productivity tools registered (6 tools)" }

        // ── Security Tools (3) ────────────────────────────────────
        ToolRegistry.register(dev.promethe.core.tools.sec.HashTool())
        ToolRegistry.register(dev.promethe.core.tools.sec.EncryptTool())
        ToolRegistry.register(dev.promethe.core.tools.sec.CertCheckTool())
        logger.info { "Security tools registered (3 tools)" }

        // ── Communication Tools (conditional) ─────────────────────
        ToolRegistry.register(dev.promethe.core.tools.comm.SignalTool(httpClient))
        ToolRegistry.register(dev.promethe.core.tools.comm.DiscordTool(httpClient))
        val slackToken = ConfigProvider.get().get("SLACK_TOKEN", "")
        if (slackToken.isNotBlank()) {
            ToolRegistry.register(dev.promethe.core.tools.comm.SlackTool(httpClient, slackToken))
        }
        logger.info { "Communication tools registered" }

        // ── AI Tools ──────────────────────────────────────────────
        ToolRegistry.register(dev.promethe.core.tools.ai.EmbeddingTool(httpClient, apiKeys, capabilityRouter))
        // STT config from env (STT_PROVIDER/STT_BASE_URL/STT_API_KEY); null = OpenAI defaults
        val sttProvider = ConfigProvider.get().get("STT_PROVIDER", "")
        val sttConfig =
            if (sttProvider.isNotBlank()) {
                dev.promethe.core.tools.ai.SttConfig(
                    provider = sttProvider,
                    baseUrl = ConfigProvider.get().get("STT_BASE_URL", "https://api.openai.com"),
                    apiKey = ConfigProvider.get().get("STT_API_KEY", ""),
                )
            } else {
                null
            }
        ToolRegistry.register(dev.promethe.core.tools.ai.SpeechToTextTool(httpClient, apiKeys, workDirStr, sttConfig))
        ToolRegistry.register(dev.promethe.core.tools.ai.VectorSearchTool(workDirStr))
        logger.info { "AI tools registered" }

        // ── RAG Knowledge Search Tool (conditional) ───────────────
        val ragEnabled = ConfigProvider.get().getBoolean("RAG_ENABLED", false)
        if (ragEnabled) {
            try {
                val ragProvider = ConfigProvider.get().get("RAG_EMBEDDING_PROVIDER", "ollama")
                val ragModel = ConfigProvider.get().get("RAG_EMBEDDING_MODEL", "nomic-embed-text")
                val ragBaseUrl = ConfigProvider.get().get("RAG_EMBEDDING_URL", "http://localhost:11434")
                val ragApiKey = ConfigProvider.get().get("RAG_EMBEDDING_API_KEY", "")
                val ragVectorStore = ConfigProvider.get().get("RAG_VECTOR_STORE", "sqlite_vec")
                val ragVectorUrl = ConfigProvider.get().get("RAG_VECTOR_URL", "")
                val ragVectorApiKey = ConfigProvider.get().get("RAG_VECTOR_API_KEY", "")
                val ragVectorPath = ConfigProvider.get().get("RAG_VECTOR_PATH", (workDir / "rag_vectors").toString())
                val ragDimensions = ConfigProvider.get().getInt("RAG_EMBEDDING_DIMENSIONS", 768)

                val embeddingService = dev.promethe.core.rag.EmbeddingServiceFactory.create(
                    provider = ragProvider,
                    model = ragModel,
                    baseUrl = ragBaseUrl,
                    apiKey = ragApiKey,
                )
                val vectorStore = dev.promethe.core.rag.VectorStoreFactory.create(
                    storeType = ragVectorStore,
                    url = ragVectorUrl,
                    apiKey = ragVectorApiKey,
                    path = ragVectorPath,
                    vectorSize = ragDimensions,
                )
                val chunker = dev.promethe.core.rag.DocumentChunker()
                val knowledgeBase = dev.promethe.core.rag.KnowledgeBase(embeddingService, vectorStore, chunker)

                ToolRegistry.register(dev.promethe.core.rag.KnowledgeSearchTool(knowledgeBase))
                ToolRegistry.register(dev.promethe.core.rag.KnowledgeIngestTool(knowledgeBase))
                ToolRegistry.register(dev.promethe.core.rag.KnowledgeDeleteTool(knowledgeBase))
                logger.info {
                    "RAG tools registered: knowledge_search + knowledge_ingest + knowledge_delete ($ragProvider/$ragModel + $ragVectorStore)"
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to initialize RAG knowledge tools — skipping" }
            }
        }

        // ── Agent-level Tools ─────────────────────────────────────
        ToolRegistry.register(dev.promethe.core.tools.builtin.ClarifyTool())
        ToolRegistry.register(dev.promethe.core.tools.builtin.CronjobTool(taskScheduler))
        ToolRegistry.register(dev.promethe.core.tools.builtin.SendMessageTool(dev.promethe.core.tools.builtin.NoOpChannelSender()))
        ToolRegistry.register(dev.promethe.core.tools.builtin.SessionSearchTool(database))
        ToolRegistry.register(dev.promethe.core.tools.builtin.MixtureOfAgentsTool(llmAdapter, config))
        ToolRegistry.register(dev.promethe.core.tools.builtin.ConfigGetTool())
        ToolRegistry.register(
            dev.promethe.core.tools.builtin.ConfigSetTool(
                onConfigChanged = { key, value ->
                    // Hot-reload: propagate llmProvider / llmModel changes to the live adapter
                    when (key) {
                        "llmProvider" -> llmAdapter.updateActiveModel(value, llmAdapter.currentModel)
                        "llmModel" -> llmAdapter.updateActiveModel(llmAdapter.currentProvider, value)
                    }
                },
            ),
        )
        ToolRegistry.register(dev.promethe.core.tools.builtin.PluginListTool(null))
        val checkpointManager = CheckpointManager(database)
        ToolRegistry.register(dev.promethe.core.tools.builtin.CheckpointSaveTool(checkpointManager))
        ToolRegistry.register(dev.promethe.core.tools.builtin.CheckpointListTool(checkpointManager))
        ToolRegistry.register(dev.promethe.core.tools.builtin.RenderUITool())
        logger.info { "Agent-level tools registered (11 tools)" }

        // ── Autonomous Goal Tool ─────────────────────────────────
        ToolRegistry.register(dev.promethe.core.autonomous.AutonomousGoalTool())
        logger.info { "Autonomous goal tool registered" }

        // Video tools (VideoGenerateTool / VideoAnalyzeTool) are registered once
        // in registerIntegrationTools() alongside the other media tools.

        // ── Home Assistant Tools (conditional) ────────────────────
        val haUrl = ConfigProvider.get().get("HA_URL", "")
        if (haUrl.isNotBlank()) {
            ToolRegistry.register(dev.promethe.core.tools.ha.HaListEntitiesTool(httpClient))
            ToolRegistry.register(dev.promethe.core.tools.ha.HaGetStateTool(httpClient))
            ToolRegistry.register(dev.promethe.core.tools.ha.HaListServicesTool(httpClient))
            ToolRegistry.register(dev.promethe.core.tools.ha.HaCallServiceTool(httpClient))
            logger.info { "Home Assistant tools registered (4 tools)" }
        }

        logger.info { "Phase 2: All extended tools registered ✅" }
    }
}
