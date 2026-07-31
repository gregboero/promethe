package dev.promethe.core

import dev.promethe.core.config.ConfigProvider
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

/**
 * Persistent credential store at `~/.promethe/credentials.json`.
 *
 * Stores:
 * - API key for gateway auth (auto-generated, prefixed `pk-prom-`)
 * - LLM provider config + API key
 * - Gateway URL (for remote mode)
 */
object CredentialsStore {
    @Serializable
    data class Credentials(
        val apiKey: String = "",
        val remoteUser: String = "",
        val remotePasswordHash: String = "",
        val remotePasswordSalt: String = "",
        val llmProvider: String = "openrouter",
        val llmApiKey: String = "",
        val llmModel: String = "",
        // Per-provider keys and models
        val llmApiKeys: Map<String, String> = emptyMap(),
        /** Canonical non-LLM and media provider secrets, keyed by ProviderSecretRegistry id. */
        val providerSecrets: Map<String, String> = emptyMap(),
        /** Validated non-provider settings persisted by the `/config/env` API. */
        val runtimeConfig: Map<String, String> = emptyMap(),
        val llmModels: Map<String, String> = emptyMap(),
        val gatewayUrl: String = "http://localhost:8080",
        // Remote owner sessions are database-backed and deliberately never live here.
        val lastRemoteGatewayUrl: String = "",
        val ollamaUrl: String = "http://localhost:11434",
        // ── Agent Parameters ──
        val temperature: Double = 0.2,
        val maxTokens: Int = 4096,
        val maxIterations: Int = 10,
        // ── Execution ──
        val executionBackend: String = "local",
        val executionTimeoutMs: Long = 30_000,
        val maxOutputBytes: Int = 50_000,
        val dockerImage: String = "",
        val sshHost: String = "",
        val sshUser: String = "",
        val sshKeyPath: String = "",
        val sshPort: Int = 22,
        // ── Context ──
        val maxContextTokens: Int = 100_000,
        val compressionThreshold: Double = 0.8,
        // ── Memory ──
        val memoryEnabled: Boolean = true,
        val memoryProvider: String = "embedded",
        val honchoBaseUrl: String = "",
        val honchoApiKey: String = "",
        val tencentMemoryUrl: String = "",
        val tencentMemoryServiceId: String = "",
        val tencentMemoryApiKey: String = "",
        // ── Observability ──
        val tracingBackend: String = "console",
        val langfusePublicKey: String = "",
        val langfuseSecretKey: String = "",
        val langfuseHost: String = "https://cloud.langfuse.com",
        val otlpEndpoint: String = "",
        // ── Approval and autonomy ──
        val approvalMode: String = "dangerous",
        val approvalTimeoutMs: Long = 120_000,
        val gepaEnabled: Boolean = false,
        val gepaIntervalMinutes: Long = 60,
        val gepaAutoApply: Boolean = false,
        // ── Personality ──
        val systemPrompt: String = "",
        // ── UI ──
        val theme: String = "system",
        val language: String = "system",
        // ── RAG / Knowledge Base ──
        val ragEnabled: Boolean = false,
        val ragEmbeddingProvider: String = "ollama",
        val ragEmbeddingModel: String = "nomic-embed-text",
        val ragEmbeddingBaseUrl: String = "http://localhost:11434",
        val ragEmbeddingApiKey: String = "",
        val ragEmbeddingDimensions: Int = 768,
        val ragVectorStoreType: String = "sqlite_vec",
        val ragVectorStoreUrl: String = "",
        val ragVectorStoreApiKey: String = "",
        val ragVectorStorePath: String = "",
        val ragChunkSize: Int = 512,
        val ragChunkOverlap: Int = 50,
        // ── AI Media Providers ──
        val replicateApiToken: String = "",
        val falKey: String = "",
        val runwayApiKey: String = "",
        val stabilityApiKey: String = "",
        val elevenlabsApiKey: String = "",
        val deepgramApiKey: String = "",
        val googleAiKey: String = "", // Gemini vision/video (fallback to llmApiKeys["google"])
        // ── Web Search ──
        val tavilyApiKey: String = "",
        val searxngUrl: String = "",
        // ── Browser ──
        val browserBackend: String = "",
        // ── Voice ──
        val voiceS2sProvider: String = "",
        val voiceS2sModel: String = "",
        val voiceS2sVoice: String = "Puck",
        val voiceS2sEnabled: Boolean = true,
        val voiceTtsProvider: String = "",
        val voiceTtsModel: String = "",
        val voiceTtsVoice: String = "Puck",
        val voiceTtsEnabled: Boolean = true,
        val voiceSttProvider: String = "",
        val voiceSttModel: String = "",
        val voiceSttEnabled: Boolean = false,
        val voiceTranslateEnabled: Boolean = false,
        val voiceTranslateTargetLang: String = "fr",
    ) {
        fun hasRemoteAccess(): Boolean = remoteUser.isNotBlank() && remotePasswordHash.isNotBlank()

        /**
         * Export credentials as a flat key-value map compatible with ConfigProvider lookups.
         * Maps typed fields to the env-var-style keys that tools and subsystems expect.
         */
        fun toEnvMap(): Map<String, String> =
            buildMap {
                // LLM API keys (env-var style overrides)
                llmApiKeys["openai"]?.takeIf { it.isNotBlank() }?.let { put("OPENAI_API_KEY", it) }
                llmApiKeys["google"]?.takeIf { it.isNotBlank() }?.let { put("GOOGLE_API_KEY", it) }
                llmApiKeys["anthropic"]?.takeIf { it.isNotBlank() }?.let { put("ANTHROPIC_API_KEY", it) }
                llmApiKeys["deepseek"]?.takeIf { it.isNotBlank() }?.let { put("DEEPSEEK_API_KEY", it) }
                llmApiKeys["nvidia"]?.takeIf { it.isNotBlank() }?.let { put("NVIDIA_NIM_API_KEY", it) }
                llmApiKeys["litellm"]?.takeIf { it.isNotBlank() }?.let { put("LITELLM_API_KEY", it) }
                llmApiKeys["openrouter"]?.takeIf { it.isNotBlank() }?.let { put("OPENROUTER_API_KEY", it) }
                put("TEMPERATURE", temperature.toString())
                put("MAX_TOKENS", maxTokens.toString())
                put("EXEC_BACKEND", executionBackend)
                put("EXECUTION_TIMEOUT_MS", executionTimeoutMs.toString())
                put("MAX_OUTPUT_BYTES", maxOutputBytes.toString())
                if (dockerImage.isNotBlank()) put("DOCKER_IMAGE", dockerImage)
                if (sshHost.isNotBlank()) put("SSH_HOST", sshHost)
                if (sshUser.isNotBlank()) put("SSH_USER", sshUser)
                if (sshKeyPath.isNotBlank()) put("SSH_KEY_PATH", sshKeyPath)
                put("SSH_PORT", sshPort.toString())
                put("MAX_CONTEXT_TOKENS", maxContextTokens.toString())
                put("COMPRESSION_THRESHOLD", compressionThreshold.toString())
                put("MEMORY_ENABLED", memoryEnabled.toString())
                put("MEMORY_PROVIDER", memoryProvider)
                if (honchoBaseUrl.isNotBlank()) put("HONCHO_URL", honchoBaseUrl)
                if (honchoApiKey.isNotBlank()) put("HONCHO_API_KEY", honchoApiKey)
                if (tencentMemoryUrl.isNotBlank()) put("TENCENT_MEMORY_URL", tencentMemoryUrl)
                if (tencentMemoryServiceId.isNotBlank()) put("TENCENT_SERVICE_ID", tencentMemoryServiceId)
                if (tencentMemoryApiKey.isNotBlank()) put("TENCENT_MEMORY_API_KEY", tencentMemoryApiKey)
                put("TRACING_BACKEND", tracingBackend)
                if (langfusePublicKey.isNotBlank()) put("LANGFUSE_PUBLIC_KEY", langfusePublicKey)
                if (langfuseSecretKey.isNotBlank()) put("LANGFUSE_SECRET_KEY", langfuseSecretKey)
                if (langfuseHost.isNotBlank()) put("LANGFUSE_HOST", langfuseHost)
                if (otlpEndpoint.isNotBlank()) put("OTLP_ENDPOINT", otlpEndpoint)
                put("APPROVAL_MODE", approvalMode)
                put("APPROVAL_TIMEOUT_MS", approvalTimeoutMs.toString())
                put("GEPA_ENABLED", gepaEnabled.toString())
                put("GEPA_INTERVAL_MINUTES", gepaIntervalMinutes.toString())
                put("GEPA_AUTO_APPLY", gepaAutoApply.toString())
                // Web Search
                if (tavilyApiKey.isNotBlank()) put("TAVILY_API_KEY", tavilyApiKey)
                if (searxngUrl.isNotBlank()) put("SEARXNG_URL", searxngUrl)
                // Browser
                if (browserBackend.isNotBlank()) put("BROWSER_BACKEND", browserBackend)
                // Voice (lower_snake_case keys for voice subsystem compatibility)
                if (voiceS2sProvider.isNotBlank()) put("voice_s2s_provider", voiceS2sProvider)
                if (voiceS2sModel.isNotBlank()) put("voice_s2s_model", voiceS2sModel)
                if (voiceS2sVoice.isNotBlank()) put("voice_s2s_voice", voiceS2sVoice)
                put("voice_s2s_enabled", voiceS2sEnabled.toString())
                if (voiceTtsProvider.isNotBlank()) put("voice_tts_provider", voiceTtsProvider)
                if (voiceTtsModel.isNotBlank()) put("voice_tts_model", voiceTtsModel)
                if (voiceTtsVoice.isNotBlank()) put("voice_tts_voice", voiceTtsVoice)
                put("voice_tts_enabled", voiceTtsEnabled.toString())
                if (voiceSttProvider.isNotBlank()) put("voice_stt_provider", voiceSttProvider)
                if (voiceSttModel.isNotBlank()) put("voice_stt_model", voiceSttModel)
                put("voice_stt_enabled", voiceSttEnabled.toString())
                put("voice_translate_enabled", voiceTranslateEnabled.toString())
                if (voiceTranslateTargetLang.isNotBlank()) put("voice_translate_target_lang", voiceTranslateTargetLang)
                ProviderSecretRegistry.resolve(this@Credentials).forEach { (id, value) ->
                    ProviderSecretRegistry.definitions.firstOrNull { it.id == id }?.let { put(it.envKey, value) }
                }
                runtimeConfig.forEach { (key, value) ->
                    RuntimeConfigRegistry.canonicalKey(key)?.let { canonical ->
                        if (value.isNotBlank()) put(canonical, value)
                    }
                }
            }
    }

