package dev.promethe.gateway

import dev.promethe.api.*
import dev.promethe.core.ApprovalGate
import dev.promethe.core.ToolApprovalGate
import dev.promethe.gateway.auth.OwnerAuthService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
private data class ApprovalActionResponse(
    val success: Boolean,
    val action: String? = null,
    val scope: String? = null,
    val error: String? = null,
)

@Serializable
private data class ApprovalPendingItem(
    val id: String,
    val toolName: String,
    val args: String,
    val argsDigest: String,
    val fingerprint: String,
    val sessionId: String,
    val createdAt: Long,
)

@Serializable
private data class ApprovalPendingResponse(
    val pending: List<ApprovalPendingItem>,
)

@Serializable
private data class ApprovalGrantItem(
    val id: String,
    val fingerprint: String,
    val sessionId: String?,
    val scope: String,
    val allowed: Boolean,
    val createdAt: Long,
    val expiresAt: Long,
)

@Serializable
private data class ApprovalGrantListResponse(
    val grants: List<ApprovalGrantItem>,
)

/** Tool approval gate routes (pending list and approve/reject). */
fun Route.approvalRoutes(approvalGate: ToolApprovalGate?) {
    get("/approval/pending") {
        val gate = approvalGate
        if (gate == null) {
            call.respond(mapOf("error" to "Approval gate not enabled (mode=auto)"))
            return@get
        }
        val pending =
            gate.listPending().map { req ->
                ApprovalPendingItem(
                    id = req.id,
                    toolName = req.toolName,
                    args = req.args,
                    argsDigest = req.argsDigest,
                    fingerprint = req.fingerprint,
                    sessionId = req.sessionId,
                    createdAt = req.createdAt,
                )
            }
        call.respond(ApprovalPendingResponse(pending))
    }

    post("/approval/{id}") {
        val gate = approvalGate
        if (gate == null) {
            call.respond(mapOf("error" to "Approval gate not enabled"))
            return@post
        }
        val requestId = call.parameters["id"] ?: ""
        val body = call.receive<JsonObject>()
        val approved = body["approved"]?.jsonPrimitive?.booleanOrNull ?: false
        val requestedScope = body["scope"]?.jsonPrimitive?.contentOrNull
        val scope =
            requestedScope
                ?.let { runCatching { ApprovalGate.ApprovalScope.valueOf(it.uppercase()) }.getOrNull() }
                ?: ApprovalGate.ApprovalScope.ONCE
        if (requestedScope != null && !requestedScope.equals(scope.name, ignoreCase = true)) {
            call.respond(HttpStatusCode.BadRequest, ApprovalActionResponse(false, error = "Unknown approval scope"))
            return@post
        }
        val expiresInMs = body["expiresInMs"]?.jsonPrimitive?.longOrNull
        val localOwner = AuthMiddleware.isLocalApiKeyAuthentication(call)
        val localOnlyApproval =
            gate.listPending()
                .firstOrNull { request -> request.id == requestId }
                ?.toolName
                ?.let { toolName ->
                    toolName == "codex_delegate" ||
                        toolName == "claude_code_delegate" ||
                        toolName.startsWith("codex_local_action") ||
                        toolName.startsWith("claude_code_action:")
                } == true
        if (approved && localOnlyApproval && !localOwner) {
            call.respond(
                HttpStatusCode.Forbidden,
                ApprovalActionResponse(false, error = "Local coding-agent approval requires the local desktop owner"),
            )
            return@post
        }
        when (gate.respond(requestId, approved, scope, expiresInMs, localOwner)) {
            ToolApprovalGate.ResponseResult.ACCEPTED -> {
                call.respond(ApprovalActionResponse(true, if (approved) "approved" else "rejected", scope.name))
            }

            ToolApprovalGate.ResponseResult.NOT_FOUND -> {
                call.respond(HttpStatusCode.NotFound, ApprovalActionResponse(false, error = "Approval request not found"))
            }

            ToolApprovalGate.ResponseResult.PERSISTENT_REQUIRES_LOCAL_OWNER -> {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ApprovalActionResponse(false, error = "Persistent approval requires the local loopback owner key"),
                )
            }

            ToolApprovalGate.ResponseResult.INVALID_EXPIRATION -> {
                call.respond(HttpStatusCode.BadRequest, ApprovalActionResponse(false, error = "Invalid approval expiration"))
            }
        }
    }

    get("/approval/grants") {
        val gate = approvalGate
        if (gate == null) {
            call.respond(ApprovalGrantListResponse(emptyList()))
            return@get
        }
        val grants: List<ApprovalGrantItem> =
            gate.listGrants().map { grant ->
                ApprovalGrantItem(
                    id = grant.id,
                    fingerprint = grant.fingerprint,
                    sessionId = grant.sessionId,
                    scope = grant.scope.name,
                    allowed = grant.allowed,
                    createdAt = grant.createdAt,
                    expiresAt = grant.expiresAt,
                )
            }
        call.respond(ApprovalGrantListResponse(grants))
    }

    delete("/approval/grants/{id}") {
        val gate = approvalGate
        if (gate == null) {
            call.respond(HttpStatusCode.NotFound, ApprovalActionResponse(false, error = "Approval gate not enabled"))
            return@delete
        }
        val grantId = call.parameters["id"].orEmpty()
        val localOwner = AuthMiddleware.isLocalApiKeyAuthentication(call)
        when (gate.revoke(grantId, localOwner)) {
            ToolApprovalGate.RevocationResult.REVOKED -> {
                call.respond(ApprovalActionResponse(true, action = "revoked"))
            }

            ToolApprovalGate.RevocationResult.NOT_FOUND -> {
                call.respond(HttpStatusCode.NotFound, ApprovalActionResponse(false, error = "Approval grant not found"))
            }

            ToolApprovalGate.RevocationResult.PERSISTENT_REQUIRES_LOCAL_OWNER -> {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ApprovalActionResponse(false, error = "Persistent approval revocation requires the local loopback owner key"),
                )
            }
        }
    }

    get("/approval/providers/pending") {
        val gate = approvalGate
        if (gate == null) {
            call.respond(mapOf("pending" to emptyList<Any>()))
            return@get
        }
        val pending = gate.listPendingProviders().map { req ->
            mapOf(
                "id" to req.id,
                "capability" to req.capability,
                "capabilityLabel" to req.capabilityLabel,
                "suggestedProviderId" to req.suggestedProviderId,
                "suggestedProviderName" to req.suggestedProviderName,
                "alternatives" to req.alternatives.map { alt -> mapOf("id" to alt.id, "name" to alt.name) },
                "sessionId" to req.sessionId,
                "createdAt" to req.createdAt.toString(),
            )
        }
        call.respond(mapOf("pending" to pending))
    }

    post("/approval/providers/{id}") {
        val gate = approvalGate
        if (gate == null) {
            call.respond(mapOf("error" to "Approval gate not enabled"))
            return@post
        }
        val requestId = call.parameters["id"] ?: ""
        val body = call.receive<JsonObject>()
        val approved = body["approved"]?.jsonPrimitive?.booleanOrNull ?: false
        val selectedProviderId = body["selectedProviderId"]?.jsonPrimitive?.content
        val success = gate.respondProvider(requestId, approved, selectedProviderId)
        call.respond(
            mapOf(
                "success" to success,
                "action" to if (approved) "provider-selected" else "provider-rejected",
                "selectedProviderId" to (selectedProviderId ?: "none"),
            ),
        )
    }
}

