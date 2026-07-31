package dev.promethe.app.config

/**
 * Cross-platform credential persistence.
 *
 * Desktop/Android: delegates to CredentialsStore (file-based).
 * WasmJS/iOS: in-memory only (credentials entered each session).
 */
@kotlinx.serialization.Serializable
data class AppCredentials(
    val apiKey: String = "",
    val llmProvider: String = "openrouter",
    val llmApiKey: String = "",
    val llmModel: String = "",
    // Per-provider keys and models (key = provider id like "openai", "anthropic", etc.)
    val llmApiKeys: Map<String, String> = emptyMap(),
    val llmModels: Map<String, String> = emptyMap(),
    val gatewayUrl: String = "http://localhost:8080",
    /** Last remote URL only. No remote session token is ever persisted. */
    val lastRemoteGatewayUrl: String = "",
    val ollamaUrl: String = "http://localhost:11434",
    // ── Agent Parameters ──
    val temperature: Double = 0.2,
    val maxTokens: Int = 4096,
    val maxIterations: Int = 10,
    // ── Execution Backend ──
    val executionBackend: String = "local", // native sandbox, docker, singularity, modal, daytona
    val executionTimeoutMs: Long = 30_000,
    val maxOutputBytes: Int = 50_000,
    val dockerImage: String = "",
    // ── SSH ──
    val sshHost: String = "",
    val sshUser: String = "",
    val sshKeyPath: String = "",
    val sshPort: Int = 22,
    // ── Context Window ──
    val maxContextTokens: Int = 100_000,
    val compressionThreshold: Double = 0.8,
    // ── Memory ──
    val memoryEnabled: Boolean = true,
    val memoryProvider: String = "embedded", // embedded, honcho, tencent
    val honchoBaseUrl: String = "",
    val honchoApiKey: String = "",
    val tencentMemoryUrl: String = "",
    val tencentMemoryServiceId: String = "",
    val tencentMemoryApiKey: String = "",
    // ── Observability ──
    val tracingBackend: String = "console", // console, langfuse, otlp
    val langfusePublicKey: String = "",
    val langfuseSecretKey: String = "",
    val langfuseHost: String = "https://cloud.langfuse.com",
    val otlpEndpoint: String = "",
    // ── Security ──
    val approvalMode: String = "dangerous", // auto, dangerous, all
    val approvalTimeoutMs: Long = 120_000,
    // ── Remote Access ──
    val remoteUser: String = "",
    // ── GEPA ──
    val gepaEnabled: Boolean = false,
    val gepaIntervalMinutes: Long = 60,
    val gepaAutoApply: Boolean = false,
    // ── Personality ──
    val systemPrompt: String = "",
    // ── UI ──
    val theme: String = "system", // dark, light, system
    val language: String = "system", // system, en, fr
)

/**
 * Platform-specific credential manager.
 * Implemented via expect/actual for each target.
 */
expect object CredentialManager {
    /** Load saved credentials, or null if not configured. */
    fun load(): AppCredentials?

    /** Save credentials to persistent storage. */
    fun save(credentials: AppCredentials)

    /** Check if LLM credentials are configured. */
    fun isConfigured(): Boolean

    /** Remove the non-secret browser session marker after logout or expiry. */
    fun clearRemoteSessionHint()
}
