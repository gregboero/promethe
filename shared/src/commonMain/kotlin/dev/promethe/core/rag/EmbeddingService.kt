package dev.promethe.core.rag

/**
 * EmbeddingService — provider-agnostic interface for generating text embeddings.
 *
 * Implementations: OllamaEmbedding, OpenAICompatibleEmbedding (covers OpenAI, LiteLLM, Mistral),
 * GeminiEmbedding, VoyageEmbedding, CohereEmbedding.
 */
interface EmbeddingService {
    /** Provider name for logging/config. */
    val providerName: String

    /** Model ID currently configured. */
    val modelId: String

    /** Output vector dimensions. */
    val dimensions: Int

    /** Embed a single text. */
    suspend fun embed(text: String): FloatArray

    /** Batch embed multiple texts (more efficient for ingestion). */
    suspend fun embedBatch(texts: List<String>): List<FloatArray>

    /**
     * Test the connection to the embedding provider.
     * Returns (success, latencyMs, error).
     */
    suspend fun testConnection(): EmbeddingTestResult

    /** Clean up HTTP resources. */
    suspend fun close()
}

data class EmbeddingTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val dimensions: Int = 0,
    val error: String? = null,
)
