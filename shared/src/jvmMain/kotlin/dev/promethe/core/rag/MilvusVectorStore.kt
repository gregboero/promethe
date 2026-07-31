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
 * MilvusVectorStore — HTTP client for Milvus (self-hosted or Zilliz Cloud).
 *
 * Self-hosted: docker-compose (heavy).
 * Cloud: Zilliz Cloud (free tier available).
 *
 * Uses the Milvus RESTful API v2 (available since Milvus 2.3+).
 */
class MilvusVectorStore(
    private val baseUrl: String = "http://localhost:19530",
    private val collectionName: String = "promethe_knowledge",
    private val token: String = "",
    private val vectorSize: Int = 768,
) : VectorStore {
    override val storeName = "milvus"

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 5_000
        }
    }

    private val json = PrometheJson

    private fun HttpRequestBuilder.applyAuth() {
        if (token.isNotBlank()) {
            header("Authorization", "Bearer $token")
        }
    }

    private suspend fun ensureCollection() {
        // Check if collection exists
        val resp = client.post("$baseUrl/v2/vectordb/collections/describe") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(buildJsonObject { put("collectionName", collectionName) })
        }
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        if (body["code"]?.jsonPrimitive?.int == 0) return

        // Create collection with schema
        client.post("$baseUrl/v2/vectordb/collections/create") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put("collectionName", collectionName)
                    put("dimension", vectorSize)
                    put("metricType", "COSINE")
                },
            )
        }
        logger.info { "Created Milvus collection '$collectionName' (dim=$vectorSize)" }
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

        val data = items.map { item ->
            buildJsonObject {
                put("doc_id", item.id)
                put("vector", JsonArray(item.embedding.map { JsonPrimitive(it) }))
                put("content", item.content)
                put("source", item.metadata["source"] ?: "")
                put(
                    "metadata_json",
                    JsonPrimitive(
                        item.metadata.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
                            .let { "{$it}" },
                    ),
                )
            }
        }

        client.post("$baseUrl/v2/vectordb/entities/upsert") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put("collectionName", collectionName)
                    put("data", JsonArray(data))
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

        val filterExpr = if (filter.isNotEmpty()) {
            filter.entries.joinToString(" and ") { "${it.key} == \"${it.value}\"" }
        } else {
            null
        }

        val response = client.post("$baseUrl/v2/vectordb/entities/search") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put("collectionName", collectionName)
                    put(
                        "data",
                        JsonArray(
                            listOf(
                                JsonArray(queryEmbedding.map { JsonPrimitive(it) }),
                            ),
                        ),
                    )
                    put("limit", topK)
                    put(
                        "outputFields",
                        JsonArray(
                            listOf(
                                JsonPrimitive("doc_id"),
                                JsonPrimitive("content"),
                                JsonPrimitive("source"),
                                JsonPrimitive("metadata_json"),
                            ),
                        ),
                    )
                    filterExpr?.let { put("filter", it) }
                },
            )
        }

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val results = body["data"]?.jsonArray ?: return emptyList()

        return results.map { item ->
            val obj = item.jsonObject
            val metaStr = obj["metadata_json"]?.jsonPrimitive?.content ?: "{}"
            ScoredResult(
                id = obj["doc_id"]?.jsonPrimitive?.content ?: "",
                content = obj["content"]?.jsonPrimitive?.content ?: "",
                score = obj["score"]?.jsonPrimitive?.double
                    ?: obj["distance"]?.jsonPrimitive?.double ?: 0.0,
                metadata = SqliteVecStore.parseSimpleJsonMap(metaStr),
            )
        }
    }

    override suspend fun delete(id: String) {
        ensureCollection()
        client.post("$baseUrl/v2/vectordb/entities/delete") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put("collectionName", collectionName)
                    put("filter", "doc_id == \"$id\"")
                },
            )
        }
    }

    override suspend fun deleteBySource(source: String) {
        ensureCollection()
        client.post("$baseUrl/v2/vectordb/entities/delete") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put("collectionName", collectionName)
                    put("filter", "source == \"$source\"")
                },
            )
        }
    }

    override suspend fun count(): Int {
        val resp = client.post("$baseUrl/v2/vectordb/collections/describe") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(buildJsonObject { put("collectionName", collectionName) })
        }
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        return body["data"]?.jsonObject?.get("rowCount")?.jsonPrimitive?.int ?: 0
    }

    override suspend fun testConnection(): Boolean =
        try {
            val resp = client.get("$baseUrl/v2/vectordb/collections/list") { applyAuth() }
            resp.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.debug(e) { "Milvus connection test failed" }
            false
        }

    override suspend fun close() {
        client.close()
    }
}
