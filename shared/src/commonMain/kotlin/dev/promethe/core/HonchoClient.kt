package dev.promethe.core

import dev.promethe.core.Log

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

class HonchoClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
) {
    private val logger = Log.create("HonchoClient")

    /** Pousse le profil utilisateur vers Honcho */
    suspend fun syncProfile(
        sessionId: String,
        userProfile: String,
        memoryContext: String,
    ): Boolean =
        try {
            val response =
                httpClient.post("$baseUrl/api/v1/sessions/$sessionId/sync") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $apiKey")
                    setBody(
                        buildJsonObject {
                            put("user_profile", userProfile)
                            put("memory_context", memoryContext)
                        },
                    )
                }
            response.status.value in 200..299
        } catch (e: Exception) {
            logger.warn(e) { "Sync failed" }
            false
        }

    /** Récupère le contexte sémantique pertinent pour une requête */
    suspend fun fetchContext(
        sessionId: String,
        query: String,
    ): String =
        try {
            val response =
                httpClient.post("$baseUrl/api/v1/sessions/$sessionId/query") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $apiKey")
                    setBody(buildJsonObject { put("query", query) })
                }
            if (response.status.value in 200..299) {
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                json["context"]?.jsonPrimitive?.content ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            logger.warn(e) { "Context fetch failed" }
            ""
        }

    /** Vérifie si le serveur Honcho est accessible */
    suspend fun isAvailable(): Boolean =
        try {
            val response = httpClient.get("$baseUrl/health")
            response.status.value == 200
        } catch (e: Exception) {
            logger.warn(e) { "Honcho health check failed" }
            false
        }
}
