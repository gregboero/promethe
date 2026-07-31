package dev.promethe.gateway

import dev.promethe.api.PluginListResponse
import dev.promethe.api.PluginResponse
import dev.promethe.api.PluginToggleRequest
import dev.promethe.core.PluginLoader
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class PluginRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var tempPluginsDir: File

    @BeforeTest
    fun setUp() {
        tempPluginsDir = Files.createTempDirectory("promethe-test-plugins").toFile()
        // Create a test plugin
        val testPluginDir = File(tempPluginsDir, "test-plugin")
        testPluginDir.mkdirs()
        val manifestFile = File(testPluginDir, "plugin.json")
        manifestFile.writeText(
            """
            {
              "name": "test-plugin",
              "version": "1.2.3",
              "description": "Un plugin de test",
              "author": "Greg",
              "enabled": true,
              "tools": [
                {
                  "name": "test_tool",
                  "description": "A test tool"
                }
              ]
            }
            """.trimIndent(),
        )
    }

    @AfterTest
    fun tearDown() {
        tempPluginsDir.deleteRecursively()
    }

    private fun ApplicationTestBuilder.configureApp(pluginLoader: PluginLoader) {
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
                pluginRoutes(pluginLoader)
            }
        }
    }

    @Test
    fun `GET plugins returns list of discovered plugins`() =
        testApplication {
            val loader = PluginLoader(pluginsDir = tempPluginsDir.absolutePath)
            configureApp(loader)

            val response = client.get("/api/v1/plugins")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<PluginListResponse>(response.bodyAsText())
            assertEquals(1, body.totalPlugins)
            assertEquals(0, body.enabledPlugins)
            assertEquals(0, body.totalTools)
            assertEquals("test-plugin", body.plugins.first().name)
            assertEquals("1.2.3", body.plugins.first().version)
        }

    @Test
    fun `GET plugin by name returns detail`() =
        testApplication {
            val loader = PluginLoader(pluginsDir = tempPluginsDir.absolutePath)
            configureApp(loader)

            val response = client.get("/api/v1/plugins/test-plugin")
            assertEquals(HttpStatusCode.OK, response.status)

            val plugin = json.decodeFromString<PluginResponse>(response.bodyAsText())
            assertEquals("test-plugin", plugin.name)
            assertEquals("1.2.3", plugin.version)
            assertEquals("Un plugin de test", plugin.description)
            assertFalse(plugin.enabled)
        }

    @Test
    fun `GET unknown plugin returns 404`() =
        testApplication {
            val loader = PluginLoader(pluginsDir = tempPluginsDir.absolutePath)
            configureApp(loader)

            val response = client.get("/api/v1/plugins/UnknownPlugin")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `POST toggle returns 501`() =
        testApplication {
            val loader = PluginLoader(pluginsDir = tempPluginsDir.absolutePath)
            configureApp(loader)

            val response = client.post("/api/v1/plugins/test-plugin/toggle") {
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":false}""")
            }
            assertEquals(HttpStatusCode.NotImplemented, response.status)
        }
}
