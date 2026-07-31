package dev.promethe.gateway

import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayCorsPolicyTest {
    @Test
    fun `CORS preflight rejects an origin outside the explicit allow-list`() =
        testApplication {
            val security =
                GatewaySecurityConfig(
                    bindHost = "127.0.0.1",
                    remoteAccessEnabled = true,
                    allowedOrigins =
                        setOf(
                            GatewaySecurityConfig.AllowedOrigin(
                                value = "https://console.example",
                                hostWithPort = "console.example",
                                scheme = "https",
                            ),
                        ),
                    remoteSessionTtlMinutes = 15,
                )
            application {
                installGatewayCorsPolicy(security)
            }
            routing {
                get("/private") { call.respondText("ok") }
            }

            val response =
                client.options("/private") {
                    header(HttpHeaders.Origin, "https://untrusted.example")
                    header(HttpHeaders.AccessControlRequestMethod, "GET")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
}
