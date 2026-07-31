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
 * QdrantVectorStore — HTTP client for Qdrant (self-hosted or Qdrant Cloud).
 *
 * Self-hosted: docker run -p 6333:6333 qdrant/qdrant
 * Cloud: free tier 1GB at https://cloud.qdrant.io
 */
class QdrantVectorStore(
    private val baseUrl: String = "http://localhost:6333",
    private val collectionName: String = "promethe_knowledge",
    private val apiKey: String = "",
    private val vectorSize: Int = 768,
) : VectorStore {
    override val storeName = "qdrant"

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 5_000
        }
    }

    private val json = PrometheJson

    private fun HttpRequestBuilder.applyAuth() {
        if (apiKey.isNotBlank()) {
            header("api-key", apiKey)
        }
    }

    private suspend fun ensureCollection() {
        val resp = client.get("$baseUrl/collections/$collectionName") { applyAuth() }
        if (resp.status == HttpStatusCode.OK) return

        client.put("$baseUrl/collections/$collectionName") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put(
                        "vectors",
                        buildJsonObject {
                            put("size", vectorSize)
                            put("distance", "Cosine")
                        },
                    )
                },
            )
        }
        logger.info { "Created Qdrant collection '$collectionName' (size=$vectorSize)" }
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
        ensureCollection()

        val points = items.map { item ->
            buildJsonObject {
                put("id", item.id.hashCode().toLong().let { if (it < 0) -it else it }) // Qdrant needs uint64
                put("vector", JsonArray(item.embedding.map { JsonPrimitive(it) }))
                put(
                    "payload",
                    buildJsonObject {
                        put("content", item.content)
                        put("doc_id", item.id)
                        for ((k, v) in item.metadata) {
                            put(k, v)
                        }
                    },
                )
            }
        }

        client.put("$baseUrl/collections/$collectionName/points") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put("points", JsonArray(points))
                },
            )
        }
    }

    override suspend fun search(
        queryEmbedding: FloatArray,
        topK: Int,
        filter: Map<String, String>,
    ): List<ScoredResult> {
        ensureCollection()

        val response = client.post("$baseUrl/collections/$collectionName/points/search") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put("vector", JsonArray(queryEmbedding.map { JsonPrimitive(it) }))
                    put("limit", topK)
                    put("with_payload", true)
                    if (filter.isNotEmpty()) {
                        put(
                            "filter",
                            buildJsonObject {
                                put(
                                    "must",
                                    JsonArray(
                                        filter.map { (k, v) ->
                                            buildJsonObject {
                                                put("key", k)
                                                put("match", buildJsonObject { put("value", v) })
                                            }
                                        },
                                    ),
                                )
                            },
                        )
                    }
                },
            )
        }

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val results = body["result"]?.jsonArray ?: return emptyList()

        return results.map { item ->
            val obj = item.jsonObject
            val payload = obj["payload"]?.jsonObject ?: JsonObject(emptyMap())
            ScoredResult(
                id = payload["doc_id"]?.jsonPrimitive?.content ?: obj["id"].toString(),
                content = payload["content"]?.jsonPrimitive?.content ?: "",
                score = obj["score"]?.jsonPrimitive?.double ?: 0.0,
                metadata = payload.filterKeys { it != "content" && it != "doc_id" }
                    .mapValues { it.value.jsonPrimitive.content },
            )
        }
    }

    override suspend fun delete(id: String) {
        ensureCollection()
        client.post("$baseUrl/collections/$collectionName/points/delete") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put(
                        "filter",
                        buildJsonObject {
                            put(
                                "must",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("key", "doc_id")
                                            put("match", buildJsonObject { put("value", id) })
                                        },
                                    ),
                                ),
                            )
                        },
                    )
                },
            )
        }
    }

    override suspend fun deleteBySource(source: String) {
        ensureCollection()
        client.post("$baseUrl/collections/$collectionName/points/delete") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put(
                        "filter",
                        buildJsonObject {
                            put(
                                "must",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("key", "source")
                                            put("match", buildJsonObject { put("value", source) })
                                        },
                                    ),
                                ),
                            )
                        },
                    )
                },
            )
        }
    }

    override suspend fun count(): Int {
        val resp = client.get("$baseUrl/collections/$collectionName") { applyAuth() }
        if (resp.status != HttpStatusCode.OK) return 0
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        return body["result"]?.jsonObject?.get("points_count")?.jsonPrimitive?.int ?: 0
    }

    override suspend fun testConnection(): Boolean =
        try {
            val resp = client.get("$baseUrl/collections") { applyAuth() }
            resp.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.debug(e) { "Qdrant connection test failed" }
            false
        }

    override suspend fun close() {
        client.close()
    }
}
