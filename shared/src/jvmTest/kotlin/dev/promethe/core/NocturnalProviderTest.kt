package dev.promethe.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** Paid provider checks. They run only through providerLiveTest with an explicit opt-in flag. */
class NocturnalProviderTest {
    @Test
    fun `Kimi K3 accepts a current chat request`() =
        runBlocking {
            if (!liveTestsEnabled()) return@runBlocking
            val key = System.getenv("MOONSHOT_API_KEY")?.takeIf { it.isNotBlank() } ?: return@runBlocking
            val baseUrl = System.getenv("MOONSHOT_BASE_URL")?.trimEnd('/') ?: "https://api.moonshot.ai"

            providerClient().use { client ->
                val response = client.post("$baseUrl/v1/chat/completions") {
                    bearerAuth(key)
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(
                        """{"model":"kimi-k3","messages":[{"role":"user","content":"Reply with OK."}],"reasoning_effort":"low","max_completion_tokens":32}""",
                    )
                }
                assertTrue(response.status.value in 200..299, "Kimi live contract failed: ${response.status}")
            }
        }

    @Test
    fun `Grok 4_5 accepts a current Responses request`() =
        runBlocking {
            if (!liveTestsEnabled()) return@runBlocking
            val key = System.getenv("XAI_API_KEY")?.takeIf { it.isNotBlank() } ?: return@runBlocking
            val baseUrl = System.getenv("XAI_BASE_URL")?.trimEnd('/') ?: "https://api.x.ai"

            providerClient().use { client ->
                val response = client.post("$baseUrl/v1/responses") {
                    bearerAuth(key)
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(
                        """{"model":"grok-4.5","input":[{"role":"user","content":"Reply with OK."}],"reasoning":{"effort":"low"},"include":["reasoning.encrypted_content"],"store":false,"max_output_tokens":32}""",
                    )
                }
                assertTrue(response.status.value in 200..299, "xAI live contract failed: ${response.status}")
            }
        }

    private fun liveTestsEnabled(): Boolean = System.getenv("PROMETHE_PROVIDER_LIVE_TESTS").equals("true", ignoreCase = true)

    private fun providerClient(): HttpClient =
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 15_000
            }
        }
}
