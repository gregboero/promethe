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

class WebhookChannelRoutesTest {
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
        val webhookManager = WebhookManager(db, AgentExecutionService(agent, db), io.ktor.client.HttpClient())

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
                webhookRoutes(db, webhookManager)
            }
        }
    }

    @Test
    fun `GET channels returns empty list initially`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            val response = client.get("/api/v1/webhooks/channels")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<WebhookChannelListResponse>(response.bodyAsText())
            assertTrue(body.channels.isEmpty(), "Should return empty list")
        }

    @Test
    fun `POST channels creates channel and GET returns it`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create
            val createResponse = client.post("/api/v1/webhooks/channels") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"id":"wh-test-1","name":"telegram","type":"bidirectional","secret":"mysecret","outboundUrl":"http://localhost/out","enabled":true}""",
                )
            }
            assertEquals(HttpStatusCode.Created, createResponse.status)

            val created = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
            assertEquals("wh-test-1", created["id"]?.jsonPrimitive?.content)

            // GET all
            val getResponse = client.get("/api/v1/webhooks/channels")
            assertEquals(HttpStatusCode.OK, getResponse.status)
            val list = json.decodeFromString<WebhookChannelListResponse>(getResponse.bodyAsText())
            assertEquals(1, list.channels.size)
            val channel = list.channels.first()
            assertEquals("wh-test-1", channel.id)
            assertEquals("telegram", channel.name)
            assertEquals("bidirectional", channel.type)
            assertEquals("mysecret", channel.secret)
            assertEquals("http://localhost/out", channel.outboundUrl)
            assertTrue(channel.enabled)
        }

    @Test
    fun `PUT channels updates existing channel`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create first
            client.post("/api/v1/webhooks/channels") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"wh-upd-1","name":"discord","type":"inbound","enabled":true}""")
            }

            // Update
            val updateResponse = client.put("/api/v1/webhooks/channels/wh-upd-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"name":"discord-updated","type":"outbound","enabled":false}""")
            }
            assertEquals(HttpStatusCode.OK, updateResponse.status)

            // Verify updated values via GET
            val getResponse = client.get("/api/v1/webhooks/channels")
            val list = json.decodeFromString<WebhookChannelListResponse>(getResponse.bodyAsText())
            val channel = list.channels.find { it.id == "wh-upd-1" }
            assertNotNull(channel)
            assertEquals("discord-updated", channel.name)
            assertEquals("outbound", channel.type)
            assertFalse(channel.enabled)
        }

    @Test
    fun `DELETE channels removes channel`() =
        testApplication {
            val db = FakeDatabase()
            configureApp(db)

            // Create first
            client.post("/api/v1/webhooks/channels") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"wh-del-1","name":"slack","type":"inbound","enabled":true}""")
            }

            // Delete
            val deleteResponse = client.delete("/api/v1/webhooks/channels/wh-del-1")
            assertEquals(HttpStatusCode.OK, deleteResponse.status)

            // Verify gone
            val getResponse = client.get("/api/v1/webhooks/channels")
            val list = json.decodeFromString<WebhookChannelListResponse>(getResponse.bodyAsText())
            assertNull(list.channels.find { it.id == "wh-del-1" }, "Should be deleted")
        }
}
