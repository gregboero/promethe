package dev.promethe.gateway

import dev.promethe.api.*
import dev.promethe.core.*
import dev.promethe.core.hooks.HookManager
import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.gateway.providers.ProviderCatalogSource
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * StatusRoutes -- consolidated system health and status dashboard.
 *
 * All responses use typed @Serializable DTOs from StatusModels.kt.
 *
 * Provides:
 * - GET /api/v1/health -- simple liveness probe
 * - GET /api/v1/status -- aggregated system status (LLM, memory, hooks, scheduler, plugins)
 * - GET /api/v1/status/providers -- detailed provider health
 */
fun Route.statusRoutes(
    llmAdapter: KoogLlmAdapter,
    memoryLayer: MemoryLayer?,
    hookManager: HookManager?,
    taskScheduler: TaskScheduler?,
    taskExecutor: TaskExecutor? = null,
    pluginLoader: PluginLoader? = null,
    config: AgentConfig? = null,
    database: PrometheDatabaseApi? = null,
    providerCatalogSource: ProviderCatalogSource? = null,
    onSettingsReloaded: suspend () -> Unit = {},
) {
    val startTime = System.currentTimeMillis()

    // ── Simple liveness probe ──
    get("/health") {
        call.respond(
            HealthResponse(
                status = "healthy",
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    // ── Consolidated status dashboard ──
    get("/status") {
        try {
            val uptime = System.currentTimeMillis() - startTime
            val llmStats = llmAdapter.getStats()
            val hookList = hookManager?.listHooks() ?: emptyList()

            // Pre-fetch suspend values (buildJsonObject lambdas are not coroutine bodies)
            val factCount =
                if (memoryLayer != null) {
                    try {
                        memoryLayer.getAllFacts().size
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to fetch memory fact count" }
                        -1
                    }
                } else {
                    null
                }

            val cacheTotal = llmStats.cacheHits + llmStats.cacheMisses

            call.respond(
                SystemStatusResponse(
                    runtime =
                        RuntimeStatus(
                            uptimeMs = uptime,
                            uptimeHuman = formatUptime(uptime),
                            model = config?.modelName,
                            provider = config?.provider,
                            executionBackend = config?.executionBackend,
                        ),
                    llm =
                        LlmStatus(
                            totalRequests = llmStats.totalRequests,
                            totalPromptTokens = llmStats.promptTokens,
                            totalCompletionTokens = llmStats.completionTokens,
                            totalCostUsd = llmStats.totalCost,
                            cache =
                                CacheStatus(
                                    hits = llmStats.cacheHits,
                                    misses = llmStats.cacheMisses,
                                    size = llmStats.cacheSize,
                                    hitRate = if (cacheTotal > 0) llmStats.cacheHits.toDouble() / cacheTotal else 0.0,
                                ),
                        ),
                    memory =
                        if (memoryLayer != null && factCount != null) {
                            MemoryStatus(provider = memoryLayer.providerName, factCount = factCount)
                        } else {
                            null
                        },
                    hooks =
                        HooksStatus(
                            registeredCount = hookList.size,
                            hooks = hookList,
                        ),
                    scheduler =
                        if (taskScheduler != null) {
                            SchedulerStatus(
                                running = taskExecutor?.isRunning() ?: false,
                                taskCount = taskScheduler.getTaskCount(),
                            )
                        } else {
                            null
                        },
                    plugins =
                        pluginLoader?.let { loader ->
                            val ps = loader.getStatus()
                            PluginsStatus(
                                total = (ps["totalPlugins"] as? Int) ?: 0,
                                enabled = (ps["enabledPlugins"] as? Int) ?: 0,
                                tools = (ps["totalTools"] as? Int) ?: 0,
                            )
                        },
                ),
            )
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Status error"))
        }
    }

    // ── Provider health check ──
    get("/status/providers") {
        try {
            val router = llmAdapter.router
            val poolStats = llmAdapter.getPoolStats()
            val activeProvider = llmAdapter.currentProvider.lowercase()
            val activeModel = llmAdapter.currentModel

            // Load per-provider usage stats from DB
            val usageByProvider = try {
                database?.getLlmUsageByProvider() ?: emptyMap()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load provider usage stats" }
                emptyMap()
            }

            // Load credentials to discover all configured providers
            val credentials = CredentialsStore.load()
            val configuredKeys = credentials?.llmApiKeys ?: emptyMap()
            val configuredModels = credentials?.llmModels ?: emptyMap()

            // Merge: pool providers + credentials providers
            val allProviders = (poolStats.keys + configuredKeys.keys).distinct()

            call.respond(
                ProvidersResponse(
                    providers = allProviders.map { provider ->
                        val keyCount = poolStats[provider] ?: if (configuredKeys[provider]?.isNotBlank() == true) 1 else 0
                        val usage = usageByProvider[provider]
                        ProviderHealth(
                            name = provider,
                            keyPoolSize = keyCount,
                            hasExecutor = router?.hasExecutor(provider) ?: false,
                            status = when {
                                provider.lowercase() == activeProvider -> "active"
                                keyCount > 0 -> "configured"
                                else -> "no_keys"
                            },
                            isActive = provider.lowercase() == activeProvider,
                            model = if (provider.lowercase() == activeProvider) activeModel else configuredModels[provider],
                            totalRequests = usage?.requestCount ?: 0,
                            totalTokens = usage?.totalTokens ?: 0,
                            totalCost = usage?.totalCost ?: 0.0,
                        )
                    },
                ),
            )
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Provider check error"))
        }
    }

    // ── Test LLM provider connectivity ──
    post("/settings/test-llm") {
        @Serializable
        data class TestLlmRequest(
            val provider: String,
            val apiKey: String,
            val model: String? = null,
            val baseUrl: String? = null,
        )

        @Serializable
        data class TestLlmResponse(
            val ok: Boolean,
            val error: String? = null,
            val latencyMs: Long? = null,
        )
        val req = call.receive<TestLlmRequest>()
        val start = System.currentTimeMillis()
        try {
            val testUrl = when (req.provider.lowercase()) {
                "openai" -> "${req.baseUrl ?: "https://api.openai.com"}/v1/models"
                "anthropic" -> "https://api.anthropic.com/v1/models"
                "google" -> "https://generativelanguage.googleapis.com/v1/models?key=${req.apiKey}"
                "deepseek" -> "https://api.deepseek.com/v1/models"
                "openrouter" -> "https://openrouter.ai/api/v1/models"
                "nvidia" -> "https://integrate.api.nvidia.com/v1/models"
                "litellm" -> "${req.baseUrl ?: "http://localhost:4000"}/models"
                "ollama" -> "${req.baseUrl ?: "http://localhost:11434"}/api/tags"
                else -> return@post call.respond(TestLlmResponse(ok = false, error = "Unknown provider: ${req.provider}"))
            }
            val httpClient = HttpClient()
            val response = httpClient.get(testUrl) {
                if (req.provider.lowercase() != "google" && req.provider.lowercase() != "ollama") {
                    header("Authorization", "Bearer ${req.apiKey}")
                }
                if (req.provider.lowercase() == "anthropic") {
                    header("x-api-key", req.apiKey)
                    header("anthropic-version", "2023-06-01")
                }
            }
            httpClient.close()
            val latency = System.currentTimeMillis() - start
            if (response.status.value in 200..299) {
                call.respond(TestLlmResponse(ok = true, latencyMs = latency))
            } else {
                call.respond(TestLlmResponse(ok = false, error = "HTTP ${response.status.value}", latencyMs = latency))
            }
        } catch (e: Exception) {
            call.respond(TestLlmResponse(ok = false, error = e.message ?: "Connection failed"))
        }
    }

    post("/settings/reload") {
        try {
            val credentials = CredentialsStore.load()
            if (credentials != null) {
                val apiKeys = CredentialsStore.resolveApiKeys(credentials)
                llmAdapter.updateApiKeys(apiKeys)
                dev.promethe.core.LiveProviderKeys.replace(apiKeys)
                // Update only from a coherent provider/model pair. Provider defaults
                // are resolved here so an empty persisted model never reaches an API.
                val selection = LlmSelectionResolver.resolve(credentials)
                val selectedProvider = selection.provider?.takeIf { it.isNotBlank() }
                val selectedModel = selection.model?.takeIf { it.isNotBlank() }
                if (selectedProvider != null && selectedModel != null) {
                    llmAdapter.updateActiveModel(selectedProvider, selectedModel)
                    logger.info { "Live provider/model updated: ${llmAdapter.currentProvider}/${llmAdapter.currentModel}" }
                } else {
                    logger.warn {
                        "Live provider/model unchanged: incomplete selection " +
                            "${selection.provider.orEmpty()}/${selection.model.orEmpty()}"
                    }
                }
                val providerCount = providerCatalogSource?.reload()?.providers()?.size
                onSettingsReloaded()
                logger.info { "Live provider keys and capability router reloaded successfully" }
                call.respond(SettingsReloadResponse(providerCount = providerCount))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No credentials file found"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Reload failed"))
        }
    }

    // ── Hook-level metrics (tool calls, errors, sessions) ──
    get("/status/metrics") {
        val metricsHook = hookManager?.getHookById("builtin.metrics")
        if (metricsHook != null && metricsHook is dev.promethe.core.hooks.MetricsHook) {
            call.respond(metricsHook.getMetrics())
        } else {
            call.respond(mapOf("tool_calls" to emptyMap<String, Int>(), "error_count" to 0, "session_count" to 0))
        }
    }
}

@Serializable
private data class SettingsReloadResponse(
    val status: String = "ok",
    val providersReloaded: Boolean = true,
    val providerCount: Int? = null,
)

private fun formatUptime(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days > 0 -> "${days}d ${hours % 24}h ${minutes % 60}m"
        hours > 0 -> "${hours}h ${minutes % 60}m ${seconds % 60}s"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}