/** Owner login, logout, and loopback-only owner setup. */
fun Route.authRoutes(
    ownerAuthService: OwnerAuthService,
    securityConfig: GatewaySecurityConfig,
) {
    post("/auth/login") {
        if (!securityConfig.remoteAccessEnabled) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Remote access is disabled"))
            return@post
        }

        val request = call.receive<LoginRequest>()
        when (val result = ownerAuthService.login(request.user, request.password, call.request.local.remoteHost)) {
            OwnerAuthService.LoginResult.InvalidCredentials -> {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid credentials"))
            }

            OwnerAuthService.LoginResult.Throttled -> {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("Too many login attempts"))
            }

            is OwnerAuthService.LoginResult.Success -> {
                if (request.clientKind == LoginClientKind.BROWSER) {
                    val maxAge = ((result.expiresAt - System.currentTimeMillis()) / 1_000).coerceAtLeast(1).toInt()
                    call.response.cookies.append(
                        Cookie(
                            name = AuthMiddleware.SESSION_COOKIE,
                            value = result.token,
                            path = "/",
                            maxAge = maxAge,
                            secure = true,
                            httpOnly = true,
                            extensions = mapOf("SameSite" to "Strict"),
                        ),
                    )
                    call.respond(LoginResponse(expiresAt = result.expiresAt, csrfToken = result.csrfToken))
                } else {
                    call.respond(LoginResponse(accessToken = result.token, expiresAt = result.expiresAt))
                }
            }
        }
    }

    post("/auth/logout") {
        ownerAuthService.revoke(AuthMiddleware.extractSessionToken(call), call.request.local.remoteHost)
        call.response.cookies.append(
            Cookie(
                name = AuthMiddleware.SESSION_COOKIE,
                value = "",
                path = "/",
                maxAge = 0,
                secure = true,
                httpOnly = true,
                extensions = mapOf("SameSite" to "Strict"),
            ),
        )
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/v1/auth/session") {
        val sessionId = call.attributes.getOrNull(AuthMiddleware.SessionIdKey)
            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("An owner session is required"))
        val ownerId = call.attributes.getOrNull(AuthMiddleware.OwnerIdKey)
            ?: return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("An owner session is required"))
        val authenticated = ownerAuthService.authenticate(AuthMiddleware.extractSessionToken(call))
            ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Session is no longer valid"))
        call.respond(
            AuthSessionResponse(
                sessionId = sessionId,
                ownerId = ownerId,
                expiresAt = authenticated.expiresAt,
                csrfToken = ownerAuthService.csrfToken(sessionId),
            ),
        )
    }

    post("/api/v1/auth/logout-all") {
        val ownerId = call.attributes.getOrNull(AuthMiddleware.OwnerIdKey)
            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("An owner session is required"))
        ownerAuthService.revokeAll(ownerId, call.request.local.remoteHost)
        call.response.cookies.append(
            Cookie(
                name = AuthMiddleware.SESSION_COOKIE,
                value = "",
                path = "/",
                maxAge = 0,
                secure = true,
                httpOnly = true,
                extensions = mapOf("SameSite" to "Strict"),
            ),
        )
        call.respond(HttpStatusCode.NoContent)
    }

    put("/api/v1/security/remote-owner") {
        if (!securityConfig.remoteAccessEnabled) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Enable REMOTE_ACCESS_ENABLED before creating an owner"))
            return@put
        }
        if (!AuthMiddleware.isLocalApiKeyAuthentication(call)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Remote owner setup requires the local loopback API key"))
            return@put
        }

        val request = call.receive<SetupRemoteOwnerRequest>()
        val wasReplaced = try {
            ownerAuthService.configureOwner(request.user, request.password, call.request.local.remoteHost)
        } catch (error: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid owner configuration"))
            return@put
        }
        call.respond(SetupRemoteOwnerResponse(user = request.user, sessionsRevoked = wasReplaced))
    }
}

/** Public liveness plus authenticated diagnostics. There is intentionally no /execute route. */
fun Route.systemRoutes(startTime: Long) {
    get("/health") {
        call.respond(mapOf("status" to "ok"))
    }

    get("/api/v1/system/diagnostics") {
        val runtime = Runtime.getRuntime()
        call.respond(
            EnhancedHealthResponse(
                uptime = (System.currentTimeMillis() - startTime).toString(),
                memory =
                    JvmMemoryInfo(
                        used = "${(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576}MB",
                        max = "${runtime.maxMemory() / 1_048_576}MB",
                    ),
            ),
        )
    }
}
