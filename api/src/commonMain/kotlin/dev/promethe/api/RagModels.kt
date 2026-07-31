package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
enum class EmbeddingProvider {
    OLLAMA,
    OPENAI,
    GEMINI,
    VOYAGE,
    MISTRAL,
    COHERE,
    LITELLM,
}

@Serializable
enum class VectorStoreType {
    SQLITE_VEC,
    CHROMA,
    QDRANT,
    PINECONE,
    MILVUS,
}

@Serializable
data class EmbeddingModelInfo(
    val id: String,
    val provider: EmbeddingProvider,
    val dimensions: Int,
    val pricePerMillionTokens: Double,
    val description: String,
) {
    companion object {
        val KNOWN_MODELS: List<EmbeddingModelInfo> = listOf(
            EmbeddingModelInfo("nomic-embed-text", EmbeddingProvider.OLLAMA, 768, 0.0, "Bon généraliste local"),
            EmbeddingModelInfo("mxbai-embed-large", EmbeddingProvider.OLLAMA, 1024, 0.0, "Haute qualité local"),
            EmbeddingModelInfo("all-minilm", EmbeddingProvider.OLLAMA, 384, 0.0, "Ultra rapide"),
            EmbeddingModelInfo("snowflake-arctic-embed", EmbeddingProvider.OLLAMA, 1024, 0.0, "Multilingue local"),
            EmbeddingModelInfo("text-embedding-3-small", EmbeddingProvider.OPENAI, 1536, 0.02, "Standard OpenAI"),
            EmbeddingModelInfo("text-embedding-3-large", EmbeddingProvider.OPENAI, 3072, 0.13, "Haute qualité OpenAI"),
            EmbeddingModelInfo("text-embedding-004", EmbeddingProvider.GEMINI, 768, 0.0, "Gratuit Google"),
            EmbeddingModelInfo("text-multilingual-embedding-002", EmbeddingProvider.GEMINI, 768, 0.025, "Multilingue Google"),
            EmbeddingModelInfo("voyage-3", EmbeddingProvider.VOYAGE, 1024, 0.06, "Top qualité"),
            EmbeddingModelInfo("voyage-3-lite", EmbeddingProvider.VOYAGE, 512, 0.02, "Budget Voyage"),
            EmbeddingModelInfo("voyage-code-3", EmbeddingProvider.VOYAGE, 1024, 0.06, "Spécialisé code"),
            EmbeddingModelInfo("mistral-embed", EmbeddingProvider.MISTRAL, 1024, 0.10, "Mistral"),
            EmbeddingModelInfo("embed-v4.0", EmbeddingProvider.COHERE, 1024, 0.0, "Multilingue Cohere"),
        )
    }
}

@Serializable
data class RagConfig(
    val enabled: Boolean = false,
    val embeddingProvider: EmbeddingProvider = EmbeddingProvider.OLLAMA,
    val embeddingModel: String = "nomic-embed-text",
    val embeddingBaseUrl: String = "http://localhost:11434",
    val embeddingApiKey: String = "",
    val embeddingDimensions: Int = 768,
    val vectorStoreType: VectorStoreType = VectorStoreType.SQLITE_VEC,
    val vectorStoreUrl: String = "",
    val vectorStoreApiKey: String = "",
    val vectorStorePath: String = "",
    val chunkSize: Int = 512,
    val chunkOverlap: Int = 50,
)

@Serializable
data class RagDocumentInfo(
    val id: String,
    val filename: String,
    val chunkCount: Int,
    val totalTokens: Int,
    val ingestedAt: Long,
)

@Serializable
data class RagSearchRequest(
    val query: String,
    val topK: Int = 5,
)

@Serializable
data class RagSearchResult(
    val content: String,
    val score: Double,
    val source: String,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class RagSearchResponse(
    val results: List<RagSearchResult>,
    val queryTimeMs: Long,
)

@Serializable
data class RagTestResponse(
    val success: Boolean,
    val provider: String,
    val model: String,
    val dimensions: Int,
    val latencyMs: Long,
    val error: String? = null,
)

@Serializable
data class RagIngestRequest(
    val content: String,
    val filename: String,
    val metadata: Map<String, String> = emptyMap(),
)
