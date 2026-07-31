package dev.promethe.gateway.providers.routes

import dev.promethe.gateway.providers.DefaultProviderCatalogSource
import dev.promethe.gateway.providers.ProviderCatalog
import dev.promethe.gateway.providers.ProviderCatalogSource
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Installs the read-only public provider catalog endpoints. */
fun Route.installProviderCatalogRoutes(source: ProviderCatalogSource = DefaultProviderCatalogSource.instance) {
    get("/api/v1/providers") {
        call.respond(source.catalog().providers())
    }

    get("/api/v1/providers/{id}/models") {
        val providerId = call.parameters["id"]
            ?: return@get call.respond(HttpStatusCode.BadRequest)
        val models = source.catalog().modelsFor(providerId)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(models)
    }
}

/** Compatibility overload for callers that intentionally expose a fixed catalog. */
fun Route.installProviderCatalogRoutes(catalog: ProviderCatalog) {
    installProviderCatalogRoutes(object : ProviderCatalogSource {
        override suspend fun catalog(forceReload: Boolean): ProviderCatalog = catalog

        override fun invalidate() = Unit
    })
}
