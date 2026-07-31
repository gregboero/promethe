package dev.promethe.core.rag

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import dev.promethe.core.PrometheJson

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * ChromaVectorStore — HTTP client for ChromaDB (local or cloud).
 *
 * ChromaDB can run locally via `pip install chromadb && chroma run`
 * or as a managed cloud service.
 *
 * Default: http://localhost:8000
 */
class ChromaVectorStore(
    private val baseUrl: String = "http://localhost:8000",
    private val collectionName: String = "promethe_knowledge",
    private val apiKey: String = "",
) : VectorStore {
    override val storeName = "chroma"

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 5_000
        }
    }

    private val json = PrometheJson
    private var collectionId: String? = null

    private suspend fun ensureCollection(): String {
        collectionId?.let { return it }

        // Try to get existing collection
        val getResp = client.get("$baseUrl/api/v1/collections/$collectionName") {
            applyAuth()
        }
        if (getResp.status == HttpStatusCode.OK) {
            val body = json.parseToJsonElement(getResp.bodyAsText()).jsonObject
            collectionId = body["id"]?.jsonPrimitive?.content
            return collectionId!!
        }

        // Create collection
        val createResp = client.post("$baseUrl/api/v1/collections") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put("name", collectionName)
                    put("metadata", buildJsonObject { put("hnsw:space", "cosine") })
                },
            )
        }
        val body = json.parseToJsonElement(createResp.bodyAsText()).jsonObject
        collectionId = body["id"]?.jsonPrimitive?.content
        logger.info { "Created Chroma collection '$collectionName' (id=$collectionId)" }
        return collectionId!!
    }

    private fun HttpRequestBuilder.applyAuth() {
        if (apiKey.isNotBlank()) {
            header("Authorization", "Bearer $apiKey")
        }
    }

    override suspend fun upsert(
        id: String,
        embedding: FloatArray,
        content: String,
        metadata: Map<String, String>,
    ) {
        upsertBatch(listOf(VectorItem(id, embedding, content, metadata)))
    }

    override suspend fun upsertBatch(items: List<VectorItem>) {
        val colId = ensureCollection()

        client.post("$baseUrl/api/v1/collections/$colId/upsert") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put("ids", JsonArray(items.map { JsonPrimitive(it.id) }))
                    put(
                        "embeddings",
                        JsonArray(
                            items.map { item ->
                                JsonArray(item.embedding.map { JsonPrimitive(it) })
                            },
                        ),
                    )
                    put("documents", JsonArray(items.map { JsonPrimitive(it.content) }))
                    put(
                        "metadatas",
                        JsonArray(
                            items.map { item ->
                                JsonObject(item.metadata.mapValues { JsonPrimitive(it.value) })
                            },
                        ),
                    )
                },
            )
        }
    }

    override suspend fun search(
        queryEmbedding: FloatArray,
        topK: Int,
        filter: Map<String, String>,
    ): List<ScoredResult> {
        val colId = ensureCollection()

        val response = client.post("$baseUrl/api/v1/collections/$colId/query") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put(
                        "query_embeddings",
                        JsonArray(
                            listOf(
                                JsonArray(queryEmbedding.map { JsonPrimitive(it) }),
                            ),
                        ),
                    )
                    put("n_results", topK)
                    if (filter.isNotEmpty()) {
                        put("where", JsonObject(filter.mapValues { JsonPrimitive(it.value) }))
                    }
                    put(
                        "include",
                        JsonArray(
                            listOf(
                                JsonPrimitive("documents"),
                                JsonPrimitive("metadatas"),
                                JsonPrimitive("distances"),
                            ),
                        ),
                    )
                },
            )
        }

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val ids = body["ids"]?.jsonArray?.firstOrNull()?.jsonArray ?: return emptyList()
        val docs = body["documents"]?.jsonArray?.firstOrNull()?.jsonArray
        val metas = body["metadatas"]?.jsonArray?.firstOrNull()?.jsonArray
        val distances = body["distances"]?.jsonArray?.firstOrNull()?.jsonArray

        return ids.mapIndexed { i, idEl ->
            ScoredResult(
                id = idEl.jsonPrimitive.content,
                content = docs?.getOrNull(i)?.jsonPrimitive?.content ?: "",
                score = 1.0 - (distances?.getOrNull(i)?.jsonPrimitive?.double ?: 1.0), // cosine distance → similarity
                metadata = metas?.getOrNull(i)?.jsonObject
                    ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
            )
        }
    }

    override suspend fun delete(id: String) {
        val colId = ensureCollection()
        client.post("$baseUrl/api/v1/collections/$colId/delete") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put("ids", JsonArray(listOf(JsonPrimitive(id))))
                },
            )
        }
    }

    override suspend fun deleteBySource(source: String) {
        val colId = ensureCollection()
        client.post("$baseUrl/api/v1/collections/$colId/delete") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put("where", buildJsonObject { put("source", source) })
                },
            )
        }
    }

    override suspend fun count(): Int {
        val colId = ensureCollection()
        val response = client.get("$baseUrl/api/v1/collections/$colId/count") {
            applyAuth()
        }
        return response.bodyAsText().trim().toIntOrNull() ?: 0
    }

    override suspend fun testConnection(): Boolean =
        try {
            val response = client.get("$baseUrl/api/v1/heartbeat")
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.debug(e) { "Chroma connection test failed" }
            false
        }

    override suspend fun close() {
        client.close()
    }
}
