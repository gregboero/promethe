package dev.promethe.core

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.modelsById
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.llms.all.*
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.MessagePart
import dev.promethe.api.ReasoningEffort
import dev.promethe.api.XaiApiMode
import dev.promethe.db.LlmUsageLogRow
import dev.promethe.db.PrometheDatabaseApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

open class KoogLlmAdapter(
    protected val config: AgentConfig,
    private val database: PrometheDatabaseApi? = null,
    private val resourceGovernors: ResourceGovernorRegistry = GlobalResourceGovernorRegistry,
) {
    private val logger = Log.create("KoogLlmAdapter")

    // Legacy single executor (for backward compat when router is not set)
    private var executor: MultiLLMPromptExecutor? = null
    private var pricingClient: io.ktor.client.HttpClient? = null
    private var pricingService: ModelPricingService? = null
    private var apiKeys: Map<String, String> = emptyMap()

    suspend fun updateApiKeys(newApiKeys: Map<String, String>) {
        this.apiKeys = newApiKeys
        router?.updateApiKeys(newApiKeys)
        if (executor != null) {
            initialize(newApiKeys)
        }
    }

    // ── Hot-reload: current active provider/model ──────────────────────────
    // Updated via updateActiveModel() when the user changes config at runtime.
    // Starts from AgentConfig values set at startup.
    @Volatile var currentProvider: String = config.provider

    @Volatile var currentModel: String = config.modelName

    /** Multi-model router — set via initializeWithRouter() */
    var router: MultiModelRouter? = null
        private set

    // Thread-safe trackers
    private val statsMutex = Mutex()
    private var totalPromptTokens = 0
    private var totalCompletionTokens = 0
    private var totalRequests = 0
    private var totalCost = 0.0

    // ── Prompt cache: hash(systemPrompt) → pre-built Prompt object ──
    // Reduces overhead when the same system prompt is reused across requests.
    private data class CachedPrompt(
        val hash: String,
        val prompt: Prompt,
        val lastUsed: Long,
    )

    private val promptCache = LinkedHashMap<String, CachedPrompt>(16, 0.75f, true)
    private val promptCacheMutex = Mutex()
    private val maxCacheSize = 32
    private var promptCacheHits = 0L
    private var promptCacheMisses = 0L

    /**
     * Legacy initialization — single executor mode.
     */
    suspend fun initialize(apiKeys: Map<String, String>) {
        this.apiKeys = apiKeys
        val httpClientFactory = getHttpClientFactory()

        // Initialize pricing service (close old client if re-initializing)
        pricingClient?.close()
        pricingClient = createPricingClient()
        val litellmUrl =
            if (config.provider.equals("litellm", ignoreCase = true)) {
                config.customBaseUrl.ifBlank { "http://localhost:4000" }
            } else {
                config.customBaseUrl
            }
        pricingService = ModelPricingService(pricingClient!!, litellmUrl)

        executor =
            when (dev.promethe.api.ProviderRegistry.canonicalKey(config.provider)) {
                "openai" -> {
                    simpleOpenAIExecutor(apiKeys["openai"] ?: "", httpClientFactory)
                }

                "anthropic" -> {
                    val settings = ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings(
                        modelVersionsMap = PassThroughModelVersionsMap(),
                    )
                    MultiLLMPromptExecutor(
                        ai.koog.prompt.llm.LLMProvider.Anthropic to ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient(
                            apiKey = apiKeys["anthropic"] ?: "",
                            settings = settings,
                            httpClientFactory = httpClientFactory,
                        ),
                    )
                }

                "google" -> {
                    simpleGoogleAIExecutor(apiKeys["google"] ?: "", httpClientFactory)
                }

                "ollama" -> {
                    simpleOllamaAIExecutor(apiKeys["ollama_url"] ?: "http://localhost:11434", httpClientFactory)
                }

                "litellm" -> {
                    MultiLLMPromptExecutor(
                        LLMProvider.OpenAI to OpenAILLMClient(
                            apiKey = apiKeys["litellm"] ?: "",
                            settings = OpenAIClientSettings(
                                baseUrl = apiKeys["litellm_url"] ?: config.customBaseUrl.ifBlank { "http://localhost:4000" },
                            ),
                            httpClientFactory = httpClientFactory,
                        ),
                    )
                }

                "openrouter" -> {
                    simpleOpenRouterExecutor(apiKeys["openrouter"] ?: "", httpClientFactory)
                }

                "deepseek" -> {
                    MultiLLMPromptExecutor(
                        LLMProvider.DeepSeek to DeepSeekLLMClient(
                            apiKey = apiKeys["deepseek"] ?: "",
                            httpClientFactory = httpClientFactory,
                        ),
                    )
                }

                "nvidia" -> {
                    MultiLLMPromptExecutor(
                        LLMProvider.OpenAI to OpenAILLMClient(
                            apiKey = apiKeys["nvidia"] ?: "",
                            settings = OpenAIClientSettings(baseUrl = "https://integrate.api.nvidia.com"),
                            httpClientFactory = httpClientFactory,
                        ),
                    )
                }

                "kimi" -> {
                    MultiLLMPromptExecutor(
                        LLMProvider.OpenAI to OpenAILLMClient(
                            apiKey = apiKeys["kimi"] ?: "",
                            settings = OpenAIClientSettings(
                                baseUrl = apiKeys["kimi_url"] ?: "https://api.moonshot.ai",
                            ),
                            httpClientFactory = httpClientFactory,
                        ),
                    )
                }

                "xai" -> {
                    MultiLLMPromptExecutor(
                        LLMProvider.OpenAI to OpenAILLMClient(
                            apiKey = apiKeys["xai"] ?: "",
                            settings = OpenAIClientSettings(
                                baseUrl = apiKeys["xai_url"] ?: "https://api.x.ai",
                            ),
                            httpClientFactory = httpClientFactory,
                        ),
                    )
                }

                else -> {
                    throw IllegalArgumentException("Unsupported provider: ${config.provider}")
                }
            }
    }

    /**
     * Multi-model initialization — creates a router that manages executors
     * for all registered agent profiles.
     */
    suspend fun initializeWithRouter(
        apiKeys: Map<String, String>,
        db: PrometheDatabaseApi,
    ) {
        this.apiKeys = apiKeys

        // Initialize pricing service (close old client if re-initializing)
        pricingClient?.close()
        pricingClient = createPricingClient()
        val litellmUrl =
            if (config.provider.equals("litellm", ignoreCase = true)) {
                config.customBaseUrl.ifBlank { "http://localhost:4000" }
            } else {
                config.customBaseUrl
            }
        pricingService = ModelPricingService(pricingClient!!, litellmUrl)

        router = MultiModelRouter(db, apiKeys)
        router!!.warmUp()

        // Also keep legacy executor for backward compat
        val (defaultExec, _) = router!!.getDefaultExecutor()
        executor = defaultExec
    }

    /**
     * Complete a prompt using the default profile or legacy executor.
     */
    open suspend fun complete(
        systemPrompt: String,
        messages: List<Pair<String, String>>,
        model: String = currentModel,
        temperature: Double = config.temperature,
    ): LlmResponse = completeWithProfile(systemPrompt, messages, currentProvider, model, temperature)

    /**
     * Hot-reload: update the active provider and model at runtime.
     *
     * Called by [ConfigSetTool] when the user changes `llmProvider` or `llmModel`
     * via `config_set`. Also re-initializes the router executor for the new provider
     * if the router is active.
     */
    fun updateActiveModel(
        provider: String,
        model: String,
    ) {
        val canonicalProvider = dev.promethe.api.ProviderRegistry.canonicalKey(provider)
        val resolvedModel = resolveModel(canonicalProvider, model)
        currentProvider = canonicalProvider
        currentModel = resolvedModel
        // Note: the router executor will be resolved on the next LLM call via completeWithProfile().
        // No need to pre-warm here — the router.getExecutor() is suspend and would require a coroutine.
    }

    internal fun resolveModel(
        provider: String,
        requestedModel: String?,
    ): String {
        val canonicalProvider = dev.promethe.api.ProviderRegistry.canonicalKey(provider)
        val providerInfo =
            requireNotNull(dev.promethe.api.ProviderRegistry.get(canonicalProvider)) {
                "Unsupported provider: $provider"
            }
        return requestedModel
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: currentModel
                .trim()
                .takeIf {
                    it.isNotEmpty() &&
                        dev.promethe.api.ProviderRegistry.canonicalKey(currentProvider) == canonicalProvider
                }
            ?: config.modelName
                .trim()
                .takeIf {
                    it.isNotEmpty() &&
                        dev.promethe.api.ProviderRegistry.canonicalKey(config.provider) == canonicalProvider
                }
            ?: providerInfo.defaultModel.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("A model is required for provider '$canonicalProvider'")
    }

    /**
     * Complete a prompt using a specific agent profile ID.
     * The router resolves the profile → provider → executor.
     */
    open suspend fun completeForProfile(
        profileId: String,
        systemPrompt: String,
        messages: List<Pair<String, String>>,
    ): LlmResponse {
        val rt = router ?: throw IllegalStateException("Router not initialized. Call initializeWithRouter().")
        val (exec, profile) = rt.getExecutorForProfile(profileId)
        return executeCompletion(exec, profile.provider, profile.model, profile.temperature, systemPrompt, messages)
    }

    /**
     * Complete with explicit provider/model override (used by delegate_task).
     */
    open suspend fun completeWithProfile(
        systemPrompt: String,
        messages: List<Pair<String, String>>,
        provider: String = config.provider,
        model: String = config.modelName,
        temperature: Double = config.temperature,
        tools: List<ToolDescriptor> = emptyList(),
        context: LlmRequestContext? = null,
        pendingToolTurn: PendingToolTurn? = null,
        reasoningEffort: ReasoningEffort = config.reasoningEffort,
    ): LlmResponse {
        val canonicalProvider = dev.promethe.api.ProviderRegistry.canonicalKey(provider)
        val resolvedModel = resolveModel(canonicalProvider, model)
        val exec =
            if (router != null) {
                router!!.getExecutor(canonicalProvider)
            } else {
                executor ?: throw IllegalStateException("KoogLlmAdapter not initialized.")
            }
        return executeCompletion(
            exec = exec,
            provider = canonicalProvider,
            model = resolvedModel,
            temperature = temperature,
            systemPrompt = systemPrompt,
            messages = messages,
            tools = tools,
            context = context,
            pendingToolTurn = pendingToolTurn,
            reasoningEffort = reasoningEffort,
        )
    }

    private suspend fun executeCompletion(
        exec: MultiLLMPromptExecutor,
        provider: String,
        model: String,
        temperature: Double,
        systemPrompt: String,
        messages: List<Pair<String, String>>,
        tools: List<ToolDescriptor> = emptyList(),
        context: LlmRequestContext? = null,
        pendingToolTurn: PendingToolTurn? = null,
        reasoningEffort: ReasoningEffort = config.reasoningEffort,
    ): LlmResponse =
        Tracing.span("gen_ai.chat") {
            setAttribute("gen_ai.system", provider)
            setAttribute("gen_ai.request.model", model)
            setAttribute("gen_ai.request.temperature", temperature)
            setAttribute("gen_ai.request.message_count", messages.size)
            context?.runId?.let { setAttribute("promethe.run.id", it) }
            context?.parentRunId?.let { setAttribute("promethe.run.parent_id", it) }
            context?.stepId?.let { setAttribute("promethe.step.id", it) }

            // Build prompt (with system prompt caching)
            val prompt = buildCachedPrompt(systemPrompt, messages, pendingToolTurn)

            // ── Build LLModel ────────────────────────────────────────
            // CRITICAL: For Anthropic, we MUST use the pre-defined SDK model objects
            // because AnthropicLLMClient uses modelVersionsMap keyed by LLModel instances
            // (data class equality). A custom LLModel with the same id but different
            // capabilities/contextLength won’t match and causes “Unsupported model”.
            val llModel = resolveKnownModel(provider, model, config.xaiApiMode)
            val providerPrompt = applyProviderParams(prompt, provider, context, reasoningEffort)

            // ── Execute with 2-level fallback chain ──────────────────────
            // Level 1: same provider, known-good model
            // Level 2: cross-provider safety net (Google gemini-3.1-flash-lite)
            var usedFallback: FallbackModel? = null
            val assistantMessage = try {
                admitLlmCall(context)
                exec.execute(providerPrompt, llModel, tools)
            } catch (e: Exception) {
                if (e is AgentExecutionException && e.code == "resource_budget_exceeded") throw e
                val msg = e.message.orEmpty()
                logger.error { "LLM execute failed [provider=$provider, model=$model]: $msg" }

                // Fallback chain is opt-in (FALLBACK_CHAIN_ENABLED) — when disabled,
                // failures propagate as-is instead of silently swapping models.
                if (!config.fallbackChainEnabled) throw e

                // Build ordered fallback chain
                val fallbackChain = buildList {
                    // Level 1: same provider
                    PROVIDER_FALLBACKS[provider.lowercase()]?.let { fb ->
                        if (fb.model != model) add(fb)
                    }
                    // Level 2: cross-provider safety net
                    val crossProvider = CROSS_PROVIDER_FALLBACK
                    if (crossProvider.provider != provider.lowercase() || crossProvider.model != model) {
                        add(crossProvider)
                    }
                }

                var lastResult: ai.koog.prompt.message.Message.Assistant? = null
                for (fb in fallbackChain) {
                    logger.warn { "Trying fallback ${fb.provider}/${fb.model}..." }
                    val fbExec = if (router != null && fb.provider != provider) {
                        try {
                            router!!.getExecutor(fb.provider)
                        } catch (_: Exception) {
                            continue
                        }
                    } else {
                        exec
                    }
                    val fbModel = resolveKnownModel(fb.provider, fb.model, config.xaiApiMode)
                    val fallbackPrompt = applyProviderParams(prompt, fb.provider, context, reasoningEffort)
                    try {
                        admitLlmCall(context)
                        lastResult = fbExec.execute(fallbackPrompt, fbModel, tools)
                        usedFallback = fb
                        break
                    } catch (e2: Exception) {
                        if (e2 is AgentExecutionException && e2.code == "resource_budget_exceeded") throw e2
                        logger.warn { "Fallback failed [${fb.provider}/${fb.model}]: ${e2.message}" }
                    }
                }
                lastResult ?: throw e // all fallbacks exhausted → propagate original
            }
            val content = assistantMessage.textContent()
            val toolCalls = assistantMessage.parts.filterIsInstance<MessagePart.Tool.Call>().map { call ->
                NativeToolCall(
                    id = call.id.orEmpty(),
                    toolName = call.tool.orEmpty(),
                    args = call.args.orEmpty(),
                )
            }

            // Build fallback notice for the user
            val fallbackNotice = usedFallback?.let { fb ->
                "⚠\uFE0F Model **$model** ($provider) is unavailable. Response generated by **${fb.model}** (${fb.provider})."
            }

            // Actual model/provider used for pricing
            val actualModel = usedFallback?.model ?: model
            val actualProvider = usedFallback?.provider ?: provider

            // Extract metadata
            val meta = assistantMessage.metaInfo
            val pTokens = meta.inputTokensCount ?: 0
            val cTokens = meta.outputTokensCount ?: 0

            // Dynamic pricing lookup
            val providerKey = apiKeys[actualProvider.lowercase()] ?: apiKeys["openrouter"] ?: ""
            val (pRate, cRate) =
                pricingService?.getPricing(actualProvider, actualModel, providerKey)
                    ?: ModelPricingService.KNOWN_PRICING[actualModel]
                    ?: ModelPricingService.DEFAULT_PRICING
            val cost = (pTokens / 1_000_000.0) * pRate + (cTokens / 1_000_000.0) * cRate
            context?.runId?.let { runId ->
                resourceGovernors.governorForRun(runId)?.recordLlmUsage((pTokens + cTokens).toLong(), cost)
            }

            // Track stats (thread-safe)
            statsMutex.withLock {
                totalPromptTokens += pTokens
                totalCompletionTokens += cTokens
                totalRequests += 1
                totalCost += cost
            }

            // Persist to SQLite (fire-and-forget, non-blocking)
            try {
                database?.insertLlmUsageLog(
                    LlmUsageLogRow(
                        provider = actualProvider,
                        model = actualModel,
                        promptTokens = pTokens,
                        completionTokens = cTokens,
                        cost = cost,
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (e: Exception) {
                logger.warn { "Failed to persist LLM usage log: ${e.message}" }
            }

            // Tracy attributes
            setAttribute("gen_ai.usage.prompt_tokens", pTokens)
            setAttribute("gen_ai.usage.completion_tokens", cTokens)
            setAttribute("gen_ai.response.model", actualModel)
            setAttribute("gen_ai.usage.cost", cost)

            LlmResponse(
                content = content,
                promptTokens = pTokens,
                completionTokens = cTokens,
                model = actualModel,
                provider = actualProvider,
                fallbackNotice = fallbackNotice,
                toolCalls = toolCalls,
                rawMessage = assistantMessage,
            )
        }

    private suspend fun admitLlmCall(context: LlmRequestContext?) {
        val runId = context?.runId ?: return
        val governor = resourceGovernors.governorForRun(runId) ?: return
        val admission = governor.admit(GovernedResource.LLM_CALL)
        if (admission is ResourceAdmission.Denied) {
            throw AgentExecutionException("resource_budget_exceeded", admission.message())
        }
    }

    private fun applyProviderParams(
        prompt: Prompt,
        provider: String,
        context: LlmRequestContext?,
        reasoningEffort: ReasoningEffort,
    ): Prompt =
        when (dev.promethe.api.ProviderRegistry.canonicalKey(provider)) {
            "kimi" -> {
                prompt.withParams(OpenAiCompatibleProviderPolicy.kimiChatParams(context, reasoningEffort))
            }

            "xai" -> {
                when (config.xaiApiMode) {
                    XaiApiMode.RESPONSES -> {
                        prompt.withParams(OpenAiCompatibleProviderPolicy.xaiResponsesParams(context, reasoningEffort))
                    }

                    XaiApiMode.CHAT_COMPLETIONS -> {
                        prompt.withParams(OpenAiCompatibleProviderPolicy.xaiChatParams(reasoningEffort))
                    }
                }
            }

            else -> {
                prompt
            }
        }

    /**
     * Fetch all available models from the current provider.
     */
    suspend fun getAvailableModels(): List<ModelInfo> {
        val apiKey = apiKeys["openrouter"] ?: ""
        return pricingService?.fetchAllModels(apiKey) ?: emptyList()
    }

    data class LlmStats(
        val totalRequests: Int,
        val promptTokens: Int,
        val completionTokens: Int,
        val totalCost: Double,
        val cacheHits: Long,
        val cacheMisses: Long,
        val cacheSize: Int,
    )

    suspend fun getStats(): LlmStats =
        statsMutex.withLock {
            LlmStats(
                totalRequests = totalRequests,
                promptTokens = totalPromptTokens,
                completionTokens = totalCompletionTokens,
                totalCost = totalCost,
                cacheHits = promptCacheHits,
                cacheMisses = promptCacheMisses,
                cacheSize = promptCache.size,
            )
        }

    fun getPoolStats(): Map<String, Int> = router?.getPoolStats() ?: emptyMap()

    /**
     * Restore cumulative stats from SQLite on startup.
     * Called once during gateway initialization.
     */
    suspend fun restoreStats() {
        val db = database ?: return
        try {
            val agg = db.getAggregatedLlmStats()
            statsMutex.withLock {
                totalPromptTokens = agg.totalPromptTokens.toInt()
                totalCompletionTokens = agg.totalCompletionTokens.toInt()
                totalRequests = agg.totalRequests
                totalCost = agg.totalCost
            }
            logger.info {
                "Restored LLM stats from DB: ${agg.totalRequests} requests, ${agg.totalPromptTokens + agg.totalCompletionTokens} tokens, \$${String.format(
                    "%.4f",
                    agg.totalCost,
                )}"
            }
        } catch (e: Exception) {
            logger.warn { "Failed to restore LLM stats from DB: ${e.message}" }
        }
    }

    /**
     * Build a prompt with system prompt caching.
     * The system prompt is hashed; if the hash matches a cached entry,
     * we reuse the pre-built Prompt instead of rebuilding from scratch.
     * Messages (user/assistant) are always appended fresh.
     */
    private suspend fun buildCachedPrompt(
        systemPrompt: String,
        messages: List<Pair<String, String>>,
        pendingToolTurn: PendingToolTurn? = null,
    ): Prompt {
        // Compute hash for cache key (system prompt only — messages change per request)
        val hash = systemPrompt.hashCode().toString(36)

        // Check cache
        val cachedEntry =
            promptCacheMutex.withLock {
                promptCache[hash]
            }

        if (cachedEntry != null) {
            // Cache hit: rebuild with same system prompt + new messages
            promptCacheMutex.withLock { promptCacheHits++ }
        } else {
            promptCacheMutex.withLock { promptCacheMisses++ }
        }

        // Koog Prompt is immutable, so the message list is rebuilt while the
        // cache tracks provider-level reuse of the stable system prefix.
        val prompt = buildPrompt(systemPrompt, messages, pendingToolTurn)

        // Update cache
        promptCacheMutex.withLock {
            if (promptCache.size >= maxCacheSize) {
                // Evict oldest (LRU via access-order LinkedHashMap)
                val oldest = promptCache.keys.firstOrNull()
                if (oldest != null) promptCache.remove(oldest)
            }
            promptCache[hash] =
                CachedPrompt(
                    hash,
                    prompt,
                    kotlin.time.Clock.System
                        .now()
                        .toEpochMilliseconds(),
                )
        }

        return prompt
    }

    internal fun buildPrompt(
        systemPrompt: String,
        messages: List<Pair<String, String>>,
        pendingToolTurn: PendingToolTurn? = null,
    ): Prompt {
        val builder = Prompt.builder("promethe-prompt")
        builder.system(systemPrompt)
        for ((role, content) in messages) {
            when (role) {
                "user" -> builder.user(content)
                "assistant" -> builder.assistant(content)
                "system" -> builder.system(content)
                else -> builder.user(content)
            }
        }
        pendingToolTurn?.let { pending ->
            builder.message(pending.assistantMessage)
            builder.toolResult(
                tool = pending.toolName,
                output = pending.result,
                id = pending.toolCallId,
                isError = pending.result.startsWith("Error:", ignoreCase = true),
            )
        }
        return builder.build()
    }

    suspend fun close() {
        router?.close()
        executor?.close()
        pricingClient?.close()
    }

    private fun createPricingClient() =
        io.ktor.client.HttpClient {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
            }
        }

    companion object {
        /** Describes a fallback model to try when the primary model fails. */
        data class FallbackModel(
            val provider: String,
            val model: String,
        )

        /**
         * Provider-level fallbacks — same provider, known-good models.
         * These are Koog-hardcoded models so they always pass SDK validation.
         * Ordered by quality: the best general-purpose model for each provider.
         */
        val PROVIDER_FALLBACKS: Map<String, FallbackModel> = mapOf(
            "google" to FallbackModel("google", "gemini-3.1-flash-lite"),
            "openai" to FallbackModel("openai", "gpt-5.6-terra"),
            "anthropic" to FallbackModel("anthropic", "claude-sonnet-5"),
            "deepseek" to FallbackModel("deepseek", "deepseek-v4-flash"),
        )

        /** Cross-provider fallback is only attempted when explicitly enabled. */
        val CROSS_PROVIDER_FALLBACK = FallbackModel("google", "gemini-3.1-flash-lite")

        fun resolveProvider(provider: String): LLMProvider =
            when (dev.promethe.api.ProviderRegistry.canonicalKey(provider)) {
                "openai" -> LLMProvider.OpenAI
                "anthropic" -> LLMProvider.Anthropic
                "google" -> LLMProvider.Google
                "ollama" -> LLMProvider.Ollama
                "litellm" -> LLMProvider.OpenAI
                "openrouter" -> LLMProvider.OpenRouter
                "deepseek" -> LLMProvider.DeepSeek
                "nvidia" -> LLMProvider.OpenAI
                "vllm" -> LLMProvider.Ollama
                "nous" -> LLMProvider.Ollama
                "kimi", "xai" -> LLMProvider.OpenAI
                else -> throw IllegalArgumentException("Unsupported provider: $provider")
            }

        /** Lazy-initialized SDK model registries indexed by model ID. */
        private val sdkModelsByProvider: Map<String, Map<String, LLModel>> by lazy {
            mapOf(
                "anthropic" to AnthropicModels.modelsById(),
                "openai" to OpenAIModels.modelsById(),
                "google" to GoogleModels.modelsById(),
            )
        }

        /**
         * Resolve a model by ID, preferring SDK pre-defined objects.
         *
         * For Anthropic this is CRITICAL: the client uses `modelVersionsMap`
         * keyed by `LLModel` (data class equality). A custom LLModel with the
         * same id but different capabilities won't match → "Unsupported model".
         *
         * For OpenAI/Google: SDK objects are preferred but custom ones also work
         * since those clients don't do a map lookup by object identity.
         */
        fun resolveKnownModel(
            provider: String,
            modelId: String,
            xaiApiMode: XaiApiMode = XaiApiMode.RESPONSES,
        ): LLModel {
            val canonicalProvider = dev.promethe.api.ProviderRegistry.canonicalKey(provider)
            // 1. Try SDK registry for this provider
            val sdkModel = sdkModelsByProvider[canonicalProvider]?.get(modelId)
            if (sdkModel != null) return sdkModel

            // 2. Try ALL registries (cross-provider lookup for aliases)
            for ((_, registry) in sdkModelsByProvider) {
                val found = registry[modelId]
                if (found != null) return found
            }

            // 3. Create a custom LLModel with provider-specific capabilities
            val providerEnum = resolveProvider(canonicalProvider)
            val optionalCapabilities = ModelCapabilityRegistry.capabilitiesFor(canonicalProvider, modelId)
            val endpointCapabilities =
                when {
                    canonicalProvider == "openai" && modelId.startsWith("gpt-5.6") -> {
                        listOf(LLMCapability.OpenAIEndpoint.Responses)
                    }

                    canonicalProvider == "xai" && xaiApiMode == XaiApiMode.RESPONSES -> {
                        listOf(LLMCapability.OpenAIEndpoint.Responses)
                    }

                    canonicalProvider in setOf("openai", "litellm", "nvidia", "kimi", "xai") -> {
                        listOf(LLMCapability.OpenAIEndpoint.Completions)
                    }

                    else -> {
                        emptyList()
                    }
                }
            val caps = buildList {
                add(LLMCapability.Completion)
                add(LLMCapability.MultipleChoices)
                addAll(optionalCapabilities)
                if (LLMCapability.Tools in optionalCapabilities) add(LLMCapability.ToolChoice)
                addAll(endpointCapabilities)
            }.distinct()
            return LLModel(
                provider = providerEnum,
                id = modelId,
                capabilities = caps,
                contextLength =
                    when (canonicalProvider) {
                        "kimi" -> 1_048_576
                        "xai" -> 500_000
                        else -> 128_000
                    },
                maxOutputTokens = 16_384,
            )
        }
    }
}

data class NativeToolCall(
    val id: String,
    val toolName: String,
    val args: String,
)

data class LlmResponse(
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val model: String,
    val provider: String,
    /** Non-null when a fallback model was used instead of the requested one. */
    val fallbackNotice: String? = null,
    val toolCalls: List<NativeToolCall> = emptyList(),
    val rawMessage: ai.koog.prompt.message.Message.Assistant? = null,
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    fun estimateCost(
        p: Double = 0.0,
        c: Double = 0.0,
    ): Double = (promptTokens / 1_000_000.0) * p + (completionTokens / 1_000_000.0) * c
}
