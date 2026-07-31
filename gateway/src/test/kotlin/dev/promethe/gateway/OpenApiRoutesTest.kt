package dev.promethe.gateway

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OpenApiRoutesTest {
    @Test
    fun `OpenAPI is generated from mounted routes`() =
        testApplication {
            application { install(ContentNegotiation) { json() } }
            routing {
                openApiRoutes()
                route("/api/v1/widgets") {
                    get { call.respond("ok") }
                    post { call.respond("ok") }
                    delete("/{id}") { call.respond("ok") }
                }
            }

            val response = client.get("/api/v1/openapi.json")
            assertEquals(HttpStatusCode.OK, response.status)
            val document = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val paths = document["paths"]?.jsonObject
            assertNotNull(paths?.get("/api/v1/widgets")?.jsonObject?.get("get"))
            assertNotNull(paths?.get("/api/v1/widgets")?.jsonObject?.get("post"))
            assertNotNull(paths?.get("/api/v1/widgets/{id}")?.jsonObject?.get("delete"))
            assertNotNull(paths?.get("/api/v1/openapi.json")?.jsonObject?.get("get"))
        }
}
