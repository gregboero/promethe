package dev.promethe.core

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * A2AClientTool — discovers and communicates with external A2A agents.
 *
 * Protocol: Google A2A (Agent-to-Agent) over HTTP.
 * Discovery: GET /.well-known/agent.json
 * Communication: POST /a2a with JSON-RPC 2.0
 */
class A2AClientTool(
    private val httpClient: HttpClient,
) {
    private val json = PrometheJson

    @kotlinx.serialization.Serializable
    data class AgentCard(
        val name: String = "",
        val description: String = "",
        val url: String = "",
        val version: String = "",
        val capabilities: List<String> = emptyList(),
    )

    data class A2ATaskResult(
        val success: Boolean,
        val agentName: String,
        val response: String,
        val error: String? = null,
    )

    /**
     * Discover an agent at the given base URL.
     * Fetches /.well-known/agent.json
     */
    suspend fun discover(baseUrl: String): AgentCard? =
        try {
            withRetry {
                val response = httpClient.get("${baseUrl.trimEnd('/')}/.well-known/agent.json")
                if (response.status == HttpStatusCode.OK) {
                    val body = response.bodyAsText()
                    json.decodeFromString<AgentCard>(body)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to discover A2A agent at $baseUrl after retries" }
            null
        }

    private suspend fun <T> withRetry(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 10000,
        block: suspend () -> T,
    ): T {
        var currentDelay = initialDelayMs
        repeat(maxRetries - 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                logger.warn(e) { "A2A operation failed (attempt ${attempt + 1}/$maxRetries), retrying in ${currentDelay}ms" }
                kotlinx.coroutines.delay(currentDelay)
                currentDelay = (currentDelay * 2).coerceAtMost(maxDelayMs)
            }
        }
        return block() // Last attempt — let exception propagate
    }

    /**
     * Send a task to an external A2A agent.
     */
    suspend fun sendTask(
        agentUrl: String,
        taskDescription: String,
        sessionId: String? = null,
    ): A2ATaskResult =
        try {
            val request =
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", "1")
                    put("method", "tasks/send")
                    putJsonObject("params") {
                        put(
                            "id",
                            sessionId ?: java.util.UUID
                                .randomUUID()
                                .toString(),
                        )
                        putJsonObject("message") {
                            put("role", "user")
                            putJsonArray("parts") {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", taskDescription)
                                    },
                                )
                            }
                        }
                    }
                }

            val response =
                httpClient.post("${agentUrl.trimEnd('/')}/a2a") {
                    contentType(ContentType.Application.Json)
                    setBody(request.toString())
                }

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val result = body["result"]?.jsonObject
            val error = body["error"]?.jsonObject

            if (error != null) {
                A2ATaskResult(
                    success = false,
                    agentName = agentUrl,
                    response = "",
                    error = error["message"]?.jsonPrimitive?.content ?: "Unknown error",
                )
            } else {
                val status = result?.get("status")?.jsonObject
                val message = status?.get("message")?.jsonObject
                val text =
                    message
                        ?.get("parts")
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.content ?: result.toString()

                A2ATaskResult(
                    success = true,
                    agentName = agentUrl,
                    response = text,
                )
            }
        } catch (e: Exception) {
            A2ATaskResult(
                success = false,
                agentName = agentUrl,
                response = "",
                error = e.message,
            )
        }
}
