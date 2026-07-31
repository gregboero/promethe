package dev.promethe.core

import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.llms.all.*
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.promethe.db.AgentProfileRow
import dev.promethe.db.PrometheDatabaseApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Multi-Model Router — manages multiple LLM executors keyed by AgentProfile.
 *
 * Instead of a single executor per adapter, the router lazily initializes
 * one executor per (provider) and routes LLM calls to the correct backend
 * based on the profile selected at call-time.
 *
 * Thread-safe: uses a Mutex to guard executor creation.
 */
class MultiModelRouter(
    private val database: PrometheDatabaseApi,
    private var apiKeys: Map<String, String>,
) {
    // Cache: provider -> executor (re-used across profiles with the same provider)
    private val executors = mutableMapOf<String, MultiLLMPromptExecutor>()
    private val mutex = Mutex()

    // ── Credential Pools: round-robin rotation for multi-key providers ──
    // Keys are split by comma: "sk-key1,sk-key2,sk-key3"
    private var keyPools: Map<String, List<String>> =
        apiKeys.mapValues { (_, v) ->
            v.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
    private val keyCounters = mutableMapOf<String, Int>()
    private val keyCounterMutex = Mutex()

    suspend fun updateApiKeys(newApiKeys: Map<String, String>) {
        mutex.withLock {
            this.apiKeys = newApiKeys
            this.keyPools = newApiKeys.mapValues { (_, v) ->
                v.split(",").map { it.trim() }.filter { it.isNotBlank() }
            }
            executors.values.forEach { it.close() }
            executors.clear()
        }
    }

    /**
     * Get the next API key for a provider using round-robin rotation.
     * If only one key exists, always returns it.
     */
    suspend fun getNextKey(provider: String): String {
        val pool = keyPools[provider.lowercase()] ?: return apiKeys[provider.lowercase()] ?: ""
        if (pool.isEmpty()) return ""
        if (pool.size == 1) return pool[0]
        val idx =
            keyCounterMutex.withLock {
                val current = keyCounters.getOrPut(provider.lowercase()) { 0 }
                keyCounters[provider.lowercase()] = current + 1
                current % pool.size
            }
        return pool[idx]
    }

    /**
     * Get or lazily create an executor for the given provider.
     */
    suspend fun getExecutor(provider: String): MultiLLMPromptExecutor {
        val key = dev.promethe.api.ProviderRegistry.canonicalKey(provider)
        executors[key]?.let { return it }

        return mutex.withLock {
            // Double-check after acquiring lock
            executors[key]?.let { return it }

            val httpClientFactory = getHttpClientFactory()
            val executor = createExecutorForProvider(key, httpClientFactory)
            executors[key] = executor
            executor
        }
    }

    /**
     * Get the executor for a specific agent profile (by ID).
     * Falls back to the default profile if not found.
     */
    suspend fun getExecutorForProfile(profileId: String): Pair<MultiLLMPromptExecutor, AgentProfileRow> {
        val profile =
            database.getAgentProfile(profileId)
                ?: database.getDefaultAgentProfile()
                ?: throw IllegalStateException("No agent profile found (id=$profileId)")

        val executor = getExecutor(profile.provider)
        return executor to profile
    }

    /**
     * Get the executor for the default agent profile.
     */
    suspend fun getDefaultExecutor(): Pair<MultiLLMPromptExecutor, AgentProfileRow> {
        val profile =
            database.getDefaultAgentProfile()
                ?: database.getAllAgentProfiles().firstOrNull()
                ?: throw IllegalStateException("No agent profiles configured. Run ProfileSeeder first.")
        val executor = getExecutor(profile.provider)
        return executor to profile
    }

    /**
     * Pre-warm executors for all persisted (non-ephemeral) profiles.
     */
    suspend fun warmUp() {
        val profiles = database.getAllAgentProfiles()
        for (profile in profiles) {
            if (!profile.ephemeral) {
                getExecutor(profile.provider)
            }
        }
    }

    /**
     * List all currently active provider slots.
     */
    fun activeProviders(): Set<String> = executors.keys.toSet()

    /**
     * Close all executors and clear the cache.
     */
    suspend fun close() {
        mutex.withLock {
            executors.values.forEach { it.close() }
            executors.clear()
        }
    }

    /**
     * Build a FallbackChain from a profile's fallback models list.
     * Profile.tools field is repurposed here — fallback models are stored separately.
     */
    suspend fun buildFallbackChain(profileId: String? = null): FallbackChain {
        val profile =
            if (profileId != null) {
                database.getAgentProfile(profileId) ?: database.getDefaultAgentProfile()
            } else {
                database.getDefaultAgentProfile()
            } ?: throw IllegalStateException("No agent profile found")

        // For now, build chain from all available providers as fallbacks
        val primaryEntry = FallbackChain.FallbackEntry(profile.provider, profile.model, 0)
        val fallbackEntries = mutableListOf<FallbackChain.FallbackEntry>()

        // Auto-discover fallback providers from available API keys
        var priority = 1
        for ((provider, key) in apiKeys) {
            if (
                key.isNotBlank() &&
                provider != profile.provider &&
                provider in dev.promethe.api.ProviderRegistry.supportedKeys
            ) {
                val defaultModel = Companion.getDefaultModelForProvider(provider)
                if (defaultModel.isNotBlank()) {
                    fallbackEntries.add(FallbackChain.FallbackEntry(provider, defaultModel, priority++))
                }
            }
        }

        return FallbackChain(listOf(primaryEntry) + fallbackEntries)
    }

    companion object {
        fun getDefaultModelForProvider(provider: String): String =
            when (dev.promethe.api.ProviderRegistry.canonicalKey(provider)) {
                "openai" -> "gpt-5.6-terra"
                "anthropic" -> "claude-sonnet-5"
                "google" -> "gemini-3.5-flash"
                "openrouter" -> ""
                "deepseek" -> "deepseek-v4-flash"
                "nvidia", "litellm" -> ""
                "ollama" -> "llama3.1"
                "vllm" -> "Hermes-3-Llama-3.1-8B"
                "nous" -> "Hermes-3-Llama-3.1-70B"
                "kimi" -> "kimi-k3"
                "xai" -> "grok-4.5"
                else -> ""
            }
    }

    // ── Internal ──

    private suspend fun createExecutorForProvider(
        provider: String,
        httpClientFactory: ai.koog.http.client.KoogHttpClient.Factory,
    ): MultiLLMPromptExecutor {
        // Use round-robin key from pool instead of single key
        return when (provider) {
            "openai" -> {
                val apiKey = getNextKey("openai")
                simpleOpenAIExecutor(apiKey, httpClientFactory)
            }

            "anthropic" -> {
                val apiKey = getNextKey("anthropic")
                // Use a pass-through modelVersionsMap so ANY model ID works,
                // not just the SDK's hardcoded ones.
                val settings = AnthropicClientSettings(
                    modelVersionsMap = PassThroughModelVersionsMap(),
                )
                MultiLLMPromptExecutor(
                    LLMProvider.Anthropic to AnthropicLLMClient(
                        apiKey = apiKey,
                        settings = settings,
                        httpClientFactory = httpClientFactory,
                    ),
                )
            }

            "google" -> {
                val apiKey = getNextKey("google")
                simpleGoogleAIExecutor(apiKey, httpClientFactory)
            }

            "ollama" -> {
                val ollamaUrl = apiKeys["ollama_url"] ?: "http://localhost:11434"
                simpleOllamaAIExecutor(ollamaUrl, httpClientFactory)
            }

            "litellm" -> {
                val apiKey = getNextKey("litellm")
                openAICompatibleExecutor(
                    apiKey = apiKey,
                    baseUrl = apiKeys["litellm_url"] ?: "http://localhost:4000",
                    httpClientFactory = httpClientFactory,
                )
            }

            "openrouter" -> {
                val apiKey = getNextKey("openrouter")
                simpleOpenRouterExecutor(apiKey, httpClientFactory)
            }

            "deepseek" -> {
                val apiKey = getNextKey("deepseek")
                MultiLLMPromptExecutor(
                    LLMProvider.DeepSeek to DeepSeekLLMClient(
                        apiKey = apiKey,
                        httpClientFactory = httpClientFactory,
                    ),
                )
            }

            "nvidia" -> {
                val apiKey = getNextKey("nvidia")
                openAICompatibleExecutor(
                    apiKey = apiKey,
                    baseUrl = "https://integrate.api.nvidia.com",
                    httpClientFactory = httpClientFactory,
                )
            }

            "vllm" -> {
                // vLLM exposes an OpenAI-compatible API — use Ollama executor (accepts custom URL)
                val vllmUrl = apiKeys["vllm_url"] ?: "http://localhost:8000"
                simpleOllamaAIExecutor(vllmUrl, httpClientFactory)
            }

            "nous" -> {
                // Nous Research Portal — OpenAI-compatible API
                val nousUrl = apiKeys["nous_url"] ?: "https://inference.nous.hermes.dev"
                simpleOllamaAIExecutor(nousUrl, httpClientFactory)
            }

            "kimi" -> {
                val apiKey = getNextKey("kimi")
                val baseUrl = apiKeys["kimi_url"] ?: "https://api.moonshot.ai"
                openAICompatibleExecutor(
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    httpClientFactory = httpClientFactory,
                )
            }

            "xai" -> {
                val apiKey = getNextKey("xai")
                val baseUrl = apiKeys["xai_url"] ?: "https://api.x.ai"
                openAICompatibleExecutor(
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    httpClientFactory = httpClientFactory,
                )
            }

            else -> {
                throw IllegalArgumentException("Unsupported provider: $provider")
            }
        }
    }

    /**
     * Get credential pool stats for monitoring.
     */
    fun getPoolStats(): Map<String, Int> = keyPools.mapValues { (_, v) -> v.size }

    /**
     * Check if an executor has been created for a provider (i.e., it was used at least once).
     */
    fun hasExecutor(provider: String): Boolean = executors.containsKey(provider.lowercase())

    private fun openAICompatibleExecutor(
        apiKey: String,
        baseUrl: String,
        httpClientFactory: ai.koog.http.client.KoogHttpClient.Factory,
    ): MultiLLMPromptExecutor =
        MultiLLMPromptExecutor(
            LLMProvider.OpenAI to OpenAILLMClient(
                apiKey = apiKey,
                settings = OpenAIClientSettings(baseUrl = baseUrl.trimEnd('/')),
                httpClientFactory = httpClientFactory,
            ),
        )
}

/**
 * A Map<LLModel, String> that wraps the SDK's default Anthropic model versions
 * but NEVER throws for unknown models — it returns model.id directly.
 *
 * This allows ANY model ID (e.g., "claude-opus-4-8") to be sent to Anthropic's
 * API without the SDK rejecting it with "Unsupported model".
 * The API itself will validate whether the model exists.
 */
internal class PassThroughModelVersionsMap(
    private val delegate: Map<LLModel, String> = AnthropicClientSettings().modelVersionsMap,
) : Map<LLModel, String> by delegate {
    override fun get(key: LLModel): String {
        // Try the SDK's hardcoded mapping first, fall back to the model's own ID
        return delegate[key] ?: key.id
    }

    override fun containsKey(key: LLModel): Boolean = true
}
