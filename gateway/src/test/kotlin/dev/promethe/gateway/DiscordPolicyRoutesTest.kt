package dev.promethe.gateway

import dev.promethe.api.DiscordAccessPolicy
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class DiscordPolicyRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `owner routes update the live Discord policy`() =
        testApplication {
            val service = DiscordPolicyService(FakeDatabase())
            service.reload()
            var reloads = 0
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/api/v1") {
                        discordPolicyRoutes(service) { reloads++ }
                    }
                }
            }

            val response =
                client.put("/api/v1/channels/discord/policy/users/111") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"allowedTopics":["weather"]}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("111", json.decodeFromString<DiscordAccessPolicy>(response.bodyAsText()).userRules.single().userId)
            assertEquals(1, reloads)

            val listed = client.get("/api/v1/channels/discord/policy")
            assertEquals(listOf("weather"), json.decodeFromString<DiscordAccessPolicy>(listed.bodyAsText()).userRules.single().allowedTopics)
        }

    @Test
    fun `invalid IDs are rejected without reloading Discord`() =
        testApplication {
            val service = DiscordPolicyService(FakeDatabase())
            service.reload()
            var reloads = 0
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/api/v1") {
                        discordPolicyRoutes(service) { reloads++ }
                    }
                }
            }

            val response =
                client.put("/api/v1/channels/discord/policy/channels/not-an-id") {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, reloads)
        }
}
