package dev.promethe.core.rag

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Factory for creating VectorStore instances based on store type configuration.
 *
 * Supported types:
 * - sqlite-vec  → SqliteVecStore (embedded, zero config, default)
 * - chroma      → ChromaVectorStore (local or cloud)
 * - qdrant      → QdrantVectorStore (Docker or Qdrant Cloud)
 * - pinecone    → PineconeVectorStore (cloud managed)
 * - milvus      → MilvusVectorStore (Docker or Zilliz Cloud)
 */
object VectorStoreFactory {
    fun create(
        storeType: String,
        url: String = "",
        apiKey: String = "",
        path: String = "",
        collectionName: String = "promethe_knowledge",
        vectorSize: Int = 768,
    ): VectorStore {
        logger.info { "Creating vector store: type=$storeType, collection=$collectionName" }

        return when (storeType.lowercase().replace("-", "_")) {
            "sqlite_vec", "sqlite" -> {
                SqliteVecStore(
                    dbPath = path.ifBlank { "vectors.db" },
                    tableName = collectionName,
                )
            }

            "chroma" -> {
                ChromaVectorStore(
                    baseUrl = url.ifBlank { "http://localhost:8000" },
                    collectionName = collectionName,
                    apiKey = apiKey,
                )
            }

            "qdrant" -> {
                QdrantVectorStore(
                    baseUrl = url.ifBlank { "http://localhost:6333" },
                    collectionName = collectionName,
                    apiKey = apiKey,
                    vectorSize = vectorSize,
                )
            }

            "pinecone" -> {
                PineconeVectorStore(
                    indexUrl = url,
                    apiKey = apiKey,
                    namespace = collectionName,
                )
            }

            "milvus", "zilliz" -> {
                MilvusVectorStore(
                    baseUrl = url.ifBlank { "http://localhost:19530" },
                    collectionName = collectionName,
                    token = apiKey,
                    vectorSize = vectorSize,
                )
            }

            else -> {
                logger.warn { "Unknown vector store type '$storeType', falling back to SQLite-vec" }
                SqliteVecStore(
                    dbPath = path.ifBlank { "vectors.db" },
                    tableName = collectionName,
                )
            }
        }
    }
}
