package dev.promethe.api

import kotlinx.serialization.Serializable

/**
 * Central registry of all supported LLM providers.
 * Single source of truth used by SetupScreen (UI), KoogLlmAdapter (runtime), and gateway.
 *
 * Lives in :api so it's available on ALL platforms (JVM, WasmJS, Android, iOS).
 */
object ProviderRegistry {
    @Serializable
    data class ProviderInfo(
        val key: String,
        val name: String,
        val category: ProviderCategory,
        val defaultModel: String,
        val fallbackModels: List<String>,
        val defaultUrl: String = "",
        val needsKey: Boolean = true,
        val description: String = "",
    )

    @Serializable
    enum class ProviderCategory {
        /** Runs locally on user's machine (Ollama) */
        LOCAL,

        /** Self-hosted proxy — local or remote (LiteLLM) */
        PROXY,

        /** Direct cloud API (OpenAI, Anthropic, Google, OpenRouter) */
        CLOUD,
    }

    /**
     * All providers supported by the Koog runtime.
     * Order matters — used for display in SetupScreen.
     */
    val providers: List<ProviderInfo> =
        listOf(
            // ── LOCAL ────────────────────────────────────────────────────
            ProviderInfo(
                key = "ollama",
                name = "Ollama",
                category = ProviderCategory.LOCAL,
                defaultModel = "llama3.1:8b",
                fallbackModels =
                    listOf(
                        "llama3.1:8b",
                        "llama3.1:70b",
                        "llama3.2:3b",
                        "mistral:7b",
                        "mixtral:8x7b",
                        "gemma2:9b",
                        "qwen2.5:7b",
                        "deepseek-r1:8b",
                        "phi3:14b",
                    ),
                defaultUrl = "http://localhost:11434",
                needsKey = false,
                description = "Run open-source models locally on your machine",
            ),
            // ── PROXY ────────────────────────────────────────────────────
            ProviderInfo(
                key = "litellm",
                name = "LiteLLM Proxy",
                category = ProviderCategory.PROXY,
                defaultModel = "",
                fallbackModels = emptyList(),
                defaultUrl = "http://localhost:4000",
                needsKey = false, // Master key is optional
                description = "Open-source proxy that unifies all LLM providers (local or remote)",
            ),
            // ── CLOUD ────────────────────────────────────────────────────
            ProviderInfo(
                key = "openai",
                name = "OpenAI",
                category = ProviderCategory.CLOUD,
                defaultModel = "gpt-5.6-terra",
                fallbackModels =
                    listOf(
                        "gpt-5.6-sol",
                        "gpt-5.6-terra",
                        "gpt-5.6-luna",
                    ),
                description = "Direct OpenAI API access",
            ),
            ProviderInfo(
                key = "anthropic",
                name = "Anthropic",
                category = ProviderCategory.CLOUD,
                defaultModel = "claude-sonnet-5",
                fallbackModels =
                    listOf(
                        "claude-sonnet-5",
                        "claude-opus-4-8",
                    ),
                description = "Direct Anthropic API access",
            ),
            ProviderInfo(
                key = "google",
                name = "Google Gemini",
                category = ProviderCategory.CLOUD,
                defaultModel = "gemini-3.5-flash",
                fallbackModels =
                    listOf(
                        "gemini-3.5-flash",
                        "gemini-3.1-pro-preview",
                        "gemini-3.1-flash-lite",
                    ),
                description = "Direct Google AI Studio / Gemini API access",
            ),
            ProviderInfo(
                key = "openrouter",
                name = "OpenRouter",
                category = ProviderCategory.CLOUD,
                defaultModel = "",
                fallbackModels = emptyList(),
                description = "Commercial hub — access 200+ models with a single API key",
            ),
            // ── HIGH-PERFORMANCE ─────────────────────────────────────────
            ProviderInfo(
                key = "deepseek",
                name = "DeepSeek",
                category = ProviderCategory.CLOUD,
                defaultModel = "deepseek-v4-flash",
                fallbackModels = listOf(
                    "deepseek-v4-flash",
                    "deepseek-v4-pro",
                ),
                description = "High-performance reasoning models at competitive cost",
            ),
            ProviderInfo(
                key = "nvidia",
                name = "NVIDIA NIM",
                category = ProviderCategory.CLOUD,
                defaultModel = "",
                fallbackModels = emptyList(),
                description = "NVIDIA NIM — GPU-accelerated inference endpoints",
            ),
            ProviderInfo(
                key = "kimi",
                name = "Kimi",
                category = ProviderCategory.CLOUD,
                defaultModel = "kimi-k3",
                fallbackModels = listOf("kimi-k3"),
                defaultUrl = "https://api.moonshot.ai",
                description = "Direct Moonshot Kimi API access",
            ),
            ProviderInfo(
                key = "xai",
                name = "xAI (Grok)",
                category = ProviderCategory.CLOUD,
                defaultModel = "grok-4.5",
                fallbackModels = listOf("grok-4.5"),
                defaultUrl = "https://api.x.ai",
                description = "Direct xAI Grok API access",
            ),
        )

    /** Lookup a provider by key */
    fun get(key: String): ProviderInfo? = providers.find { it.key == canonicalKey(key) }

    /** All providers of a given category */
    fun byCategory(category: ProviderCategory): List<ProviderInfo> = providers.filter { it.category == category }

    /** All provider keys supported by the Koog runtime */
    val supportedKeys: Set<String> = providers.map { it.key }.toSet()

    /** Normalize provider IDs that were persisted by older Promethe releases. */
    fun canonicalKey(key: String): String =
        when (val normalized = key.trim().lowercase()) {
            "gemini" -> "google"
            else -> normalized
        }
}
