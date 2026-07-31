package dev.promethe.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Health ──────────────────────────────────────────────────

@Serializable
data class HealthResponse(
    val status: String = "healthy",
    val timestamp: Long = 0,
)

// ── System Status (consolidated dashboard) ──────────────────

@Serializable
data class SystemStatusResponse(
    val runtime: RuntimeStatus,
    val llm: LlmStatus,
    val memory: MemoryStatus? = null,
    val hooks: HooksStatus,
    val scheduler: SchedulerStatus? = null,
    val plugins: PluginsStatus? = null,
)

@Serializable
data class RuntimeStatus(
    @SerialName("uptime_ms") val uptimeMs: Long,
    @SerialName("uptime_human") val uptimeHuman: String,
    val version: String = PrometheVersion.CURRENT,
    val model: String? = null,
    val provider: String? = null,
    @SerialName("execution_backend") val executionBackend: String? = null,
)

@Serializable
data class LlmStatus(
    @SerialName("total_requests") val totalRequests: Int,
    @SerialName("total_prompt_tokens") val totalPromptTokens: Int,
    @SerialName("total_completion_tokens") val totalCompletionTokens: Int,
    @SerialName("total_cost_usd") val totalCostUsd: Double,
    val cache: CacheStatus,
)

@Serializable
data class CacheStatus(
    val hits: Long,
    val misses: Long,
    val size: Int,
    @SerialName("hit_rate") val hitRate: Double,
)

@Serializable
data class MemoryStatus(
    val provider: String,
    @SerialName("fact_count") val factCount: Int,
)

@Serializable
data class HooksStatus(
    @SerialName("registered_count") val registeredCount: Int,
    val hooks: List<String>,
)

@Serializable
data class SchedulerStatus(
    val running: Boolean,
    @SerialName("task_count") val taskCount: Int,
)

@Serializable
data class PluginsStatus(
    val total: Int,
    val enabled: Int,
    val tools: Int,
)

// ── Provider Health ─────────────────────────────────────────

@Serializable
data class ProvidersResponse(
    val providers: List<ProviderHealth>,
)

@Serializable
data class ProviderHealth(
    val name: String,
    @SerialName("key_pool_size") val keyPoolSize: Int,
    @SerialName("has_executor") val hasExecutor: Boolean,
    val status: String,
    @SerialName("is_active") val isActive: Boolean = false,
    val model: String? = null,
    @SerialName("total_requests") val totalRequests: Int = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("total_cost") val totalCost: Double = 0.0,
)

// ── Error ───────────────────────────────────────────────────

@Serializable
data class ErrorResponse(
    val error: String,
)
