package dev.promethe.gateway

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

/** Installs the exact browser-origin policy shared with the MCP HTTP endpoint. */
fun Application.installGatewayCorsPolicy(securityConfig: GatewaySecurityConfig) {
    if (securityConfig.allowedOrigins.isEmpty()) return

    install(CORS) {
        securityConfig.allowedOrigins.forEach { origin ->
            allowHost(origin.hostWithPort, schemes = listOf(origin.scheme))
        }
        allowCredentials = true
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.CacheControl)
    }
}
