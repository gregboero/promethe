package dev.promethe.gateway

import ai.koog.a2a.transport.RequestHandler
import ai.koog.a2a.transport.server.jsonrpc.http.HttpJSONRPCServerTransport
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class A2ARoutePluginCompatibilityTest {
    @Test
    fun `A2A serializer can coexist with gateway routing serializer`() =
        testApplication {
            application {
                install(SSE)
                routing {
                    installGatewayContentNegotiation()
                    route("/agents") {
                        HttpJSONRPCServerTransport(noopRequestHandler()).transportRoutes(this, "/a2a")
                    }
                    get("/probe") {
                        call.respondText("ok")
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.get("/probe").status)
        }

    private fun noopRequestHandler(): RequestHandler =
        Proxy.newProxyInstance(
            RequestHandler::class.java.classLoader,
            arrayOf(RequestHandler::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "NoopRequestHandler"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as RequestHandler
}
