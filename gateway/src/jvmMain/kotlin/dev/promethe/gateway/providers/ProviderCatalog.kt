package dev.promethe.gateway.providers

import dev.promethe.api.providers.CertificationStatus
import dev.promethe.api.providers.ModelCapability
import dev.promethe.api.providers.ModelDescriptor
import dev.promethe.api.providers.ModelLifecycle
import dev.promethe.api.providers.ModelMaturity
import dev.promethe.api.providers.ProviderAvailability
import dev.promethe.api.providers.ProviderDescriptor

/** Immutable catalog snapshot used by the HTTP layer and the dynamic source. */
class ProviderCatalog private constructor(
    private val entries: List<Entry>,
) {
    data class Entry(
        val provider: ProviderDescriptor,
        val models: List<ModelDescriptor>,
    )

    init {
        require(entries.map { it.provider.id }.distinct().size == entries.size) {
            "Provider ids must be unique"
        }
        require(entries.all { entry -> entry.models.all { it.providerId == entry.provider.id } }) {
            "Every model must belong to its catalog provider"
        }
        require(
            entries.all { entry ->
                entry.models.map { it.id }.distinct().size == entry.models.size
            },
        ) {
            "Model ids must be unique within a provider"
        }
    }

    fun entries(): List<Entry> = entries

    fun providers(): List<ProviderDescriptor> = entries.map { it.provider }

    fun modelsFor(providerId: String): List<ModelDescriptor>? = entries.firstOrNull { it.provider.id == providerId }?.models

    companion object {
        fun of(entries: List<Entry>): ProviderCatalog = ProviderCatalog(entries.toList())

        /** Static certification candidates used until live account discovery succeeds. */
        fun initial(): ProviderCatalog =
            of(
                listOf(
                    entry(
                        id = "ollama",
                        displayName = "Ollama",
                        description = "Local open-source model runtime",
                        capabilities = listOf(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                        certificationStatus = CertificationStatus.BETA,
                        models = listOf(
                            model("llama3.1:8b", "Llama 3.1 8B", ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                            model("llama3.1:70b", "Llama 3.1 70B", ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                            model("mistral:7b", "Mistral 7B", ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                        ),
                    ),
                    entry(
                        id = "litellm",
                        displayName = "LiteLLM Proxy",
                        description = "OpenAI-compatible proxy for local and remote models",
                        capabilities = listOf(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                        certificationStatus = CertificationStatus.BETA,
                        models = emptyList(),
                    ),
                    entry(
                        id = "openai",
                        displayName = "OpenAI",
                        description = "Direct OpenAI model access",
                        certificationStatus = CertificationStatus.BETA,
                        models = listOf(
                            model("gpt-5.6-sol", "GPT-5.6 Sol", ModelCapability.REASONING, ModelCapability.VISION, ModelCapability.TOOL_CALLING),
                            model("gpt-5.6-terra", "GPT-5.6 Terra", ModelCapability.REASONING, ModelCapability.VISION, ModelCapability.TOOL_CALLING),
                            model("gpt-5.6-luna", "GPT-5.6 Luna", ModelCapability.REASONING, ModelCapability.VISION, ModelCapability.TOOL_CALLING),
                        ),
                    ),
                    entry(
                        id = "anthropic",
                        displayName = "Anthropic",
                        description = "Direct Claude model access",
                        certificationStatus = CertificationStatus.BETA,
                        models = listOf(
                            model("claude-sonnet-5", "Claude Sonnet 5", ModelCapability.REASONING, ModelCapability.VISION, ModelCapability.TOOL_CALLING),
                            model("claude-opus-4-8", "Claude Opus 4.8", ModelCapability.REASONING, ModelCapability.VISION, ModelCapability.TOOL_CALLING),
                        ),
                    ),
                    entry(
                        id = "google",
                        displayName = "Google Gemini",
                        description = "Direct Google Gemini model access",
                        certificationStatus = CertificationStatus.BETA,
                        models = listOf(
                            model("gemini-3.5-flash", "Gemini 3.5 Flash", ModelCapability.REASONING, ModelCapability.VISION, ModelCapability.TOOL_CALLING),
                            model("gemini-3.1-pro-preview", "Gemini 3.1 Pro Preview", ModelCapability.REASONING, ModelCapability.VISION, ModelCapability.TOOL_CALLING, lifecycle = ModelLifecycle.PREVIEW, maturity = ModelMaturity.PREVIEW),
                            model("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite", ModelCapability.REASONING, ModelCapability.VISION, ModelCapability.TOOL_CALLING),
                        ),
                    ),
                    entry(
                        id = "openrouter",
                        displayName = "OpenRouter",
                        description = "Multi-provider model gateway",
                        capabilities = listOf(ModelCapability.CHAT, ModelCapability.VISION, ModelCapability.TOOL_CALLING),
                        certificationStatus = CertificationStatus.BETA,
                        models = emptyList(),
                    ),
                    entry(
                        id = "deepseek",
                        displayName = "DeepSeek",
                        description = "Chat and reasoning models from DeepSeek",
                        certificationStatus = CertificationStatus.BETA,
                        models = listOf(
                            model("deepseek-v4-flash", "DeepSeek V4 Flash", ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                            model("deepseek-v4-pro", "DeepSeek V4 Pro", ModelCapability.REASONING, ModelCapability.TOOL_CALLING),
                        ),
                    ),
                    entry(
                        id = "nvidia",
                        displayName = "NVIDIA NIM",
                        description = "GPU-accelerated inference endpoints",
                        capabilities = listOf(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                        certificationStatus = CertificationStatus.BETA,
                        models = emptyList(),
                    ),
                    entry(
                        id = "kimi",
                        displayName = "Kimi",
                        description = "Direct Moonshot Kimi API access",
                        certificationStatus = CertificationStatus.BETA,
                        models = listOf(
                            model(
                                "kimi-k3",
                                "Kimi K3",
                                ModelCapability.CHAT,
                                ModelCapability.REASONING,
                                ModelCapability.TOOL_CALLING,
                                contextWindowTokens = 1_048_576,
                            ),
                        ),
                    ),
                    entry(
                        id = "xai",
                        displayName = "xAI (Grok)",
                        description = "Direct xAI Grok API access",
                        certificationStatus = CertificationStatus.BETA,
                        models = listOf(
                            model(
                                "grok-4.5",
                                "Grok 4.5",
                                ModelCapability.CHAT,
                                ModelCapability.REASONING,
                                ModelCapability.TOOL_CALLING,
                                contextWindowTokens = 500_000,
                            ),
                        ),
                    ),
                ),
            )

        private fun entry(
            id: String,
            displayName: String,
            description: String,
            certificationStatus: CertificationStatus,
            models: List<ModelDescriptor>,
            capabilities: List<ModelCapability> = emptyList(),
        ): Entry {
            val advertisedCapabilities = models.flatMap { it.capabilities }.distinct().ifEmpty { capabilities }
            val provider = ProviderDescriptor(
                id = id,
                displayName = displayName,
                description = description,
                capabilities = advertisedCapabilities,
                certificationStatus = certificationStatus,
                maturity = if (certificationStatus == CertificationStatus.CERTIFIED) ModelMaturity.STABLE else ModelMaturity.BETA,
            )
            return Entry(provider, models.map { it.copy(providerId = id) })
        }

        private fun model(
            id: String,
            displayName: String,
            vararg capabilities: ModelCapability,
            lifecycle: ModelLifecycle = ModelLifecycle.ACTIVE,
            maturity: ModelMaturity = ModelMaturity.BETA,
            certificationStatus: CertificationStatus = CertificationStatus.BETA,
            contextWindowTokens: Int? = null,
        ): ModelDescriptor =
            ModelDescriptor(
                id = id,
                displayName = displayName,
                providerId = "pending",
                capabilities = capabilities.toList(),
                lifecycle = lifecycle,
                certificationStatus = certificationStatus,
                maturity = maturity,
                contextWindowTokens = contextWindowTokens,
            )
    }
}
