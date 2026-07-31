package dev.promethe.gateway

import dev.promethe.api.*
import dev.promethe.core.*
import dev.promethe.core.hooks.HookManager
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

class SchedulerRoutesIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun createDummyAgent(db: FakeDatabase): AIAgent {
        val config = AgentConfig()
        val llm = KoogLlmAdapter(config, db)
        val pm = ProfileManager(null)
        val ae = ActionExecutor(config, io.ktor.client.HttpClient(), hookManager = HookManager())
        val sl = SkillLoader(okio.FileSystem.SYSTEM, getProfileDirectoryPath(config))
        val sw = SkillWriter(okio.FileSystem.SYSTEM, getProfileDirectoryPath(config))
        val te = TrajectoryEvaluator(llm, config)
        return AIAgent(
            config = config,
            database = db,
            llmAdapter = llm,
            profileManager = pm,
            actionExecutor = ae,
            skillLoader = sl,
            trajectoryEvaluator = te,
            skillWriter = sw,
        )
    }

    private fun ApplicationTestBuilder.configureApp(db: FakeDatabase) {
        val agent = createDummyAgent(db)
        val scheduler = TaskScheduler(db)

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
                schedulerRoutes(db, scheduler)
            }
        }
    }

    @Test
    fun `GET tasks returns empty list initially`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            val response = client.get("/api/v1/scheduler/tasks")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<ScheduledTaskListResponse>(response.bodyAsText())
            assertTrue(body.tasks.isEmpty(), "Should return empty list")
        }

    @Test
    fun `POST tasks creates task and GET returns it`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            val createResponse = client.post("/api/v1/scheduler/tasks") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"Backup job","cronExpression":"0 0 * * *","prompt":"Perform database backup","profileId":"test-profile","enabled":true}""",
                )
            }
            assertEquals(HttpStatusCode.Created, createResponse.status)

            val created = json.decodeFromString<ScheduledTaskResponse>(createResponse.bodyAsText())
            assertNotNull(created.id)
            assertEquals("Backup job", created.name)
            assertEquals("0 0 * * *", created.cronExpression)
            assertEquals("Perform database backup", created.prompt)
            assertEquals("test-profile", created.profileId)
            assertTrue(created.enabled)

            // GET single task
            val getSingleResponse = client.get("/api/v1/scheduler/tasks/${created.id}")
            assertEquals(HttpStatusCode.OK, getSingleResponse.status)
            val fetched = json.decodeFromString<ScheduledTaskResponse>(getSingleResponse.bodyAsText())
            assertEquals(created.id, fetched.id)
            assertEquals("Backup job", fetched.name)

            // GET all tasks
            val getAllResponse = client.get("/api/v1/scheduler/tasks")
            assertEquals(HttpStatusCode.OK, getAllResponse.status)
            val list = json.decodeFromString<ScheduledTaskListResponse>(getAllResponse.bodyAsText())
            assertEquals(1, list.tasks.size)
            assertEquals(created.id, list.tasks.first().id)
        }

    @Test
    fun `GET tasks by unknown id returns 404`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            val response = client.get("/api/v1/scheduler/tasks/nonexistent")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `PUT tasks updates task`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create first
            val createResponse = client.post("/api/v1/scheduler/tasks") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Original task","cronExpression":"0 0 * * *","prompt":"Original prompt"}""")
            }
            val created = json.decodeFromString<ScheduledTaskResponse>(createResponse.bodyAsText())

            // Update
            val updateResponse = client.put("/api/v1/scheduler/tasks/${created.id}") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"Updated task","cronExpression":"*/5 * * * *","prompt":"Updated prompt","profileId":"new-profile","enabled":false}""",
                )
            }
            assertEquals(HttpStatusCode.OK, updateResponse.status)

            val updated = json.decodeFromString<ScheduledTaskResponse>(updateResponse.bodyAsText())
            assertEquals("Updated task", updated.name)
            assertEquals("*/5 * * * *", updated.cronExpression)
            assertEquals("Updated prompt", updated.prompt)
            assertEquals("new-profile", updated.profileId)
            assertFalse(updated.enabled)
        }

    @Test
    fun `DELETE tasks removes task`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create first
            val createResponse = client.post("/api/v1/scheduler/tasks") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"To delete","cronExpression":"0 0 * * *","prompt":"Delete me"}""")
            }
            val created = json.decodeFromString<ScheduledTaskResponse>(createResponse.bodyAsText())

            // Delete
            val deleteResponse = client.delete("/api/v1/scheduler/tasks/${created.id}")
            assertEquals(HttpStatusCode.OK, deleteResponse.status)

            // Verify gone
            val getResponse = client.get("/api/v1/scheduler/tasks/${created.id}")
            assertEquals(HttpStatusCode.NotFound, getResponse.status)
        }

    @Test
    fun `POST tasks toggle switches enabled state`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create first
            val createResponse = client.post("/api/v1/scheduler/tasks") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Toggle task","cronExpression":"0 0 * * *","prompt":"Toggle me","enabled":true}""")
            }
            val created = json.decodeFromString<ScheduledTaskResponse>(createResponse.bodyAsText())
            assertTrue(created.enabled)

            // Toggle (disable)
            val toggleResponse1 = client.post("/api/v1/scheduler/tasks/${created.id}/toggle")
            assertEquals(HttpStatusCode.OK, toggleResponse1.status)
            val toggled1 = json.decodeFromString<ScheduledTaskResponse>(toggleResponse1.bodyAsText())
            assertFalse(toggled1.enabled)

            // Toggle again (enable)
            val toggleResponse2 = client.post("/api/v1/scheduler/tasks/${created.id}/toggle")
            assertEquals(HttpStatusCode.OK, toggleResponse2.status)
            val toggled2 = json.decodeFromString<ScheduledTaskResponse>(toggleResponse2.bodyAsText())
            assertTrue(toggled2.enabled)
        }
}
