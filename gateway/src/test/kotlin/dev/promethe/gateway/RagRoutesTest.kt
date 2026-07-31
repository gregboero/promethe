package dev.promethe.gateway

import dev.promethe.api.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlin.test.*

class RagRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.configureApp() {
        application {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    },
                )
            }
        }
        routing {
            route("/api/v1") {
                ragRoutes()
            }
        }
    }

    @Test
    fun `GET rag models returns KNOWN_MODELS`() =
        testApplication {
            configureApp()

            val response = client.get("/api/v1/rag/models")
            assertEquals(HttpStatusCode.OK, response.status)

            val models = json.decodeFromString<List<EmbeddingModelInfo>>(response.bodyAsText())
            assertFalse(models.isEmpty())
            assertTrue(models.any { it.id == "nomic-embed-text" })
        }

    @Test
    fun `GET rag config returns default config`() =
        testApplication {
            configureApp()

            val response = client.get("/api/v1/rag/config")
            assertEquals(HttpStatusCode.OK, response.status)

            val config = json.decodeFromString<RagConfig>(response.bodyAsText())
            assertFalse(config.enabled)
            assertEquals(EmbeddingProvider.OLLAMA, config.embeddingProvider)
            assertEquals("nomic-embed-text", config.embeddingModel)
        }

    @Test
    fun `PUT rag config updates config`() =
        testApplication {
            configureApp()

            val newConfigJson =
                """
                {
                  "enabled": true,
                  "embeddingProvider": "GEMINI",
                  "embeddingModel": "text-embedding-004",
                  "embeddingBaseUrl": "https://api.google.com",
                  "embeddingApiKey": "secret-key",
                  "embeddingDimensions": 768,
                  "vectorStoreType": "CHROMA",
                  "vectorStoreUrl": "http://localhost:8000",
                  "chunkSize": 256,
                  "chunkOverlap": 20
                }
                """.trimIndent()

            val response = client.put("/api/v1/rag/config") {
                contentType(ContentType.Application.Json)
                setBody(newConfigJson)
            }
            assertEquals(HttpStatusCode.OK, response.status)

            val updated = json.decodeFromString<RagConfig>(response.bodyAsText())
            assertTrue(updated.enabled)
            assertEquals(EmbeddingProvider.GEMINI, updated.embeddingProvider)
            assertEquals("text-embedding-004", updated.embeddingModel)
            assertEquals("https://api.google.com", updated.embeddingBaseUrl)
            assertEquals("secret-key", updated.embeddingApiKey)
            assertEquals(768, updated.embeddingDimensions)
            assertEquals(VectorStoreType.CHROMA, updated.vectorStoreType)
            assertEquals("http://localhost:8000", updated.vectorStoreUrl)
            assertEquals(256, updated.chunkSize)
            assertEquals(20, updated.chunkOverlap)

            // Verify with subsequent GET
            val getResponse = client.get("/api/v1/rag/config")
            val config = json.decodeFromString<RagConfig>(getResponse.bodyAsText())
            assertTrue(config.enabled)
            assertEquals(EmbeddingProvider.GEMINI, config.embeddingProvider)
        }

    @Test
    fun `POST test-embedding returns result`() =
        testApplication {
            configureApp()

            val response = client.post("/api/v1/rag/test-embedding")
            assertEquals(HttpStatusCode.OK, response.status)

            val testResponse = json.decodeFromString<RagTestResponse>(response.bodyAsText())
            // Without a real embedding server, this returns false with an error
            assertFalse(testResponse.success)
            assertNotNull(testResponse.error)
        }

    @Test
    fun `GET rag documents returns empty list`() =
        testApplication {
            configureApp()

            val response = client.get("/api/v1/rag/documents")
            assertEquals(HttpStatusCode.OK, response.status)

            val docs = json.decodeFromString<List<RagDocumentInfo>>(response.bodyAsText())
            assertTrue(docs.isEmpty())
        }

    @Test
    fun `POST rag ingest returns 503 when disabled`() =
        testApplication {
            configureApp()

            // Ensure RAG is disabled
            client.put("/api/v1/rag/config") {
                contentType(ContentType.Application.Json)
                setBody("""{ "enabled": false }""")
            }

            val response = client.post("/api/v1/rag/ingest") {
                contentType(ContentType.Application.Json)
                setBody("""{ "content": "hello", "filename": "test.txt" }""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

    @Test
    fun `POST rag search returns 503 when disabled`() =
        testApplication {
            configureApp()

            val response = client.post("/api/v1/rag/search") {
                contentType(ContentType.Application.Json)
                setBody("""{ "query": "hello" }""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }

    @Test
    fun `DELETE rag document returns 503 when disabled`() =
        testApplication {
            configureApp()

            val response = client.delete("/api/v1/rag/documents/doc-123")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }
}
