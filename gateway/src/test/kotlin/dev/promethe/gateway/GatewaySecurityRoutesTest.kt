package dev.promethe.gateway

import dev.promethe.gateway.auth.OwnerAuthService
import dev.promethe.db.DatabaseFactory
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class GatewaySecurityRoutesTest {
    private val apiKey = "pk-prom-local-test-key"
    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.configureSecureRoutes(): OwnerAuthService {
        val auth = OwnerAuthService(DatabaseFactory.createInMemory(), sessionTtlMinutes = 15)
        val security = GatewaySecurityConfig(
            bindHost = "127.0.0.1",
            remoteAccessEnabled = true,
            allowedOrigins = emptySet(),
            remoteSessionTtlMinutes = 15,
        )
        application {
            install(ContentNegotiation) { json(json) }
            AuthMiddleware.install(this, apiKey, security, auth)
            routing {
                authRoutes(auth, security)
                systemRoutes(0)
                get("/api/private") { call.respond(mapOf("ok" to true)) }
            }
        }
        return auth
    }

    @Test
    fun `remote owner setup requires loopback local key and sessions are revocable`() =
        testApplication {
            configureSecureRoutes()

            val unauthenticated = client.put("/api/v1/security/remote-owner") {
                contentType(ContentType.Application.Json)
                setBody("""{"user":"owner","password":"correct-horse-battery-staple"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)

            val configured = client.put("/api/v1/security/remote-owner") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody("""{"user":"owner","password":"correct-horse-battery-staple"}""")
            }
            assertEquals(HttpStatusCode.OK, configured.status)

            val login = client.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"user":"owner","password":"correct-horse-battery-staple","clientKind":"NATIVE"}""")
            }
            assertEquals(HttpStatusCode.OK, login.status)
            val loginBody = login.bodyAsText()
            assertFalse(loginBody.contains(apiKey))
            val token = json.parseToJsonElement(loginBody).jsonObject["accessToken"]!!.toString().trim('"')
            assertTrue(token.startsWith("pss_"))

            val takeover = client.put("/api/v1/security/remote-owner") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"user":"attacker","password":"another-correct-password"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, takeover.status)

            assertEquals(HttpStatusCode.OK, client.get("/api/private") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/private?token=$token").status)

            assertEquals(HttpStatusCode.NoContent, client.post("/auth/logout") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/private") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        }

    @Test
    fun `retired execution and legacy remote setup routes are not found`() =
        testApplication {
            configureSecureRoutes()
            assertEquals(HttpStatusCode.NotFound, client.post("/execute") { setBody("{}") }.status)
            assertEquals(HttpStatusCode.NotFound, client.post("/api/chat") { setBody("{}") }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/ws/chat").status)
            assertEquals(HttpStatusCode.NotFound, client.post("/api/setup/remote") { setBody("{}") }.status)
        }

    @Test
    fun `browser login uses a strict secure HttpOnly cookie`() =
        testApplication {
            configureSecureRoutes()
            client.put("/api/v1/security/remote-owner") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody("""{"user":"owner","password":"correct-horse-battery-staple"}""")
            }

            val login = client.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"user":"owner","password":"correct-horse-battery-staple","clientKind":"BROWSER"}""")
            }
            val cookie = login.headers[HttpHeaders.SetCookie].orEmpty()
            assertTrue(cookie.contains("HttpOnly"))
            assertTrue(cookie.contains("Secure"))
            assertTrue(cookie.contains("SameSite=Strict"))
            assertFalse(login.bodyAsText().contains("accessToken"))
            assertTrue(login.bodyAsText().contains("csrfToken"))
        }

    @Test
    fun `browser mutations require CSRF and session endpoint restores the token`() =
        testApplication {
            configureSecureRoutes()
            client.put("/api/v1/security/remote-owner") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody("""{"user":"owner","password":"correct-horse-battery-staple"}""")
            }
            val login =
                client.post("/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"user":"owner","password":"correct-horse-battery-staple","clientKind":"BROWSER"}""")
                }
            val sessionCookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            val csrfToken = json.parseToJsonElement(login.bodyAsText()).jsonObject["csrfToken"]!!.toString().trim('"')

            val restored = client.get("/api/v1/auth/session") { header(HttpHeaders.Cookie, sessionCookie) }
            assertEquals(HttpStatusCode.OK, restored.status)
            assertTrue(restored.bodyAsText().contains(csrfToken))

            val rejected = client.post("/api/v1/auth/logout-all") { header(HttpHeaders.Cookie, sessionCookie) }
            assertEquals(HttpStatusCode.Forbidden, rejected.status)

            val revoked =
                client.post("/api/v1/auth/logout-all") {
                    header(HttpHeaders.Cookie, sessionCookie)
                    header(AuthMiddleware.CSRF_HEADER, csrfToken)
                }
            assertEquals(HttpStatusCode.NoContent, revoked.status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/auth/session") { header(HttpHeaders.Cookie, sessionCookie) }.status,
            )
        }
}
