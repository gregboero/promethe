package dev.promethe.core

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Fetches and caches model pricing dynamically from provider APIs.
 * - LiteLLM: /model/info (self-hosted, preferred)
 * - OpenRouter: /api/v1/models (prompt & completion per token)
 * - Ollama: free (local)
 * - Others: fallback to known pricing or defaults
 */
class ModelPricingService(
    private val httpClient: HttpClient,
    private val litellmBaseUrl: String = "",
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    private val mutex = Mutex()

    // Cache: modelId -> (promptPricePerMillion, completionPricePerMillion)
    private val pricingCache = mutableMapOf<String, Pair<Double, Double>>()
    private var lastFetchMs = 0L
    private val cacheTtlMs = 3_600_000L // 1 hour

    /**
     * Get pricing for a model. Returns (promptPricePerMillion, completionPricePerMillion).
     * Fetches from provider API on first call, then caches.
     */
    suspend fun getPricing(
        provider: String,
        modelId: String,
        apiKey: String = "",
    ): Pair<Double, Double> {
        // Ollama is always free
        if (provider.equals("ollama", ignoreCase = true)) {
            return Pair(0.0, 0.0)
        }

        // Check cache
        val cacheKey = "$provider:$modelId"
        mutex.withLock {
            pricingCache[cacheKey]?.let { return it }
        }

        // Fetch from provider
        val pricing =
            when (provider.lowercase()) {
                "litellm" -> {
                    fetchLiteLLMPricing(modelId, apiKey)
                }

                "openrouter" -> {
                    fetchOpenRouterPricing(modelId, apiKey)
                }

                "openai" -> {
                    fetchOpenAIPricing(modelId, apiKey)
                }

                "anthropic" -> {
                    fetchAnthropicPricing(modelId)
                }

                "google" -> {
                    fetchGooglePricing(modelId)
                }

                "deepseek" -> {
                    fetchDeepSeekPricing(modelId, apiKey)
                }

                "nvidia" -> {
                    fetchOpenRouterPricing(modelId, apiKey)
                }

                "kimi", "xai" -> {
                    KNOWN_PRICING[modelId] ?: DEFAULT_PRICING
                }

                else -> {
                    // If LiteLLM is configured, try it first
                    if (litellmBaseUrl.isNotBlank()) {
                        fetchLiteLLMPricing(modelId, apiKey)
                    } else {
                        fetchOpenRouterPricing(modelId, apiKey)
                    }
                }
            }

        // Cache result
        mutex.withLock {
            pricingCache[cacheKey] = pricing
        }
        return pricing
    }

    /**
     * Fetch all models and pricing.
     * Prefers LiteLLM /model/info if configured, else falls back to OpenRouter.
     */
    suspend fun fetchAllModels(apiKey: String): List<ModelInfo> {
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - lastFetchMs < cacheTtlMs) {
            return emptyList() // Cache still valid
        }

        // Try LiteLLM first
        if (litellmBaseUrl.isNotBlank()) {
            val litellmModels = fetchLiteLLMModels(apiKey)
            if (litellmModels.isNotEmpty()) return litellmModels
        }

        // Fallback to OpenRouter
        return fetchOpenRouterModels(apiKey)
    }

    /**
     * Fetch model list from LiteLLM proxy /model/info endpoint.
     * Returns pricing, context window, and provider info for all configured models.
     */
    private suspend fun fetchLiteLLMModels(apiKey: String): List<ModelInfo> {
        val baseUrl = litellmBaseUrl.trimEnd('/')
        return try {
            val response =
                httpClient.get("$baseUrl/model/info") {
                    if (apiKey.isNotBlank()) {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                    }
                }
            val body = response.body<String>()
            val modelsResponse = json.decodeFromString<LiteLLMModelInfoResponse>(body)

            mutex.withLock {
                lastFetchMs = Clock.System.now().toEpochMilliseconds()
                modelsResponse.data.forEach { model ->
                    val promptPrice = model.modelInfo?.inputCostPerToken?.let { it * 1_000_000 } ?: 0.0
                    val completionPrice = model.modelInfo?.outputCostPerToken?.let { it * 1_000_000 } ?: 0.0
                    pricingCache["litellm:${model.modelName}"] = Pair(promptPrice, completionPrice)
                }
            }

            modelsResponse.data.map { model ->
                ModelInfo(
                    id = model.modelName,
                    name = model.modelName,
                    provider = "litellm",
                    promptPricePerMillion = model.modelInfo?.inputCostPerToken?.let { it * 1_000_000 } ?: 0.0,
                    completionPricePerMillion = model.modelInfo?.outputCostPerToken?.let { it * 1_000_000 } ?: 0.0,
                    contextLength = model.modelInfo?.maxInputTokens ?: 0,
                    maxCompletionTokens = model.modelInfo?.maxOutputTokens,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchLiteLLMPricing(
        modelId: String,
        apiKey: String,
    ): Pair<Double, Double> {
        val baseUrl = litellmBaseUrl.trimEnd('/')
        if (baseUrl.isBlank()) return KNOWN_PRICING[modelId] ?: DEFAULT_PRICING

        return try {
            val response =
                httpClient.get("$baseUrl/model/info") {
                    if (apiKey.isNotBlank()) {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                    }
                }
            val body = response.body<String>()
            val modelsResponse = json.decodeFromString<LiteLLMModelInfoResponse>(body)

            val model = modelsResponse.data.find { it.modelName == modelId }
            if (model?.modelInfo != null) {
                val prompt = (model.modelInfo.inputCostPerToken ?: 0.0) * 1_000_000
                val completion = (model.modelInfo.outputCostPerToken ?: 0.0) * 1_000_000

                // Cache all
                mutex.withLock {
                    modelsResponse.data.forEach { m ->
                        val p = (m.modelInfo?.inputCostPerToken ?: 0.0) * 1_000_000
                        val c = (m.modelInfo?.outputCostPerToken ?: 0.0) * 1_000_000
                        pricingCache["litellm:${m.modelName}"] = Pair(p, c)
                    }
                }

                Pair(prompt, completion)
            } else {
                KNOWN_PRICING[modelId] ?: DEFAULT_PRICING
            }
        } catch (e: Exception) {
            KNOWN_PRICING[modelId] ?: DEFAULT_PRICING
        }
    }

    private suspend fun fetchOpenRouterModels(apiKey: String): List<ModelInfo> =
        try {
            val response =
                httpClient.get("https://openrouter.ai/api/v1/models") {
                    if (apiKey.isNotBlank()) {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                    }
                }
            val body = response.body<String>()
            val modelsResponse = json.decodeFromString<OpenRouterModelsResponse>(body)

            mutex.withLock {
                lastFetchMs = Clock.System.now().toEpochMilliseconds()
                modelsResponse.data.forEach { model ->
                    val prompt = (model.pricing?.prompt?.toDoubleOrNull() ?: 0.0) * 1_000_000
                    val completion = (model.pricing?.completion?.toDoubleOrNull() ?: 0.0) * 1_000_000
                    pricingCache["openrouter:${model.id}"] = Pair(prompt, completion)
                }
            }

            modelsResponse.data.map { model ->
                ModelInfo(
                    id = model.id,
                    name = model.name,
                    provider = "openrouter",
                    promptPricePerMillion = (model.pricing?.prompt?.toDoubleOrNull() ?: 0.0) * 1_000_000,
                    completionPricePerMillion = (model.pricing?.completion?.toDoubleOrNull() ?: 0.0) * 1_000_000,
                    contextLength = model.contextLength ?: 0,
                    maxCompletionTokens = model.topProvider?.maxCompletionTokens,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }

    private suspend fun fetchOpenRouterPricing(
        modelId: String,
        apiKey: String,
    ): Pair<Double, Double> =
        try {
            val response =
                httpClient.get("https://openrouter.ai/api/v1/models") {
                    if (apiKey.isNotBlank()) {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                    }
                }
            val body = response.body<String>()
            val modelsResponse = json.decodeFromString<OpenRouterModelsResponse>(body)

            // Find our model
            val model = modelsResponse.data.find { it.id == modelId }
            if (model?.pricing != null) {
                // OpenRouter returns price per token, convert to per million
                val prompt = (model.pricing.prompt?.toDoubleOrNull() ?: 0.0) * 1_000_000
                val completion = (model.pricing.completion?.toDoubleOrNull() ?: 0.0) * 1_000_000

                // Cache all models while we're at it
                mutex.withLock {
                    lastFetchMs = Clock.System.now().toEpochMilliseconds()
                    modelsResponse.data.forEach { m ->
                        val p = (m.pricing?.prompt?.toDoubleOrNull() ?: 0.0) * 1_000_000
                        val c = (m.pricing?.completion?.toDoubleOrNull() ?: 0.0) * 1_000_000
                        pricingCache["openrouter:${m.id}"] = Pair(p, c)
                    }
                }

                Pair(prompt, completion)
            } else {
                DEFAULT_PRICING
            }
        } catch (e: Exception) {
            DEFAULT_PRICING
        }

    private suspend fun fetchOpenAIPricing(
        modelId: String,
        apiKey: String,
    ): Pair<Double, Double> {
        // OpenAI doesn't expose pricing via API — use known defaults
        return KNOWN_PRICING[modelId] ?: DEFAULT_PRICING
    }

    private fun fetchAnthropicPricing(modelId: String): Pair<Double, Double> = KNOWN_PRICING[modelId] ?: DEFAULT_PRICING

    private fun fetchGooglePricing(modelId: String): Pair<Double, Double> = KNOWN_PRICING[modelId] ?: DEFAULT_PRICING

    private suspend fun fetchDeepSeekPricing(
        modelId: String,
        apiKey: String,
    ): Pair<Double, Double> {
        // DeepSeek uses OpenAI-compatible API, pricing not exposed — use known
        return KNOWN_PRICING[modelId] ?: KNOWN_PRICING["deepseek-chat"] ?: DEFAULT_PRICING
    }

    companion object {
        val DEFAULT_PRICING = Pair(1.0, 1.0)

        // Fallback pricing for providers that don't expose pricing APIs
        // Updated periodically — prices per million tokens
        val KNOWN_PRICING =
            mapOf(
                // OpenAI
                "gpt-4o" to Pair(2.50, 10.0),
                "gpt-4o-mini" to Pair(0.15, 0.60),
                "gpt-4-turbo" to Pair(10.0, 30.0),
                "o1" to Pair(15.0, 60.0),
                "o1-mini" to Pair(3.0, 12.0),
                "o3-mini" to Pair(1.10, 4.40),
                // Anthropic
                "claude-sonnet-4-20250514" to Pair(3.0, 15.0),
                "claude-3-5-sonnet-20241022" to Pair(3.0, 15.0),
                "claude-3-5-haiku-20241022" to Pair(0.80, 4.0),
                "claude-3-opus-20240229" to Pair(15.0, 75.0),
                // Google
                "gemini-2.5-pro" to Pair(1.25, 10.0),
                "gemini-2.5-flash" to Pair(0.15, 0.60),
                "gemini-2.0-flash" to Pair(0.10, 0.40),
                // DeepSeek
                "deepseek-chat" to Pair(0.27, 1.10),
                "deepseek-reasoner" to Pair(0.55, 2.19),
                "deepseek/deepseek-chat-v3-0324" to Pair(0.27, 1.10),
                "deepseek/deepseek-r1" to Pair(0.55, 2.19),
                // Kimi and xAI conservative public fallback rates
                "kimi-k3" to Pair(3.0, 15.0),
                "grok-4.5" to Pair(4.0, 12.0),
                // NVIDIA Nemotron
                "nvidia/nemotron-4-340b-instruct" to Pair(4.20, 4.20),
                "nvidia/llama-3.1-nemotron-70b-instruct" to Pair(0.35, 0.40),
                "nvidia/llama-3.3-nemotron-super-49b-v1" to Pair(0.20, 0.20),
                // Meta Llama
                "meta-llama/llama-3.1-405b-instruct" to Pair(3.50, 3.50),
                "meta-llama/llama-3.1-70b-instruct" to Pair(0.52, 0.75),
                // NousResearch
                "nousresearch/hermes-3-llama-3.1-405b" to Pair(3.50, 3.50),
            )
    }
}

// --- LiteLLM API response models ---

@Serializable
data class LiteLLMModelInfoResponse(
    val data: List<LiteLLMModelEntry> = emptyList(),
)

@Serializable
data class LiteLLMModelEntry(
    @SerialName("model_name") val modelName: String,
    @SerialName("model_info") val modelInfo: LiteLLMModelDetail? = null,
)

@Serializable
data class LiteLLMModelDetail(
    @SerialName("input_cost_per_token") val inputCostPerToken: Double? = null,
    @SerialName("output_cost_per_token") val outputCostPerToken: Double? = null,
    @SerialName("max_input_tokens") val maxInputTokens: Int? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

// --- OpenRouter API response models ---

@Serializable
data class OpenRouterModelsResponse(
    val data: List<OpenRouterModel>,
)

@Serializable
data class OpenRouterModel(
    val id: String,
    val name: String = "",
    val pricing: OpenRouterPricing? = null,
    @SerialName("context_length") val contextLength: Int? = null,
    @SerialName("top_provider") val topProvider: OpenRouterTopProvider? = null,
)

@Serializable
data class OpenRouterPricing(
    val prompt: String? = null,
    val completion: String? = null,
    val image: String? = null,
    val request: String? = null,
)

@Serializable
data class OpenRouterTopProvider(
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
)

// --- Domain model ---

data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val promptPricePerMillion: Double,
    val completionPricePerMillion: Double,
    val contextLength: Int,
    val maxCompletionTokens: Int? = null,
)
