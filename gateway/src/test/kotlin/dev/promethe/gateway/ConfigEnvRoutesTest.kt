package dev.promethe.gateway

import dev.promethe.core.CredentialsStore
import dev.promethe.core.ProviderSecretRegistry
import dev.promethe.core.RuntimeConfigRegistry
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

class ConfigEnvRoutesTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Redirect the whole Promethe home to a throwaway temp dir for the test's
     * lifetime -- the real ~/.promethe/credentials.json is never touched.
     */
    private var tempHome: java.io.File? = null
    private var previousHome: String? = null

    @BeforeTest
    fun setUp() {
        val dir = kotlin.io.path.createTempDirectory("promethe-test-home").toFile()
        tempHome = dir
        previousHome = System.getProperty("promethe.home")
        System.setProperty("promethe.home", dir.absolutePath)
        CredentialsStore.save(CredentialsStore.Credentials())
    }

    @AfterTest
    fun tearDown() {
        if (previousHome == null) {
            System.clearProperty("promethe.home")
        } else {
            System.setProperty("promethe.home", previousHome)
        }
        tempHome?.deleteRecursively()
        tempHome = null
        previousHome = null
    }

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
        routing { route("/api/v1") { configEnvRoutes() } }
    }

    // GET /config/env returns 200 with a map.
    @Test
    fun `GET config env returns 200 with a map`() =
        testApplication {
            configureApp()

            val response = client.get("/api/v1/config/env")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertNotNull(body, "Response should be a valid map")
        }

    // PUT /config/env/{key} sets a value.
    @Test
    fun `PUT config env key sets the value`() =
        testApplication {
            configureApp()

            val response =
                client.put("/api/v1/config/env/SEARXNG_URL") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"http://localhost:9090"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("set", body["status"])
            assertEquals("SEARXNG_URL", body["key"])

            // Verify the value was persisted through CredentialsStore.
            val creds = CredentialsStore.load()
            assertNotNull(creds)
            assertEquals("http://localhost:9090", creds.searxngUrl)
        }

    // PUT with a masked value returns 400.
    @Test
    fun `PUT config env key with masked value returns 400`() =
        testApplication {
            configureApp()

            val response =
                client.put("/api/v1/config/env/TAVILY_API_KEY") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"tvly***"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)

            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertTrue(
                body["error"]?.contains("Masked") == true || body["error"]?.contains("masked") == true,
                "Error message should mention masked value: ${body["error"]}",
            )
        }

    // DELETE /config/env/{key} returns 200.
    @Test
    fun `DELETE config env key returns 200`() =
        testApplication {
            configureApp()

            // First set a value, then delete it.
            client.put("/api/v1/config/env/BROWSER_BACKEND") {
                contentType(ContentType.Application.Json)
                setBody("""{"value":"playwright"}""")
            }

            val response = client.delete("/api/v1/config/env/BROWSER_BACKEND")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("removed", body["status"])
            assertEquals("BROWSER_BACKEND", body["key"])

            // Verify value was reset.
            val creds = CredentialsStore.load()
            assertNotNull(creds)
            assertEquals("", creds.browserBackend)
        }

    // Sensitive keys are masked in GET responses.
    @Test
    fun `GET config env masks sensitive keys`() =
        testApplication {
            configureApp()

            // Set a sensitive key and a non-sensitive one.
            client.put("/api/v1/config/env/TAVILY_API_KEY") {
                contentType(ContentType.Application.Json)
                setBody("""{"value":"tvly-abcdef123456"}""")
            }
            client.put("/api/v1/config/env/SEARXNG_URL") {
                contentType(ContentType.Application.Json)
                setBody("""{"value":"http://searx.local"}""")
            }

            val response = client.get("/api/v1/config/env")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            // TAVILY_API_KEY contains "key" and must be masked.
            val tavilyValue = body["TAVILY_API_KEY"]
            assertNotNull(tavilyValue, "TAVILY_API_KEY should be present in response")
            assertTrue(
                tavilyValue.endsWith("***"),
                "Sensitive key value should be masked with ***: got '$tavilyValue'",
            )
            assertEquals("tvly***", tavilyValue, "Masked value should be first 4 chars + ***")

            // SEARXNG_URL is not sensitive and remains cleartext.
            val searxValue = body["SEARXNG_URL"]
            assertNotNull(searxValue, "SEARXNG_URL should be present in response")
            assertEquals("http://searx.local", searxValue, "Non-sensitive value should be returned in cleartext")
        }

    // Short sensitive values are fully masked.
    @Test
    fun `GET config env fully masks short sensitive values`() =
        testApplication {
            configureApp()

            // Set a sensitive key with a short value.
            client.put("/api/v1/config/env/FAL_KEY") {
                contentType(ContentType.Application.Json)
                setBody("""{"value":"ab"}""")
            }

            val response = client.get("/api/v1/config/env")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            // FAL_KEY contains "key" and is fully masked when short.
            val falValue = body["FAL_KEY"]
            assertNotNull(falValue, "FAL_KEY should be present in response")
            assertEquals("***", falValue, "Short sensitive value should be fully masked")
        }

    // Token-containing keys are also masked.
    @Test
    fun `GET config env masks token keys`() =
        testApplication {
            configureApp()

            client.put("/api/v1/config/env/REPLICATE_API_TOKEN") {
                contentType(ContentType.Application.Json)
                setBody("""{"value":"r8_longTokenValue12345"}""")
            }

            val response = client.get("/api/v1/config/env")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            // REPLICATE_API_TOKEN contains "token" and must not be returned in cleartext.
            val replicateValue = body["REPLICATE_API_TOKEN"]
            assertNotNull(replicateValue, "REPLICATE_API_TOKEN should be present in response")
            assertTrue(
                replicateValue.endsWith("***"),
                "Token-containing key should be masked: got '$replicateValue'",
            )
            assertNotEquals("r8_longTokenValue12345", replicateValue, "Token value must not be returned in cleartext")
        }

    @Test
    fun `all canonical provider and runtime keys persist while unknown keys are rejected`() =
        testApplication {
            configureApp()

            ProviderSecretRegistry.definitions.forEach { definition ->
                val response =
                    client.put("/api/v1/config/env/${definition.envKey}") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"value":"test-${definition.id}"}""")
                    }
                assertEquals(HttpStatusCode.OK, response.status, definition.envKey)
            }

            val credentials = assertNotNull(CredentialsStore.load())
            val resolved = ProviderSecretRegistry.resolve(credentials)
            ProviderSecretRegistry.definitions.forEach { definition ->
                assertEquals("test-${definition.id}", resolved[definition.id], definition.envKey)
            }
            RuntimeConfigRegistry.keys.forEach { key ->
                val response =
                    client.put("/api/v1/config/env/$key") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"value":"runtime-$key"}""")
                    }
                assertEquals(HttpStatusCode.OK, response.status, key)
            }

            val allConfig = assertNotNull(CredentialsStore.load()).toEnvMap()
            RuntimeConfigRegistry.keys.forEach { key ->
                assertEquals("runtime-$key", allConfig[key], key)
            }
            ProviderSecretRegistry.definitions.forEach { definition ->
                assertEquals("test-${definition.id}", ProviderSecretRegistry.resolve(assertNotNull(CredentialsStore.load()))[definition.id])
            }

            val unknown =
                client.put("/api/v1/config/env/NOT_A_PROMETHE_KEY") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"value":"ignored"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
        }
}
