package dev.promethe.core.rag

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Factory for creating EmbeddingService instances based on provider configuration.
 *
 * Supported providers:
 * - ollama       → OllamaEmbeddingService (local, free)
 * - openai       → OpenAICompatibleEmbeddingService
 * - litellm      → OpenAICompatibleEmbeddingService (proxy)
 * - mistral      → OpenAICompatibleEmbeddingService (Mistral API)
 * - gemini       → GeminiEmbeddingService
 * - voyage       → VoyageEmbeddingService
 * - cohere       → CohereEmbeddingService
 */
object EmbeddingServiceFactory {
    fun create(
        provider: String,
        model: String,
        apiKey: String = "",
        baseUrl: String = "",
        dimensions: Int = 768,
    ): EmbeddingService {
        logger.info { "Creating embedding service: provider=$provider, model=$model, dimensions=$dimensions" }

        return when (provider.lowercase()) {
            "ollama" -> {
                OllamaEmbeddingService(
                    modelId = model.ifBlank { "nomic-embed-text" },
                    baseUrl = baseUrl.ifBlank { "http://localhost:11434" },
                    dimensions = dimensions,
                )
            }

            "openai" -> {
                OpenAICompatibleEmbeddingService(
                    modelId = model.ifBlank { "text-embedding-3-small" },
                    apiKey = apiKey,
                    baseUrl = "https://api.openai.com",
                    dimensions = dimensions,
                    providerName = "openai",
                )
            }

            "litellm" -> {
                OpenAICompatibleEmbeddingService(
                    modelId = model,
                    apiKey = apiKey,
                    baseUrl = baseUrl.ifBlank { "http://localhost:4000" },
                    dimensions = dimensions,
                    providerName = "litellm",
                )
            }

            "mistral" -> {
                OpenAICompatibleEmbeddingService(
                    modelId = model.ifBlank { "mistral-embed" },
                    apiKey = apiKey,
                    baseUrl = "https://api.mistral.ai",
                    dimensions = dimensions,
                    providerName = "mistral",
                )
            }

            "gemini" -> {
                GeminiEmbeddingService(
                    modelId = model.ifBlank { "text-embedding-004" },
                    apiKey = apiKey,
                    dimensions = dimensions,
                )
            }

            "voyage" -> {
                VoyageEmbeddingService(
                    modelId = model.ifBlank { "voyage-3" },
                    apiKey = apiKey,
                    dimensions = dimensions,
                )
            }

            "cohere" -> {
                CohereEmbeddingService(
                    modelId = model.ifBlank { "embed-v4.0" },
                    apiKey = apiKey,
                    dimensions = dimensions,
                )
            }

            else -> {
                logger.warn { "Unknown embedding provider '$provider', falling back to Ollama" }
                OllamaEmbeddingService(
                    modelId = model.ifBlank { "nomic-embed-text" },
                    baseUrl = baseUrl.ifBlank { "http://localhost:11434" },
                    dimensions = dimensions,
                )
            }
        }
    }
}
