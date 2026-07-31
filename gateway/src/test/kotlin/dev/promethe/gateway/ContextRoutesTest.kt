package dev.promethe.gateway

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
import kotlin.test.*

class ContextRoutesTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The same whitelist the route uses — clean them up after every test. */
    private val recognizedFiles = listOf(".promethe.md", "SOUL.md", "AGENTS.md", "CONTEXT.md")

    /**
     * Resolve the working directory exactly as ContextRoutes does so we know
     * which folder to clean up.
     */
    private fun resolveWorkingDir(): File {
        val base = File(System.getProperty("user.dir"))
        val candidates = listOf(base, File(base, "promethe"), File(base, "..\\promethe"))
        return candidates.firstOrNull { it.exists() && it.isDirectory } ?: base
    }

    @AfterTest
    fun cleanUp() {
        val dir = resolveWorkingDir()
        recognizedFiles.forEach { name -> File(dir, name).delete() }
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
        routing { route("/api/v1") { contextRoutes() } }
    }

    // ── 1. GET /api/v1/context/files ──────────────────────────────────────

    @Test
    fun `GET context files lists recognized files`() =
        testApplication {
            configureApp()

            val response = client.get("/api/v1/context/files")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<ContextFilesResponse>(response.bodyAsText())
            assertEquals(4, body.files.size, "Should list exactly 4 recognized file slots")
            assertTrue(body.directory.isNotBlank(), "directory field should be non-blank")
            // Each entry must be one of the recognised names
            val names = body.files.map { it.name }.toSet()
            assertTrue(names.containsAll(recognizedFiles), "All recognised files should appear")
        }

    // ── 2. GET unrecognised file → 403 ─────────────────────────────────

    @Test
    fun `GET unrecognized file returns 403`() =
        testApplication {
            configureApp()

            val response = client.get("/api/v1/context/files/evil.md")
            assertEquals(HttpStatusCode.Forbidden, response.status)

            val error = json.decodeFromString<ContextErrorResponse>(response.bodyAsText())
            assertTrue(error.error.contains("recognized", ignoreCase = true))
        }

    // ── 3. PUT context file creates it ─────────────────────────────────

    @Test
    fun `PUT context file creates it`() =
        testApplication {
            configureApp()

            val content = "# Hello Promethe"
            val response = client.put("/api/v1/context/files/.promethe.md") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"$content"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)

            val dto = json.decodeFromString<ContextFileDto>(response.bodyAsText())
            assertEquals(".promethe.md", dto.name)
            assertEquals(content, dto.content)
            assertTrue(dto.exists)
            assertTrue(dto.sizeBytes > 0)
        }

    // ── 4. GET context file returns content after PUT ───────────────────

    @Test
    fun `GET context file returns content`() =
        testApplication {
            configureApp()

            val content = "Soul of the project"
            // Create the file first
            client.put("/api/v1/context/files/SOUL.md") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"$content"}""")
            }

            val response = client.get("/api/v1/context/files/SOUL.md")
            assertEquals(HttpStatusCode.OK, response.status)

            val dto = json.decodeFromString<ContextFileDto>(response.bodyAsText())
            assertEquals("SOUL.md", dto.name)
            assertEquals(content, dto.content)
            assertTrue(dto.exists)
        }

    // ── 5. DELETE context file removes it ───────────────────────────────

    @Test
    fun `DELETE context file removes it`() =
        testApplication {
            configureApp()

            // Create then delete
            client.put("/api/v1/context/files/AGENTS.md") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"agents data"}""")
            }

            val response = client.delete("/api/v1/context/files/AGENTS.md")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.decodeFromString<ContextDeleteResponse>(response.bodyAsText())
            assertTrue(body.deleted)
            assertEquals("AGENTS.md", body.name)
        }

    // ── 6. PUT unrecognised file → 403 ─────────────────────────────────

    @Test
    fun `PUT unrecognized file returns 403`() =
        testApplication {
            configureApp()

            val response = client.put("/api/v1/context/files/evil.md") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"malicious"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)

            val error = json.decodeFromString<ContextErrorResponse>(response.bodyAsText())
            assertTrue(error.error.contains("recognized", ignoreCase = true))
        }
}
