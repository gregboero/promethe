package dev.promethe.gateway.providers

import dev.promethe.api.providers.CertificationStatus
import dev.promethe.api.providers.ModelCapability
import dev.promethe.api.providers.ProviderAvailability
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicProviderCatalogSourceTest {
    @Test
    fun `discovers provider-specific payloads without putting keys in URLs`() =
        runTest {
            val requests = CopyOnWriteArrayList<String>()
            val client = HttpClient(
                MockEngine { request ->
                    requests += "${request.url.host}${request.url.encodedPath}"
                    assertFalse(request.url.toString().contains("test-secret"))
                    assertHeader(request, request.url.host)
                    responseFor(request)
                },
            )
            val source = DynamicProviderCatalogSource(client, resolverForAllProviders())

            try {
                val catalog = source.catalog(forceReload = true)
                val openAiModels = catalog.modelsFor("openai").orEmpty()
                val openRouterModel = catalog.modelsFor("openrouter")!!.single()

                assertEquals(CertificationStatus.BETA, openAiModels.first { it.id == "gpt-5.6-sol" }.certificationStatus)
                assertEquals(CertificationStatus.UNVERIFIED, openAiModels.first { it.id == "openai-custom" }.certificationStatus)
                assertEquals(CertificationStatus.UNVERIFIED, openRouterModel.certificationStatus)
                assertEquals(listOf("tools", "response_format"), openRouterModel.supportedParameters)
                assertTrue(ModelCapability.VISION in openRouterModel.capabilities)
                assertEquals("2027-01-01T00:00:00Z", openRouterModel.expiresAt)
                assertEquals(ProviderAvailability.AVAILABLE, catalog.providers().first { it.id == "nvidia" }.availability)
                assertTrue(requests.contains("litellm.test/model/info"))
                assertTrue(requests.contains("litellm.test/v1/models"))
                assertTrue(requests.contains("kimi.test/v1/models"))
                assertTrue(requests.contains("xai.test/v1/models"))
                assertEquals(1_048_576, catalog.modelsFor("kimi")!!.single { it.id == "kimi-k3" }.contextWindowTokens)
                assertEquals(500_000, catalog.modelsFor("xai")!!.single { it.id == "grok-4.5" }.contextWindowTokens)
            } finally {
                client.close()
            }
        }

    @Test
    fun `cache avoids requests and invalidate reloads the last valid catalog`() =
        runTest {
            var calls = 0
            var fail = false
            val client = HttpClient(
                MockEngine { request ->
                    calls += 1
                    responseFor(request, fail)
                },
            )
            val source = DynamicProviderCatalogSource(
                httpClient = client,
                credentials = ProviderCredentialResolver { providerId ->
                    if (providerId == "openai") ProviderAccess("test-secret", "https://openai.test") else ProviderAccess()
                },
            )

            try {
                val first = source.catalog()
                val firstRefreshCalls = calls
                source.catalog()
                assertEquals(firstRefreshCalls, calls)

                fail = true
                source.invalidate()
                val stale = source.catalog()
                assertEquals(firstRefreshCalls * 2, calls)
                assertEquals(ProviderAvailability.STALE, stale.providers().first { it.id == "openai" }.availability)
                assertTrue(stale.modelsFor("openai").orEmpty().any { it.id == "gpt-5.6-sol" })
                assertEquals(ProviderAvailability.AVAILABLE, first.providers().first { it.id == "openai" }.availability)
            } finally {
                client.close()
            }
        }

    private fun resolverForAllProviders(): ProviderCredentialResolver =
        ProviderCredentialResolver { providerId ->
            ProviderAccess("test-secret", "https://$providerId.test")
        }

    private fun MockRequestHandleScope.responseFor(
        request: HttpRequestData,
        fail: Boolean = false,
    ) = if (fail) {
        respond("{}", HttpStatusCode.ServiceUnavailable)
    } else {
        val body = when {
            request.url.host == "litellm.test" && request.url.encodedPath == "/model/info" -> ""
            else -> responseBodies[request.url.host] ?: "{}"
        }
        val status = if (request.url.host == "litellm.test" && request.url.encodedPath == "/model/info") {
            HttpStatusCode.NotFound
        } else {
            HttpStatusCode.OK
        }
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
    }

    private fun assertHeader(
        request: HttpRequestData,
        providerHost: String,
    ) {
        when (providerHost) {
            "anthropic.test" -> assertEquals("test-secret", request.headers["x-api-key"])
            "google.test" -> assertEquals("test-secret", request.headers["x-goog-api-key"])
            else -> assertEquals("Bearer test-secret", request.headers[HttpHeaders.Authorization])
        }
    }

    private companion object {
        val responseBodies = mapOf(
            "openai.test" to """{"data":[{"id":"gpt-5.6-sol","supported_parameters":["tools","response_format"]},{"id":"openai-custom"}]}""",
            "anthropic.test" to """{"data":[{"id":"claude-sonnet-5","display_name":"Claude Sonnet 5"}]}""",
            "google.test" to """{"models":[{"name":"models/gemini-3.1-pro-preview","displayName":"Gemini 3.1 Pro Preview","supportedGenerationMethods":["generateContent"],"inputTokenLimit":100000,"outputTokenLimit":8192}]}""",
            "deepseek.test" to """{"data":[{"id":"deepseek-v4-pro"}]}""",
            "nvidia.test" to """{"data":[{"id":"nvidia/custom"}]}""",
            "litellm.test" to """{"data":[{"id":"litellm/custom"}]}""",
            "openrouter.test" to """{"data":[{"id":"vendor/custom","name":"Vendor Custom","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},"supported_parameters":["tools","response_format"],"expiration_date":"2027-01-01T00:00:00Z"}]}""",
            "kimi.test" to """{"data":[{"id":"kimi-k3","context_length":1048576,"supported_parameters":["tools","reasoning_effort"]}]}""",
            "xai.test" to """{"data":[{"id":"grok-4.5","context_length":500000,"supported_parameters":["tools","reasoning_effort"]}]}""",
        )
    }
}
