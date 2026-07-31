package dev.promethe.gateway

import dev.promethe.api.ErrorResponse
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.core.sandbox.SandboxManager
import dev.promethe.core.sandbox.SandboxPolicyException
import dev.promethe.core.sandbox.SandboxRuntimePolicy
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

fun Route.sandboxSecurityRoutes(
    sandboxManager: SandboxManager,
    runtimePolicy: SandboxRuntimePolicy,
) {
    get("security/sandbox/status") {
        val status = sandboxManager.status()
        call.respond(
            status.copy(
                mode = runtimePolicy.get().mode,
                networkMode = runtimePolicy.get().networkMode,
            ),
        )
    }

    post("security/sandbox/self-test") {
        call.respond(sandboxManager.selfTest())
    }

    get("security/permission-profile") {
        call.respond(runtimePolicy.get())
    }

    put("security/permission-profile") {
        val requested = call.receive<SandboxPermissionProfile>()
        val localOwner = AuthMiddleware.isLocalApiKeyAuthentication(call)
        try {
            call.respond(runtimePolicy.update(requested, localOwner))
        } catch (error: IllegalStateException) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(error.message ?: "Sandbox policy denied"))
        } catch (error: SandboxPolicyException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid sandbox policy"))
        }
    }
}