    private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}
    private val json = PromethePrettyJson
    private val ioLock = Any()

    // Resolved through PrometheHome on every access so tests can redirect via
    // the `promethe.home` system property instead of mutating the real
    // ~/.promethe/credentials.json.
    private val configDir: File get() = PrometheHome.dir
    private val configFile: File get() = File(configDir, "credentials.json")
    private val lockFile: File get() = File(configDir, ".credentials.lock")

    /**
     * Load credentials from disk. Returns null if not configured.
     */
    fun load(): Credentials? =
        synchronized(ioLock) {
            withFileLock {
                loadUnlocked()
            }
        }

    private fun loadUnlocked(): Credentials? {
        if (!configFile.exists()) return null
        return try {
            val decoded = json.decodeFromString(Credentials.serializer(), configFile.readText())
            val migrated =
                when (decoded.voiceS2sModel) {
                    "gemini-2.5-flash-preview-native-audio-dialog",
                    "gemini-2.0-flash-live-001",
                    -> decoded.copy(voiceS2sModel = "gemini-3.1-flash-live-preview")

                    else -> decoded
                }
            if (migrated != decoded) saveUnlocked(migrated)
            migrated
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Load credentials, creating defaults if missing.
     * Always ensures an API key exists.
     */
    fun loadOrCreate(): Credentials =
        synchronized(ioLock) {
            withFileLock {
                val existing = loadUnlocked()
                if (existing != null && existing.apiKey.isNotBlank()) {
                    // SHA-256 remote credentials are intentionally not migrated. Remote
                    // ownership is now Argon2id + session state in SQLite.
                    if (existing.remoteUser.isNotBlank() || existing.remotePasswordHash.isNotBlank() || existing.remotePasswordSalt.isNotBlank()) {
                        val sanitized = existing.copy(remoteUser = "", remotePasswordHash = "", remotePasswordSalt = "")
                        saveUnlocked(sanitized)
                        return@withFileLock sanitized
                    }
                    return@withFileLock existing
                }
                val credentials = existing?.copy(apiKey = generateApiKey()) ?: Credentials(apiKey = generateApiKey())
                saveUnlocked(credentials)
                credentials
            }
        }

    /**
     * Save credentials to disk. Creates the directory if needed.
     */
    fun save(credentials: Credentials) {
        synchronized(ioLock) {
            withFileLock {
                saveUnlocked(credentials)
            }
        }
    }

    fun update(transform: (Credentials) -> Credentials): Credentials =
        synchronized(ioLock) {
            withFileLock {
                val updated = transform(loadUnlocked() ?: Credentials())
                saveUnlocked(updated)
                updated
            }
        }

    private inline fun <T> withFileLock(block: () -> T): T {
        configDir.mkdirs()
        FileChannel
            .open(
                lockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                restrictPermissions(lockFile)
                channel.lock().use {
                    return block()
                }
            }
    }

    private fun saveUnlocked(credentials: Credentials) {
        configDir.mkdirs()
        val directory = configDir.toPath()
        val target = configFile.toPath()
        val temporary = Files.createTempFile(directory, ".credentials-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(Credentials.serializer(), credentials))
            restrictPermissions(temporary.toFile())
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            restrictPermissions(configFile)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun restrictPermissions(file: File) {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    /**
     * Check if credentials exist and have at least an LLM key configured.
     */
    fun isConfigured(): Boolean {
        val creds = load() ?: return false
        val provider = creds.llmProvider.lowercase()
        if (provider == "ollama" || provider == "vllm") return true
        val key = creds.llmApiKeys[creds.llmProvider] ?: creds.llmApiKey
        return key.isNotBlank()
    }

    /**
     * Merge credentials with environment variables.
     * Env vars take precedence (for Docker / CI).
     */
    fun resolveApiKeys(creds: Credentials): Map<String, String> =
        buildMap {
            // Stored credentials from the multi-provider maps
            putAll(creds.llmApiKeys)

            // Legacy single stored credential fallback
            if (creds.llmApiKey.isNotBlank()) {
                put(creds.llmProvider, creds.llmApiKey)
            }
            if (creds.llmProvider.equals("litellm", ignoreCase = true) && creds.ollamaUrl.isNotBlank()) {
                put("litellm_url", creds.ollamaUrl)
            }
            putAll(ProviderSecretRegistry.resolve(creds))
            // Environment overrides
            ConfigProvider.get().get("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() }?.let { put("openrouter", it) }
            ConfigProvider.get().get("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }?.let { put("openai", it) }
            ConfigProvider.get().get("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }?.let { put("anthropic", it) }
            ConfigProvider.get().get("GOOGLE_API_KEY")?.takeIf { it.isNotBlank() }?.let { put("google", it) }
            ConfigProvider.get().get("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }?.let { put("deepseek", it) }
            ConfigProvider.get().get("NVIDIA_NIM_API_KEY")?.takeIf { it.isNotBlank() }?.let { put("nvidia", it) }
            ConfigProvider.get().get("LITELLM_API_KEY")?.takeIf { it.isNotBlank() }?.let { put("litellm", it) }
            (ConfigProvider.get().get("LITELLM_BASE_URL") ?: ConfigProvider.get().get("LITELLM_URL"))
                ?.takeIf { it.isNotBlank() }
                ?.let { put("litellm_url", it) }
            ConfigProvider.get().get("MOONSHOT_BASE_URL")
                ?.takeIf { it.isNotBlank() }
                ?.let { put("kimi_url", it) }
            ConfigProvider.get().get("XAI_BASE_URL")
                ?.takeIf { it.isNotBlank() }
                ?.let { put("xai_url", it) }
            ProviderSecretRegistry.definitions.forEach { definition ->
                ConfigProvider.get().get(definition.envKey)?.takeIf { it.isNotBlank() }?.let { put(definition.id, it) }
            }
            put("ollama_url", creds.ollamaUrl)
        }

    /**
     * Generate a cryptographically random API key.
     * Format: `pk-prom-` + 32 hex chars (128 bits of entropy).
     */
    fun generateApiKey(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return "pk-prom-" + bytes.joinToString("") { "%02x".format(it) }
    }
}
