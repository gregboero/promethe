package dev.promethe.core.acp

import dev.promethe.core.Log

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import dev.promethe.core.PrometheJson

/**
 * ACP Client — consumes remote ACP-compatible agents as if they were local tools.
 *
 * Workflow:
 *   1. Discover remote agent via /.well-known/acp.json
 *   2. Parse capabilities into AcpCapability list
 *   3. Invoke capabilities via POST /acp/invoke
 *
 * @param httpClient Shared Ktor HttpClient
 * @param apiKey     Optional bearer token for authenticated agents
 */
class AcpClient(
    private val httpClient: HttpClient,
    private val apiKey: String = "",
) {
    private val logger = Log.create("AcpClient")
    private val json = PrometheJson

    /**
     * Discover a remote agent's capabilities.
     *
     * @param baseUrl The base URL of the remote agent (e.g., "http://agent.example.com:8080")
     * @return The agent's AcpAgentCard, or null if discovery fails
     */
    suspend fun discover(baseUrl: String): AcpAgentCard? =
        try {
            val response = httpClient.get("$baseUrl/.well-known/acp.json") {
                if (apiKey.isNotBlank()) {
                    headers { append("Authorization", "Bearer $apiKey") }
                }
            }
            if (response.status.value in 200..299) {
                json.decodeFromString<AcpAgentCard>(response.bodyAsText())
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Discovery failed for $baseUrl" }
            null
        }

    /**
     * Invoke a capability on a remote ACP agent.
     *
     * @param baseUrl      Remote agent base URL
     * @param capabilityId ID of the capability to invoke
     * @param input        Text input for the capability
     * @param parameters   Optional key-value parameters
     * @return AcpResponse with output or error
     */
    suspend fun invoke(
        baseUrl: String,
        capabilityId: String,
        input: String,
        parameters: Map<String, String> = emptyMap(),
    ): AcpResponse =
        try {
            val request = AcpRequest(
                capabilityId = capabilityId,
                input = input,
                parameters = parameters,
            )
            val response = httpClient.post("$baseUrl/acp/invoke") {
                contentType(ContentType.Application.Json)
                if (apiKey.isNotBlank()) {
                    headers { append("Authorization", "Bearer $apiKey") }
                }
                setBody(json.encodeToString(AcpRequest.serializer(), request))
            }
            val body = response.bodyAsText()
            if (response.status.value in 200..299) {
                json.decodeFromString<AcpResponse>(body)
            } else {
                AcpResponse(
                    output = "",
                    status = AcpStatus.FAILED,
                    error = "HTTP ${response.status.value}: $body",
                )
            }
        } catch (e: Exception) {
            AcpResponse(
                output = "",
                status = AcpStatus.FAILED,
                error = "Invocation failed: ${e.message}",
            )
        }
}
