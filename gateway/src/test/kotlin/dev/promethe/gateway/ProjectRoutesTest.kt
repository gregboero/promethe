package dev.promethe.gateway

import dev.promethe.api.ProjectInfo
import dev.promethe.api.ProjectListResponse
import dev.promethe.api.SessionInfo
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.configureApp(
        database: FakeDatabase,
        workspaceRoot: String,
    ) {
        application {
            install(ContentNegotiation) {
                json(Json { encodeDefaults = true })
            }
            routing {
                route("/api/v1") {
                    projectRoutes(database, workspaceRoot)
                    sessionRoutes(database)
                }
            }
        }
    }

    @Test
    fun `project becomes active and new sessions inherit it`() =
        testApplication {
            val workspace = createTempDirectory("promethe-project-route")
            try {
                configureApp(FakeDatabase(), workspace.toString())
                val createdResponse =
                    client.post("/api/v1/projects") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"name":"Public release","description":"Release work","instructions":"Keep tests green"}""",
                        )
                    }
                assertEquals(HttpStatusCode.Created, createdResponse.status)
                val project = json.decodeFromString<ProjectInfo>(createdResponse.bodyAsText())
                assertTrue(project.active)
                assertTrue(Files.exists(workspace.resolve(project.workspacePath).resolve("PROJECT.md")))

                val sessionResponse =
                    client.post("/api/v1/sessions") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"id":"release-chat"}""")
                    }
                val session = json.decodeFromString<SessionInfo>(sessionResponse.bodyAsText())
                assertEquals(project.id, session.projectId)

                val list = json.decodeFromString<ProjectListResponse>(client.get("/api/v1/projects").bodyAsText())
                assertEquals(project.id, list.activeProjectId)
                assertEquals(1, list.projects.single().sessionCount)
            } finally {
                workspace.toFile().deleteRecursively()
            }
        }

    @Test
    fun `session can be moved and archived projects cannot be activated`() =
        testApplication {
            val workspace = createTempDirectory("promethe-project-assignment")
            try {
                configureApp(FakeDatabase(), workspace.toString())
                val project =
                    json.decodeFromString<ProjectInfo>(
                        client.post("/api/v1/projects") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"name":"Workspace"}""")
                        }.bodyAsText(),
                    )
                client.post("/api/v1/sessions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"id":"existing","projectId":null}""")
                }
                val assigned =
                    client.patch("/api/v1/sessions/existing/project") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"projectId":"${project.id}"}""")
                    }
                assertEquals(project.id, json.decodeFromString<SessionInfo>(assigned.bodyAsText()).projectId)

                client.put("/api/v1/projects/${project.id}") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"archived":true}""")
                }
                val activation =
                    client.put("/api/v1/projects/${project.id}/active") {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                assertEquals(HttpStatusCode.Conflict, activation.status)
            } finally {
                workspace.toFile().deleteRecursively()
            }
        }
}
