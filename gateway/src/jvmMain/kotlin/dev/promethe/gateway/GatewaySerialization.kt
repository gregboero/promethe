package dev.promethe.gateway

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/**
 * Installs JSON serialization on the routing root so protocol routes can
 * provide their own route-scoped serializers without conflicting with Ktor.
 */
internal fun Route.installGatewayContentNegotiation() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }
}
