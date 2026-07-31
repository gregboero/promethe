package dev.promethe.gateway.providers

import dev.promethe.core.CredentialsStore
import dev.promethe.core.ModelCapabilityRegistry
import dev.promethe.core.config.ConfigProvider
import ai.koog.prompt.llm.LLMCapability
import dev.promethe.api.providers.CertificationStatus
import dev.promethe.api.providers.ModelCapability
import dev.promethe.api.providers.ModelDescriptor
import dev.promethe.api.providers.ModelLifecycle
import dev.promethe.api.providers.ModelMaturity
import dev.promethe.api.providers.ProviderAvailability
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ProviderAccess(
    val apiKey: String? = null,
    val baseUrl: String? = null,
)

fun interface ProviderCredentialResolver {
    fun resolve(providerId: String): ProviderAccess
}

/** Resolves secrets from the merged configuration without exposing them to catalog DTOs. */
object ConfigProviderCredentialResolver : ProviderCredentialResolver {
    private val apiKeyNames = mapOf(
        "openai" to "OPENAI_API_KEY",
        "anthropic" to "ANTHROPIC_API_KEY",
        "google" to "GOOGLE_API_KEY",
        "deepseek" to "DEEPSEEK_API_KEY",
        "nvidia" to "NVIDIA_NIM_API_KEY",
        "litellm" to "LITELLM_API_KEY",
        "openrouter" to "OPENROUTER_API_KEY",
        "kimi" to "MOONSHOT_API_KEY",
        "xai" to "XAI_API_KEY",
    )

    override fun resolve(providerId: String): ProviderAccess {
        val config = ConfigProvider.get()
        val credentials = CredentialsStore.load()
        val apiKey = apiKeyNames[providerId]?.let { value(config, it) }
            ?: credentials?.llmApiKeys?.get(providerId)?.takeIf { it.isNotBlank() }
            ?: credentials?.takeIf { it.llmProvider.equals(providerId, ignoreCase = true) }
                ?.llmApiKey?.takeIf { it.isNotBlank() }
            ?: if (providerId == "google") credentials?.googleAiKey?.takeIf { it.isNotBlank() } else null
        val baseUrl = when (providerId) {
            "ollama" -> {
                value(config, "OLLAMA_URL") ?: credentials?.ollamaUrl?.takeIf { it.isNotBlank() }
            }

            "openai" -> {
                value(config, "OPENAI_BASE_URL", "OPENAI_API_BASE")
            }

            "anthropic" -> {
                value(config, "ANTHROPIC_BASE_URL")
            }

            "google" -> {
                value(config, "GOOGLE_BASE_URL")
            }

            "deepseek" -> {
                value(config, "DEEPSEEK_BASE_URL")
            }

            "nvidia" -> {
                value(config, "NVIDIA_NIM_BASE_URL", "NIM_BASE_URL")
            }

            "litellm" -> {
                value(config, "LITELLM_BASE_URL", "LITELLM_URL")
                    ?: credentials?.takeIf { it.llmProvider.equals("litellm", ignoreCase = true) }
                        ?.ollamaUrl?.takeIf { it.isNotBlank() }
            }

            "openrouter" -> {
                value(config, "OPENROUTER_BASE_URL")
            }

            "kimi" -> {
                value(config, "MOONSHOT_BASE_URL") ?: "https://api.moonshot.ai"
            }

            "xai" -> {
                value(config, "XAI_BASE_URL") ?: "https://api.x.ai"
            }

            else -> {
                null
            }
        }
        return ProviderAccess(apiKey = apiKey, baseUrl = baseUrl)
    }

    private fun value(
        config: ConfigProvider,
        vararg keys: String,
    ): String? =
        keys.asSequence()
            .map { config.get(it, "") }
            .map(String::trim)
            .firstOrNull { it.isNotBlank() }
}

interface ProviderCatalogSource {
    suspend fun catalog(forceReload: Boolean = false): ProviderCatalog

    fun invalidate()

    suspend fun reload(): ProviderCatalog = catalog(forceReload = true)
}

/**
 * Live provider discovery with a six-hour cache and a last-valid snapshot fallback.
 * HTTP failures never replace a valid snapshot with an empty list.
 */
