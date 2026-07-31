package dev.promethe.gateway.auth

import dev.promethe.gateway.AuthMiddleware
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** OAuth routes with an authenticated start and a narrowly public callback. */
fun Route.oauthRoutes(oauthManager: OAuthManager) {
    route("/auth/oauth") {
        get("/{provider}/authorize") {
            if (call.attributes.getOrNull(AuthMiddleware.CredentialKindKey) != AuthMiddleware.CredentialKind.OWNER_SESSION) {
                return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "An owner session is required to start OAuth"))
            }
            val provider = call.parameters["provider"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing provider"))
            val ownerId = call.attributes.getOrNull(AuthMiddleware.OwnerIdKey) ?: return@get call.respond(HttpStatusCode.Forbidden)
            try {
                call.respond(mapOf("authorization_url" to oauthManager.beginAuthorization(provider, ownerId, call.request.local.remoteHost)))
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "OAuth authorization failed")))
            }
        }

        // This is intentionally the only public OAuth endpoint used by new
        // installations. The provider is obtained from the signed state so a
        // single fixed redirect URI can serve GitHub and Google.
        get("/callback") {
            val code = call.request.queryParameters["code"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing code"))
            val state = call.request.queryParameters["state"] ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing state"))
            if (call.request.queryParameters["redirect_uri"] != null) {
                return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "redirect_uri is not accepted by the callback"))
            }
            try {
                oauthManager.completeAuthorization(code, state, call.request.local.remoteHost)
                call.respond(mapOf("status" to "connected"))
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "OAuth callback rejected")))
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "OAuth token exchange failed"))
            }
        }
    }

    route("/api/v1/oauth") {
        get("/connections") {
            val ownerId = call.attributes.getOrNull(AuthMiddleware.OwnerIdKey) ?: return@get call.respond(HttpStatusCode.Forbidden)
            call.respond(mapOf("connections" to oauthManager.listConnections(ownerId)))
        }

        delete("/{provider}") {
            val ownerId = call.attributes.getOrNull(AuthMiddleware.OwnerIdKey) ?: return@delete call.respond(HttpStatusCode.Forbidden)
            val provider = call.parameters["provider"] ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing provider"))
            try {
                oauthManager.disconnect(provider, ownerId, call.request.local.remoteHost)
                call.respond(mapOf("status" to "disconnected", "provider" to provider))
            } catch (error: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "OAuth disconnect failed")))
            }
        }
    }
}
