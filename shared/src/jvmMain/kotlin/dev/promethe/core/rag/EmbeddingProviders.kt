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
 * Ollama embedding service — calls local Ollama /api/embeddings endpoint.
 *
 * Default model: nomic-embed-text (768d).
 * Fully local, free, no API key needed.
 */
class OllamaEmbeddingService(
    override val modelId: String = "nomic-embed-text",
    private val baseUrl: String = "http://localhost:11434",
    override val dimensions: Int = 768,
) : EmbeddingService {
    override val providerName = "ollama"

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 5_000
        }
    }

    private val json = PrometheJson

    override suspend fun embed(text: String): FloatArray {
        val response = client.post("$baseUrl/api/embeddings") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("model", modelId)
                    put("prompt", text)
                },
            )
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["embedding"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        // Ollama doesn't have a native batch endpoint — sequential calls
        return texts.map { embed(it) }
    }

    override suspend fun testConnection(): EmbeddingTestResult {
        val start = System.currentTimeMillis()
        return try {
            val result = embed("test")
            EmbeddingTestResult(
                success = true,
                latencyMs = System.currentTimeMillis() - start,
                dimensions = result.size,
            )
        } catch (e: Exception) {
            logger.debug(e) { "Ollama embedding test failed" }
            EmbeddingTestResult(
                success = false,
                latencyMs = System.currentTimeMillis() - start,
                error = e.message,
            )
        }
    }

    override suspend fun close() {
        client.close()
    }
}

/**
 * OpenAI-compatible embedding service — works with OpenAI, LiteLLM, Mistral, and any
 * provider that exposes the /v1/embeddings endpoint.
 *
 * For LiteLLM: pass the LiteLLM proxy URL as baseUrl.
 * For Mistral: pass https://api.mistral.ai as baseUrl.
 */
class OpenAICompatibleEmbeddingService(
    override val modelId: String = "text-embedding-3-small",
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    override val dimensions: Int = 1536,
    override val providerName: String = "openai",
) : EmbeddingService {
    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 5_000
        }
    }

    private val json = PrometheJson

    override suspend fun embed(text: String): FloatArray = embedBatch(listOf(text)).first()

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        val response = client.post("$baseUrl/v1/embeddings") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(
                buildJsonObject {
                    put("model", modelId)
                    put("input", JsonArray(texts.map { JsonPrimitive(it) }))
                },
            )
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]!!.jsonArray
        return data.map { item ->
            item.jsonObject["embedding"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        }
    }

    override suspend fun testConnection(): EmbeddingTestResult {
        val start = System.currentTimeMillis()
        return try {
            val result = embed("test")
            EmbeddingTestResult(
                success = true,
                latencyMs = System.currentTimeMillis() - start,
                dimensions = result.size,
            )
        } catch (e: Exception) {
            logger.debug(e) { "$providerName embedding test failed" }
            EmbeddingTestResult(
                success = false,
                latencyMs = System.currentTimeMillis() - start,
                error = e.message,
            )
        }
    }

    override suspend fun close() {
        client.close()
    }
}

/**
 * Google Gemini embedding service — calls the Gemini embedContent API.
 *
 * Uses text-embedding-004 (768d) by default — generous free tier.
 */
