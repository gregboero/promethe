package dev.promethe.gateway

import dev.promethe.core.AgentConfig
import dev.promethe.core.ToolApprovalGate
import dev.promethe.gateway.auth.OwnerAuthService
import dev.promethe.db.DatabaseFactory
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalRoutesTest {
    private val apiKey = "pk-prom-approval-local-key"
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `pending provider choices endpoint is loadable when the queue is empty`() =
        testApplication {
            configureRoutes()

            val response =
                client.get("/approval/providers/pending") {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(0, json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("pending").jsonArray.size)
        }

    @Test
    fun `persistent approval and revocation require local owner authentication`() =
        testApplication {
            coroutineScope {
                val gate = configureRoutes()
                val ownerToken = createOwnerAndLogin()
                val policyArgs = """{"action":"listen_channel","targetId":"123"}"""

                val remoteAttempt = async { gate.checkMandatory("discord_policy", policyArgs, "session-a") }
                val remoteRequestId = awaitPending(gate)
                val forbidden =
                    client.post("/approval/$remoteRequestId") {
                        header(HttpHeaders.Authorization, "Bearer $ownerToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"approved":true,"scope":"PERSISTENT"}""")
                    }
                assertEquals(HttpStatusCode.Forbidden, forbidden.status)
                assertEquals(1, gate.listPending().size)
                gate.respond(remoteRequestId, approved = false)
                assertFalse(remoteAttempt.await().allowed)

                val localAttempt = async { gate.checkMandatory("discord_policy", policyArgs, "session-b") }
                val localRequestId = awaitPending(gate)
                val approved =
                    client.post("/approval/$localRequestId") {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                        contentType(ContentType.Application.Json)
                        setBody("""{"approved":true,"scope":"PERSISTENT","expiresInMs":1000,"localOwner":false}""")
                    }
                assertEquals(HttpStatusCode.OK, approved.status)
                assertTrue(localAttempt.await().allowed)

                val grant = gate.listGrants().single()
                val revoked =
                    client.delete("/approval/grants/${grant.id}") {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                    }
                assertEquals(HttpStatusCode.OK, revoked.status)
                assertTrue(gate.listGrants().isEmpty())
            }
        }

    @Test
    fun `route validates scopes and never exposes raw sensitive arguments`() =
        testApplication {
            coroutineScope {
                val gate = configureRoutes()
                val pending = async { gate.checkMandatory("shell", """{"token":"top-secret","executable":"git"}""", "session-a") }
                val requestId = awaitPending(gate)

                val listing =
                    client.post("/approval/$requestId") {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                        contentType(ContentType.Application.Json)
                        setBody("""{"approved":true,"scope":"FOREVER"}""")
                    }
                assertEquals(HttpStatusCode.BadRequest, listing.status)
                assertEquals(1, gate.listPending().size)
                assertFalse(gate.listPending().single().args.contains("top-secret"))

                gate.respond(requestId, approved = false)
                assertFalse(pending.await().allowed)
            }
        }

    @Test
    fun `remote owner cannot approve local coding agent actions`() =
        testApplication {
            coroutineScope {
                val gate = configureRoutes()
                val ownerToken = createOwnerAndLogin()
                val pending = async { gate.checkMandatory("codex_delegate", "{}", "remote-session") }
                val requestId = awaitPending(gate)

                val response =
                    client.post("/approval/$requestId") {
                        header(HttpHeaders.Authorization, "Bearer $ownerToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"approved":true,"scope":"ONCE"}""")
                    }

                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertEquals(1, gate.listPending().size)
                gate.respond(requestId, approved = false)
                assertFalse(pending.await().allowed)
            }
        }

    @Test
    fun `persistent configuration choice is advertised only to the local owner`() =
        testApplication {
            coroutineScope {
                val gate = configureRoutes()
                val ownerToken = createOwnerAndLogin()
                val pending =
                    async {
                        gate.checkMandatory(
                            "discord_policy",
                            """{"action":"listen_channel","targetId":"123"}""",
                            "session-a",
                        )
                    }
                val requestId = awaitPending(gate)

                val localListing =
                    client.get("/approval/pending") {
                        header(HttpHeaders.Authorization, "Bearer $apiKey")
                    }
                val localItem =
                    json.parseToJsonElement(localListing.bodyAsText()).jsonObject
                        .getValue("pending").jsonArray.single().jsonObject
                assertTrue(localItem.getValue("persistentAllowed").jsonPrimitive.content.toBoolean())

                val remoteListing =
                    client.get("/approval/pending") {
                        header(HttpHeaders.Authorization, "Bearer $ownerToken")
                    }
                val remoteItem =
                    json.parseToJsonElement(remoteListing.bodyAsText()).jsonObject
                        .getValue("pending").jsonArray.single().jsonObject
                assertFalse(remoteItem.getValue("persistentAllowed").jsonPrimitive.content.toBoolean())

                gate.respond(requestId, approved = false)
                assertFalse(pending.await().allowed)
            }
        }

    private fun ApplicationTestBuilder.configureRoutes(): ToolApprovalGate {
        val gate =
            ToolApprovalGate(
                AgentConfig(
                    approvalMode = "all",
                    approvalTimeoutMs = 10_000,
                ),
            )
        val auth = OwnerAuthService(DatabaseFactory.createInMemory(), sessionTtlMinutes = 15)
        val security =
            GatewaySecurityConfig(
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
                approvalRoutes(gate)
            }
        }
        return gate
    }

    private suspend fun ApplicationTestBuilder.createOwnerAndLogin(): String {
        assertEquals(
            HttpStatusCode.OK,
            client.put("/api/v1/security/remote-owner") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody("""{"user":"owner","password":"correct-horse-battery-staple"}""")
            }.status,
        )
        val login =
            client.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"user":"owner","password":"correct-horse-battery-staple","clientKind":"NATIVE"}""")
            }
        assertEquals(HttpStatusCode.OK, login.status)
        return json.parseToJsonElement(login.bodyAsText()).jsonObject["accessToken"].toString().trim('"')
    }

    private suspend fun awaitPending(gate: ToolApprovalGate): String =
        withTimeout(2_000) {
            while (gate.listPending().isEmpty()) {
                delay(10)
            }
            gate.listPending().single().id
        }
}
