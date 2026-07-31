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
 * PineconeVectorStore — HTTP client for Pinecone (cloud managed).
 *
 * Serverless, free tier: 100K vectors.
 * Requires API key + index host URL (e.g. "https://my-index-abc123.svc.us-east1-gcp.pinecone.io").
 */
class PineconeVectorStore(
    private val indexUrl: String,
    private val apiKey: String,
    private val namespace: String = "promethe",
) : VectorStore {
    override val storeName = "pinecone"

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 5_000
        }
    }

    private val json = PrometheJson

    private fun HttpRequestBuilder.applyAuth() {
        header("Api-Key", apiKey)
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
        val vectors = items.map { item ->
            buildJsonObject {
                put("id", item.id)
                put("values", JsonArray(item.embedding.map { JsonPrimitive(it) }))
                put(
                    "metadata",
                    buildJsonObject {
                        put("content", item.content)
                        for ((k, v) in item.metadata) {
                            put(k, v)
                        }
                    },
                )
            }
        }

        client.post("$indexUrl/vectors/upsert") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put("vectors", JsonArray(vectors))
                    put("namespace", namespace)
                },
            )
        }
    }

    override suspend fun search(
        queryEmbedding: FloatArray,
        topK: Int,
        filter: Map<String, String>,
    ): List<ScoredResult> {
        val response = client.post("$indexUrl/query") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put("vector", JsonArray(queryEmbedding.map { JsonPrimitive(it) }))
                    put("topK", topK)
                    put("includeMetadata", true)
                    put("namespace", namespace)
                    if (filter.isNotEmpty()) {
                        put(
                            "filter",
                            buildJsonObject {
                                for ((k, v) in filter) {
                                    put(k, buildJsonObject { put("\$eq", v) })
                                }
                            },
                        )
                    }
                },
            )
        }

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val matches = body["matches"]?.jsonArray ?: return emptyList()

        return matches.map { item ->
            val obj = item.jsonObject
            val metadata = obj["metadata"]?.jsonObject ?: JsonObject(emptyMap())
            ScoredResult(
                id = obj["id"]?.jsonPrimitive?.content ?: "",
                content = metadata["content"]?.jsonPrimitive?.content ?: "",
                score = obj["score"]?.jsonPrimitive?.double ?: 0.0,
                metadata = metadata.filterKeys { it != "content" }
                    .mapValues { it.value.jsonPrimitive.content },
            )
        }
    }

    override suspend fun delete(id: String) {
        client.post("$indexUrl/vectors/delete") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put("ids", JsonArray(listOf(JsonPrimitive(id))))
                    put("namespace", namespace)
                },
            )
        }
    }

    override suspend fun deleteBySource(source: String) {
        client.post("$indexUrl/vectors/delete") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(
                buildJsonObject {
                    put(
                        "filter",
                        buildJsonObject {
                            put("source", buildJsonObject { put("\$eq", source) })
                        },
                    )
                    put("namespace", namespace)
                },
            )
        }
    }

    override suspend fun count(): Int {
        val resp = client.post("$indexUrl/describe_index_stats") {
            contentType(ContentType.Application.Json)
            applyAuth()
            setBody(buildJsonObject {})
        }
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val ns = body["namespaces"]?.jsonObject?.get(namespace)?.jsonObject
        return ns?.get("vectorCount")?.jsonPrimitive?.int ?: 0
    }

    override suspend fun testConnection(): Boolean =
        try {
            val resp = client.post("$indexUrl/describe_index_stats") {
                contentType(ContentType.Application.Json)
                applyAuth()
                setBody(buildJsonObject {})
            }
            resp.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.debug(e) { "Pinecone connection test failed" }
            false
        }

    override suspend fun close() {
        client.close()
    }
}
