package dev.promethe.gateway

import dev.promethe.api.ErrorResponse
import dev.promethe.gateway.auth.OwnerAuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.AttributeKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetAddress

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Authentication boundary for the gateway.
 *
 * The generated API key is deliberately a loopback-only administrative key.
 * A remote caller must use a short lived owner session, never a URL parameter.
 */
object AuthMiddleware {
    const val SESSION_COOKIE = "promethe_session"

    private val jsonEncoder = Json { encodeDefaults = true }

    enum class CredentialKind {
        LOCAL_API_KEY,
        OWNER_SESSION,
    }

    val CredentialKindKey = AttributeKey<CredentialKind>("gatewayCredentialKind")
    val OwnerIdKey = AttributeKey<String>("gatewayOwnerId")
    val SessionIdKey = AttributeKey<String>("gatewaySessionId")

    fun install(
        app: Application,
        expectedApiKey: String,
        securityConfig: GatewaySecurityConfig,
        ownerAuthService: OwnerAuthService,
    ) {
        app.intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()

            if (call.request.httpMethod == HttpMethod.Options || isPublicRoute(path)) return@intercept
            if (path in RETIRED_ROUTES) {
                call.respond(HttpStatusCode.NotFound)
                finish()
                return@intercept
            }

            val bearer = call.request.parseBearerToken()
            val cookieToken = call.request.cookies[SESSION_COOKIE]
            val token = bearer ?: cookieToken

            if (token.isNullOrBlank()) {
                logger.warn { "Blocked $path: no Authorization bearer token" }
                respondUnauthorized(call, "Missing Authorization header")
                finish()
                return@intercept
            }

            when {
                token.startsWith("pk-prom-") -> {
                    if (!isLoopback(call.request.local.remoteHost)) {
                        logger.warn { "Rejected local API key from non-loopback peer on $path" }
                        respondForbidden(call, "The local API key is accepted only from loopback")
                        finish()
                        return@intercept
                    }
                    if (!constantTimeEquals(token, expectedApiKey)) {
                        respondUnauthorized(call, "Invalid API key")
                        finish()
                        return@intercept
                    }
                    call.attributes.put(CredentialKindKey, CredentialKind.LOCAL_API_KEY)
                }

                token.startsWith("pss_") -> {
                    if (!securityConfig.remoteAccessEnabled) {
                        respondUnauthorized(call, "Remote sessions are disabled")
                        finish()
                        return@intercept
                    }
                    val session = ownerAuthService.authenticate(token)
                    if (session == null) {
                        respondUnauthorized(call, "Invalid, expired, or revoked session")
                        finish()
                        return@intercept
                    }
                    call.attributes.put(CredentialKindKey, CredentialKind.OWNER_SESSION)
                    call.attributes.put(OwnerIdKey, session.ownerId)
                    call.attributes.put(SessionIdKey, session.sessionId)
                    if (bearer == null && cookieToken != null && call.request.httpMethod in MUTATING_METHODS) {
                        val csrfToken = call.request.header(CSRF_HEADER)
                        if (!ownerAuthService.verifyCsrf(session.sessionId, csrfToken)) {
                            respondForbidden(call, "Missing or invalid CSRF token")
                            finish()
                            return@intercept
                        }
                    }
                }

                else -> {
                    respondUnauthorized(call, "Unknown credential type")
                    finish()
                    return@intercept
                }
            }
        }
    }

    fun isLoopbackRequest(call: ApplicationCall): Boolean = isLoopback(call.request.local.remoteHost)

    fun isLocalApiKeyAuthentication(call: ApplicationCall): Boolean = call.attributes.getOrNull(CredentialKindKey) == CredentialKind.LOCAL_API_KEY && isLoopbackRequest(call)

    fun extractSessionToken(call: ApplicationCall): String? =
        call.request.parseBearerToken()?.takeIf { it.startsWith("pss_") }
            ?: call.request.cookies[SESSION_COOKIE]?.takeIf { it.startsWith("pss_") }

    private fun ApplicationRequest.parseBearerToken(): String? {
        val header = header(HttpHeaders.Authorization) ?: return null
        if (!header.startsWith("Bearer ", ignoreCase = true)) return null
        return header.substringAfter(' ').trim().takeIf { it.isNotBlank() }
    }

    private suspend fun respondUnauthorized(
        call: ApplicationCall,
        message: String,
    ) {
        call.respondText(
            jsonEncoder.encodeToString(ErrorResponse(error = message)),
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized,
        )
    }

    private suspend fun respondForbidden(
        call: ApplicationCall,
        message: String,
    ) {
        call.respondText(
            jsonEncoder.encodeToString(ErrorResponse(error = message)),
            ContentType.Application.Json,
            HttpStatusCode.Forbidden,
        )
    }

    private fun isPublicRoute(path: String): Boolean =
        path == "/health" ||
            path == "/health/" ||
            path == "/auth/login" ||
            path == "/auth/oauth/callback" ||
            path in PUBLIC_WEBHOOK_ROUTES ||
            path.startsWith("/.well-known/") ||
            isStaticAsset(path)

    private fun isStaticAsset(path: String): Boolean =
        path == "/" ||
            path.endsWith(".html") ||
            path.endsWith(".js") ||
            path.endsWith(".mjs") ||
            path.endsWith(".css") ||
            path.endsWith(".wasm") ||
            path.endsWith(".map") ||
            path.endsWith(".png") ||
            path.endsWith(".ico")

    private val PUBLIC_WEBHOOK_ROUTES =
        setOf(
            "/webhook/telegram",
            "/webhook/whatsapp",
            "/webhook/discord",
            "/webhook/slack",
            "/webhook/signal",
            "/webhook/matrix",
            "/webhook/sms",
        )

    private val RETIRED_ROUTES = setOf("/execute", "/api/chat", "/ws/chat", "/api/setup/remote")
    const val CSRF_HEADER = "X-CSRF-Token"
    private val MUTATING_METHODS = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete)

    private fun isLoopback(host: String): Boolean =
        host.equals("localhost", ignoreCase = true) ||
            runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)

    private fun constantTimeEquals(
        actual: String,
        expected: String,
    ): Boolean =
        actual.isNotBlank() &&
            java.security.MessageDigest.isEqual(actual.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
}