class DynamicProviderCatalogSource(
    private val httpClient: HttpClient = providerCatalogHttpClient(),
    private val credentials: ProviderCredentialResolver = ConfigProviderCredentialResolver,
    private val fallback: ProviderCatalog = ProviderCatalog.initial(),
    private val now: () -> Long = System::currentTimeMillis,
    private val cacheTtlMillis: Long = CACHE_TTL_MILLIS,
) : ProviderCatalogSource {
    private val mutex = Mutex()

    @Volatile
    private var cached: CachedCatalog? = null

    override suspend fun catalog(forceReload: Boolean): ProviderCatalog =
        mutex.withLock {
            val currentTime = now()
            val current = cached
            if (!forceReload && current != null && current.expiresAt > currentTime) {
                return@withLock current.catalog
            }

            val base = current?.catalog ?: fallback
            val refreshed = coroutineScope {
                base.entries().map { entry ->
                    async {
                        discoverySpecs[entry.provider.id]?.let { spec -> discover(entry, spec) } ?: entry
                    }
                }.awaitAll()
            }
            refreshed.forEach(::applyRuntimeModelPolicy)
            val result = ProviderCatalog.of(refreshed)
            cached = CachedCatalog(result, currentTime + cacheTtlMillis)
            result
        }

    override fun invalidate() {
        cached = cached?.copy(expiresAt = 0L)
    }

    private suspend fun discover(
        previous: ProviderCatalog.Entry,
        spec: DiscoverySpec,
    ): ProviderCatalog.Entry {
        val access = credentials.resolve(spec.providerId)
        val baseUrl = access.baseUrl ?: spec.defaultBaseUrl
        if (baseUrl.isNullOrBlank() || (spec.requiresApiKey && access.apiKey.isNullOrBlank())) {
            return mark(previous, ProviderAvailability.CONFIGURATION_REQUIRED)
        }

        return try {
            val discovered = discoverModels(spec, baseUrl, access)
            if (discovered == null) {
                mark(previous, previous.failureAvailability())
            } else {
                val models = materialize(spec.providerId, discovered)
                ProviderCatalog.Entry(
                    provider = previous.provider.copy(
                        availability = ProviderAvailability.AVAILABLE,
                        capabilities = models.flatMap { it.capabilities }.distinct().ifEmpty { previous.provider.capabilities },
                        maturity = discoveredMaturity(discovered),
                    ),
                    models = models,
                )
            }
        } catch (_: Exception) {
            mark(previous, previous.failureAvailability())
        }
    }

    private fun ProviderCatalog.Entry.failureAvailability(): ProviderAvailability =
        if (
            provider.availability == ProviderAvailability.AVAILABLE ||
            provider.availability == ProviderAvailability.STALE
        ) {
            ProviderAvailability.STALE
        } else {
            ProviderAvailability.UNAVAILABLE
        }

    private suspend fun discoverModels(
        spec: DiscoverySpec,
        baseUrl: String,
        access: ProviderAccess,
    ): List<RawModel>? {
        if (spec.providerId == "litellm") {
            val info = fetchJson(baseUrl, "/model/info", spec.authScheme, access.apiKey)
            val infoModels = info?.let(::parseLiteLlmInfo).orEmpty()
            if (infoModels.isNotEmpty()) return infoModels
        }

        val response = fetchJson(baseUrl, spec.path, spec.authScheme, access.apiKey) ?: return null
        return parseModels(spec.providerId, response)
    }

    private suspend fun fetchJson(
        baseUrl: String,
        path: String,
        authScheme: AuthScheme,
        apiKey: String?,
    ): JsonObject? {
        val response = httpClient.get {
            url(joinUrl(baseUrl, path))
            expectSuccess = false
            if (!apiKey.isNullOrBlank()) {
                when (authScheme) {
                    AuthScheme.BEARER -> {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                    }

                    AuthScheme.ANTHROPIC -> {
                        header("x-api-key", apiKey)
                        header("anthropic-version", "2023-06-01")
                    }

                    AuthScheme.GOOGLE -> {
                        header("x-goog-api-key", apiKey)
                    }
                }
            }
            headers { header(HttpHeaders.Accept, "application/json") }
        }
        if (response.status != HttpStatusCode.OK) return null
        return runCatching { Json.parseToJsonElement(response.bodyAsText()) as? JsonObject }.getOrNull()
    }

    private fun parseModels(
        providerId: String,
        root: JsonObject,
    ): List<RawModel> =
        when (providerId) {
            "google" -> {
                root.array("models").orEmpty().mapNotNull { it.asObject()?.toRawModel(google = true) }
            }

            else -> {
                root.array("data", "models").orEmpty().mapNotNull { it.asObject()?.toRawModel() }
            }
        }

    private fun parseLiteLlmInfo(root: JsonObject): List<RawModel> {
        val data = root.array("data")
            ?: root.values.mapNotNull { it.asObject() }.let { JsonArray(it) }
        return data.mapNotNull { it.asObject()?.toRawModel() }
    }

    private fun materialize(
        providerId: String,
        discovered: List<RawModel>,
    ): List<ModelDescriptor> {
        val certified = fallback.modelsFor(providerId).orEmpty().associateBy { it.id }
        val descriptors = discovered.distinctBy { it.id }.map { raw ->
            val known = certified[raw.id]
            if (known != null) {
                known.copy(
                    providerId = providerId,
                    availability = ProviderAvailability.AVAILABLE,
                    contextWindowTokens = raw.contextWindowTokens ?: known.contextWindowTokens,
                    maxOutputTokens = raw.maxOutputTokens ?: known.maxOutputTokens,
                    supportedParameters = raw.supportedParameters,
                    modalities = raw.modalities,
                    expiresAt = raw.expiresAt,
                )
            } else {
                ModelDescriptor(
                    id = raw.id,
                    displayName = raw.displayName,
                    providerId = providerId,
                    capabilities = raw.capabilities,
                    lifecycle = raw.lifecycle,
                    certificationStatus = CertificationStatus.UNVERIFIED,
                    availability = ProviderAvailability.AVAILABLE,
                    maturity = ModelMaturity.BETA,
                    contextWindowTokens = raw.contextWindowTokens,
                    maxOutputTokens = raw.maxOutputTokens,
                    supportedParameters = raw.supportedParameters,
                    modalities = raw.modalities,
                    expiresAt = raw.expiresAt,
                )
            }
        }
        return descriptors
    }

    private fun applyRuntimeModelPolicy(entry: ProviderCatalog.Entry) {
        entry.models.forEach { descriptor ->
            val capabilities = buildList {
                if (descriptor.supportedParameters.any { it.equals("temperature", ignoreCase = true) }) {
                    add(LLMCapability.Temperature)
                }
                if (ModelCapability.TOOL_CALLING in descriptor.capabilities) add(LLMCapability.Tools)
                if (ModelCapability.REASONING in descriptor.capabilities) add(LLMCapability.Thinking)
                if (ModelCapability.VISION in descriptor.capabilities) add(LLMCapability.Vision.Image)
            }
            ModelCapabilityRegistry.learnCapabilities(entry.provider.id, descriptor.id, capabilities)
        }
    }

    private fun discoveredMaturity(models: List<RawModel>): ModelMaturity = if (models.any { it.lifecycle == ModelLifecycle.PREVIEW }) ModelMaturity.PREVIEW else ModelMaturity.BETA

    private fun mark(
        previous: ProviderCatalog.Entry,
        availability: ProviderAvailability,
    ): ProviderCatalog.Entry =
        ProviderCatalog.Entry(
            provider = previous.provider.copy(availability = availability),
            models = previous.models.map { it.copy(availability = availability) },
        )

    private data class CachedCatalog(
        val catalog: ProviderCatalog,
        val expiresAt: Long,
    )

    private data class DiscoverySpec(
        val providerId: String,
        val path: String,
        val defaultBaseUrl: String?,
        val authScheme: AuthScheme,
        val requiresApiKey: Boolean = true,
    )

    private enum class AuthScheme {
        BEARER,
        ANTHROPIC,
        GOOGLE,
    }

    private data class RawModel(
        val id: String,
        val displayName: String,
        val capabilities: List<ModelCapability>,
        val lifecycle: ModelLifecycle = ModelLifecycle.ACTIVE,
        val contextWindowTokens: Int? = null,
        val maxOutputTokens: Int? = null,
        val supportedParameters: List<String> = emptyList(),
        val modalities: List<String> = emptyList(),
        val expiresAt: String? = null,
    )

    companion object {
        const val CACHE_TTL_MILLIS: Long = 6 * 60 * 60 * 1000L

        private val discoverySpecs: Map<String, DiscoverySpec> = mapOf(
            "ollama" to DiscoverySpec("ollama", "/api/tags", "http://localhost:11434", AuthScheme.BEARER, requiresApiKey = false),
            "openai" to DiscoverySpec("openai", "/v1/models", "https://api.openai.com", AuthScheme.BEARER),
            "anthropic" to DiscoverySpec("anthropic", "/v1/models", "https://api.anthropic.com", AuthScheme.ANTHROPIC),
            "google" to DiscoverySpec("google", "/v1beta/models", "https://generativelanguage.googleapis.com", AuthScheme.GOOGLE),
            "deepseek" to DiscoverySpec("deepseek", "/models", "https://api.deepseek.com", AuthScheme.BEARER),
            "nvidia" to DiscoverySpec("nvidia", "/v1/models", "https://integrate.api.nvidia.com", AuthScheme.BEARER),
            "litellm" to DiscoverySpec("litellm", "/v1/models", null, AuthScheme.BEARER, requiresApiKey = false),
            "openrouter" to DiscoverySpec("openrouter", "/api/v1/models", "https://openrouter.ai", AuthScheme.BEARER),
            "kimi" to DiscoverySpec("kimi", "/v1/models", "https://api.moonshot.ai", AuthScheme.BEARER),
            "xai" to DiscoverySpec("xai", "/v1/models", "https://api.x.ai", AuthScheme.BEARER),
        )

        private fun joinUrl(
            baseUrl: String,
            path: String,
        ): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

        private fun JsonObject.array(vararg names: String): JsonArray? = names.asSequence().mapNotNull { this[it] as? JsonArray }.firstOrNull()

        private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

        private fun JsonObject.toRawModel(google: Boolean = false): RawModel? {
            val liteParams = obj("litellm_params")
            val modelInfo = obj("model_info")
            val id = text("id", "name", "model_name")
                ?: liteParams?.text("model")
                ?: return null
            val normalizedId = if (google) id.removePrefix("models/") else id
            val displayName = text("display_name", "displayName")
                ?: if (google) text("name")?.removePrefix("models/") ?: normalizedId else normalizedId
            val supported = strings("supported_parameters")
                .ifEmpty { strings("supportedGenerationMethods") }
                .ifEmpty { modelInfo?.strings("supported_parameters").orEmpty() }
            val modalities = (
                strings("modalities") + strings("input_modalities") + strings("output_modalities") +
                    obj("architecture")?.strings("input_modalities").orEmpty() +
                    obj("architecture")?.strings("output_modalities").orEmpty()
            ).distinct()
            val capabilities = capabilitiesFor(normalizedId, supported, modalities, google)
            val lifecycle = if (normalizedId.contains("preview", ignoreCase = true)) ModelLifecycle.PREVIEW else ModelLifecycle.ACTIVE
            return RawModel(
                id = normalizedId,
                displayName = displayName,
                capabilities = capabilities,
                lifecycle = lifecycle,
                contextWindowTokens = int("context_window", "contextWindowTokens", "context_length", "inputTokenLimit")
                    ?: modelInfo?.int("context_window", "context_length"),
                maxOutputTokens = int("max_output_tokens", "max_completion_tokens", "outputTokenLimit")
                    ?: modelInfo?.int("max_output_tokens", "max_tokens"),
                supportedParameters = supported,
                modalities = modalities,
                expiresAt = text("expiration_date", "expires_at", "expiration", "expires")
                    ?: modelInfo?.text("expiration_date", "expires_at"),
            )
        }

        private fun capabilitiesFor(
            id: String,
            supportedParameters: List<String>,
            modalities: List<String>,
            google: Boolean,
        ): List<ModelCapability> {
            val lowerId = id.lowercase()
            val lowerSupported = supportedParameters.map(String::lowercase)
            val lowerModalities = modalities.map(String::lowercase)
            return buildList {
                if (
                    lowerId.contains("embed") ||
                    lowerSupported.any { it.contains("embed") } ||
                    (google && lowerSupported.any { it.contains("embedcontent") })
                ) {
                    add(ModelCapability.EMBEDDINGS)
                } else {
                    add(
                        if (
                            lowerId.contains("reason") ||
                            lowerId.startsWith("o3") ||
                            lowerId.startsWith("o4") ||
                            lowerId == "kimi-k3" ||
                            lowerId == "grok-4.5"
                        ) {
                            ModelCapability.REASONING
                        } else {
                            ModelCapability.CHAT
                        },
                    )
                }
                if (lowerModalities.any { it.contains("image") }) add(ModelCapability.VISION)
                if (lowerModalities.any { it.contains("audio") }) add(ModelCapability.AUDIO_INPUT)
                if (lowerSupported.any { it.contains("tool") || it.contains("function") }) add(ModelCapability.TOOL_CALLING)
                if (lowerSupported.any { it.contains("json") || it.contains("structured") }) add(ModelCapability.STRUCTURED_OUTPUT)
            }.distinct()
        }

        private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

        private fun JsonObject.text(vararg names: String): String? = names.asSequence().mapNotNull { name -> primitive(name)?.contentOrNull?.takeIf { it.isNotBlank() } }.firstOrNull()

        private fun JsonObject.strings(name: String): List<String> = (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

        private fun JsonObject.int(vararg names: String): Int? = names.asSequence().mapNotNull { primitive(it)?.contentOrNull?.toIntOrNull() }.firstOrNull()

        private fun JsonObject.primitive(name: String): JsonPrimitive? = this[name] as? JsonPrimitive
    }
}

object DefaultProviderCatalogSource {
    val instance: DynamicProviderCatalogSource by lazy { DynamicProviderCatalogSource() }
}

private fun providerCatalogHttpClient(): HttpClient =
    HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 5_000
            connectTimeoutMillis = 3_000
            socketTimeoutMillis = 5_000
        }
    }
