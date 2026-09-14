package dev.promethe.core.memory

import dev.promethe.core.Log

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Memory provider backed by TencentDB Agent Memory.
 * Communicates via REST API with a self-hosted or cloud TencentDB Agent Memory instance.
 *
 * @see <a href="https://github.com/Tencent/TencentDB-Agent-Memory">TencentDB Agent Memory</a>
 *
 * Architecture (TencentDB 4-tier):
 * - L0: Conversation (raw dialogue) — handled by our DB
 * - L1: Atom (extracted facts) — stored via this provider
 * - L2: Scenario (aggregated scene blocks)
 * - L3: Persona (high-level user profiles)
 */
class TencentMemoryProvider(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val serviceId: String = "",
    private val apiKey: String = "",
) : MemoryProvider {
    private val logger = Log.create("TencentMemoryProvider")
    override val name: String = "tencent-agent-memory"

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    override suspend fun storeFact(fact: MemoryFact) {
        try {
            httpClient.post("$baseUrl/api/v1/memories") {
                contentType(ContentType.Application.Json)
                applyAuth()
                setBody(
                    buildJsonObject {
                        put("content", fact.content)
                        put("category", fact.category)
                        put("user_id", fact.userId)
                        put("tier", fact.tier.name.lowercase())
                        put("confidence", fact.confidence.toDouble())
                        put("source_session", fact.sourceSession)
                    },
                )
            }
        } catch (e: Exception) {
            println("[TencentMemory] Store failed: ${e.message}")
        }
    }

    override suspend fun recallFacts(
        query: String,
        limit: Int,
        userId: String,
    ): List<MemoryFact> {
        return try {
            val response =
                httpClient.post("$baseUrl/api/v1/memories/query") {
                    contentType(ContentType.Application.Json)
                    applyAuth()
                    setBody(
                        buildJsonObject {
                            put("query", query)
                            put("limit", limit)
                            put("user_id", userId)
                        },
                    )
                }
            if (response.status.value !in 200..299) return emptyList()

            val body = Json.parseToJsonElement(response.bodyAsText())
            val results = body.jsonObject["results"]?.jsonArray ?: return emptyList()

            results.map { element ->
                val obj = element.jsonObject
                MemoryFact(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    userId = obj["user_id"]?.jsonPrimitive?.content ?: "default",
                    category = obj["category"]?.jsonPrimitive?.content ?: "general",
                    content = obj["content"]?.jsonPrimitive?.content ?: "",
                    confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 1.0f,
                    tier = parseTier(obj["tier"]?.jsonPrimitive?.content),
                )
            }
        } catch (e: Exception) {
            println("[TencentMemory] Recall failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getAllFacts(userId: String): List<MemoryFact> {
        return try {
            val response =
                httpClient.get("$baseUrl/api/v1/memories") {
                    parameter("user_id", userId)
                    applyAuth()
                }
            if (response.status.value !in 200..299) return emptyList()

            val body = Json.parseToJsonElement(response.bodyAsText())
            val results = body.jsonObject["memories"]?.jsonArray ?: return emptyList()

            results.map { element ->
                val obj = element.jsonObject
                MemoryFact(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    userId = obj["user_id"]?.jsonPrimitive?.content ?: userId,
                    category = obj["category"]?.jsonPrimitive?.content ?: "general",
                    content = obj["content"]?.jsonPrimitive?.content ?: "",
                    confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull ?: 1.0f,
                    tier = parseTier(obj["tier"]?.jsonPrimitive?.content),
                )
            }
        } catch (e: Exception) {
            println("[TencentMemory] GetAll failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun deleteFact(id: String) {
        try {
            httpClient.delete("$baseUrl/api/v1/memories/$id") {
                applyAuth()
            }
        } catch (e: Exception) {
            println("[TencentMemory] Delete failed: ${e.message}")
        }
    }

    override suspend fun isAvailable(): Boolean =
        try {
            val response = httpClient.get("$baseUrl/health")
            response.status.value == 200
        } catch (e: Exception) {
            logger.warn(e) { "TencentDB health check failed" }
            false
        }

    // ── Internal ──

    private fun HttpRequestBuilder.applyAuth() {
        if (apiKey.isNotBlank()) {
            header("Authorization", "Bearer $apiKey")
        }
        if (serviceId.isNotBlank()) {
            header("x-tdai-service-id", serviceId)
        }
    }

    private fun parseTier(raw: String?): MemoryTier =
        when (raw?.lowercase()) {
            "conversation" -> MemoryTier.CONVERSATION
            "atomic", "atom" -> MemoryTier.ATOMIC
            "scenario" -> MemoryTier.SCENARIO
            "persona" -> MemoryTier.PERSONA
            else -> MemoryTier.ATOMIC
        }
}