class GeminiEmbeddingService(
    override val modelId: String = "text-embedding-004",
    private val apiKey: String,
    override val dimensions: Int = 768,
) : EmbeddingService {
    override val providerName = "gemini"

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 5_000
        }
    }

    private val json = PrometheJson
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta"

    override suspend fun embed(text: String): FloatArray {
        val response = client.post("$baseUrl/models/$modelId:embedContent") {
            contentType(ContentType.Application.Json)
            parameter("key", apiKey)
            setBody(
                buildJsonObject {
                    put(
                        "content",
                        buildJsonObject {
                            put(
                                "parts",
                                JsonArray(
                                    listOf(
                                        buildJsonObject { put("text", text) },
                                    ),
                                ),
                            )
                        },
                    )
                },
            )
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val embedding = body["embedding"]!!.jsonObject["values"]!!.jsonArray
        return embedding.map { it.jsonPrimitive.float }.toFloatArray()
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        // Gemini batchEmbedContents endpoint
        val requests = texts.map { text ->
            buildJsonObject {
                put("model", "models/$modelId")
                put(
                    "content",
                    buildJsonObject {
                        put(
                            "parts",
                            JsonArray(listOf(buildJsonObject { put("text", text) })),
                        )
                    },
                )
            }
        }
        val response = client.post("$baseUrl/models/$modelId:batchEmbedContents") {
            contentType(ContentType.Application.Json)
            parameter("key", apiKey)
            setBody(
                buildJsonObject {
                    put("requests", JsonArray(requests))
                },
            )
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val embeddings = body["embeddings"]!!.jsonArray
        return embeddings.map { item ->
            item.jsonObject["values"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        }
    }

    override suspend fun testConnection(): EmbeddingTestResult {
        val start = System.currentTimeMillis()
        return try {
            val result = embed("test")
            EmbeddingTestResult(
                success = true,
                latencyMs = System.currentTimeMillis() - start,
                dimensions = result.size,
            )
        } catch (e: Exception) {
            logger.debug(e) { "Gemini embedding test failed" }
            EmbeddingTestResult(
                success = false,
                latencyMs = System.currentTimeMillis() - start,
                error = e.message,
            )
        }
    }

    override suspend fun close() {
        client.close()
    }
}

/**
 * Voyage AI embedding service — calls the Voyage /v1/embeddings API.
 *
 * Recommended by Anthropic. Excellent for code (voyage-code-3).
 */
class VoyageEmbeddingService(
    override val modelId: String = "voyage-3",
    private val apiKey: String,
    override val dimensions: Int = 1024,
) : EmbeddingService {
    override val providerName = "voyage"

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 5_000
        }
    }

    private val json = PrometheJson

    override suspend fun embed(text: String): FloatArray = embedBatch(listOf(text)).first()

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        val response = client.post("https://api.voyageai.com/v1/embeddings") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(
                buildJsonObject {
                    put("model", modelId)
                    put("input", JsonArray(texts.map { JsonPrimitive(it) }))
                },
            )
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["data"]!!.jsonArray.map { item ->
            item.jsonObject["embedding"]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        }
    }

    override suspend fun testConnection(): EmbeddingTestResult {
        val start = System.currentTimeMillis()
        return try {
            val result = embed("test")
            EmbeddingTestResult(
                success = true,
                latencyMs = System.currentTimeMillis() - start,
                dimensions = result.size,
            )
        } catch (e: Exception) {
            logger.debug(e) { "Voyage embedding test failed" }
            EmbeddingTestResult(
                success = false,
                latencyMs = System.currentTimeMillis() - start,
                error = e.message,
            )
        }
    }

    override suspend fun close() {
        client.close()
    }
}

/**
 * Cohere embedding service — calls the Cohere /v2/embed API.
 *
 * Free tier: 100 req/min. Excellent multilingue.
 */
class CohereEmbeddingService(
    override val modelId: String = "embed-v4.0",
    private val apiKey: String,
    override val dimensions: Int = 1024,
) : EmbeddingService {
    override val providerName = "cohere"

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 5_000
        }
    }

    private val json = PrometheJson

    override suspend fun embed(text: String): FloatArray = embedBatch(listOf(text)).first()

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        val response = client.post("https://api.cohere.com/v2/embed") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(
                buildJsonObject {
                    put("model", modelId)
                    put("texts", JsonArray(texts.map { JsonPrimitive(it) }))
                    put("input_type", "search_document")
                    put("embedding_types", JsonArray(listOf(JsonPrimitive("float"))))
                },
            )
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val embeddings = body["embeddings"]!!.jsonObject["float"]!!.jsonArray
        return embeddings.map { arr ->
            arr.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        }
    }

    override suspend fun testConnection(): EmbeddingTestResult {
        val start = System.currentTimeMillis()
        return try {
            val result = embed("test")
            EmbeddingTestResult(
                success = true,
                latencyMs = System.currentTimeMillis() - start,
                dimensions = result.size,
            )
        } catch (e: Exception) {
            logger.debug(e) { "Cohere embedding test failed" }
            EmbeddingTestResult(
                success = false,
                latencyMs = System.currentTimeMillis() - start,
                error = e.message,
            )
        }
    }

    override suspend fun close() {
        client.close()
    }
}
