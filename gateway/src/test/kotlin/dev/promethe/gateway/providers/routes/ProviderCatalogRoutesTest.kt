package dev.promethe.gateway.providers.routes

import dev.promethe.api.providers.ModelCapability
import dev.promethe.api.providers.ModelDescriptor
import dev.promethe.api.providers.ProviderDescriptor
import dev.promethe.gateway.providers.ProviderCatalog
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderCatalogRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.configureApp() {
        application { providerCatalogTestModule() }
    }

    private fun Application.providerCatalogTestModule() {
        install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
        routing { installProviderCatalogRoutes(ProviderCatalog.initial()) }
    }

    @Test
    fun `providers endpoint returns the initial catalog`() =
        testApplication {
            configureApp()

            val response = client.get("/api/v1/providers")
            assertEquals(HttpStatusCode.OK, response.status)
            val providers = json.decodeFromString<List<ProviderDescriptor>>(response.bodyAsText())

            assertTrue(providers.size >= 8)
            assertEquals(providers.size, providers.map { it.id }.distinct().size)
            assertTrue(providers.any { it.id == "openai" })
            assertTrue(providers.all { it.capabilities.isNotEmpty() })
        }

    @Test
    fun `models endpoint returns models for a provider`() =
        testApplication {
            configureApp()

            val response = client.get("/api/v1/providers/openai/models")
            assertEquals(HttpStatusCode.OK, response.status)
            val models = json.decodeFromString<List<ModelDescriptor>>(response.bodyAsText())

            assertTrue(models.any { it.id == "gpt-5.6-sol" })
            assertTrue(models.all { it.providerId == "openai" })
            assertTrue(models.all { ModelCapability.CHAT in it.capabilities || ModelCapability.REASONING in it.capabilities })
        }

    @Test
    fun `unknown provider is not silently treated as an empty catalog`() =
        testApplication {
            configureApp()

            val response = client.get("/api/v1/providers/unknown/models")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `responses contain no credentials or secret values`() =
        testApplication {
            configureApp()

            val raw = client.get("/api/v1/providers").bodyAsText() +
                client.get("/api/v1/providers/openai/models").bodyAsText()
            assertFalse(raw.contains("apiKey", ignoreCase = true))
            assertFalse(raw.contains("secret", ignoreCase = true))
            assertFalse(raw.contains("Bearer ", ignoreCase = true))
            assertFalse(raw.contains("sk-"))
        }
}
