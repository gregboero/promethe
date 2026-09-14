package dev.promethe.gateway

import dev.promethe.api.ErrorResponse
import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.core.CredentialsStore
import dev.promethe.core.PrometheJson
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
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString

fun Route.sandboxSecurityRoutes(
    sandboxManager: SandboxManager,
    runtimePolicy: SandboxRuntimePolicy,
    remoteAccessEnabled: Boolean = false,
    persistProfile: (SandboxPermissionProfile) -> Unit = ::persistSandboxProfile,
) {
    get("security/sandbox/status") {
        val status = sandboxManager.status()
        call.respond(
            status.copy(
                mode = runtimePolicy.get().mode,
                networkMode = runtimePolicy.get().networkMode,
                setupAvailable =
                    AuthMiddleware.isLocalApiKeyAuthentication(call) &&
                        status.backend == dev.promethe.api.SandboxBackend.WINDOWS_ELEVATED &&
                        !status.available,
                localConfigurationAllowed = AuthMiddleware.isLocalApiKeyAuthentication(call),
                workspaceRoot = runtimePolicy.workspaceRoot(),
            ),
        )
    }

    post("security/sandbox/self-test") {
        call.respond(sandboxManager.selfTest())
    }

    post("security/sandbox/setup") {
        if (!AuthMiddleware.isLocalApiKeyAuthentication(call)) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("Sandbox setup requires the local loopback API key"),
            )
            return@post
        }
        try {
            call.respond(sandboxManager.setup(runtimePolicy.workspaceRoot()))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(error.message?.take(2_000) ?: "Sandbox setup failed"),
            )
        }
    }

    get("security/permission-profile") {
        call.respond(runtimePolicy.get())
    }

    put("security/permission-profile") {
        if (!AuthMiddleware.isLocalApiKeyAuthentication(call)) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("Sandbox permissions can only be changed locally with the loopback API key"),
            )
            return@put
        }
        val requested = call.receive<SandboxPermissionProfile>()
        if (requested.mode == SandboxMode.FULL_ACCESS && remoteAccessEnabled) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("Full local file access is unavailable while remote access is enabled"),
            )
            return@put
        }
        val previous = runtimePolicy.get()
        try {
            val updated = runtimePolicy.update(requested, localOwner = true)
            try {
                persistProfile(updated)
            } catch (error: Exception) {
                runtimePolicy.update(previous, localOwner = true)
                throw SandboxProfilePersistenceException(error)
            }
            call.respond(updated)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(error.message ?: "Sandbox policy denied"))
        } catch (error: SandboxPolicyException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid sandbox policy"))
        } catch (error: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid sandbox policy"))
        } catch (error: SandboxProfilePersistenceException) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Sandbox profile could not be persisted"))
        }
    }
}

private class SandboxProfilePersistenceException(
    cause: Throwable,
) : RuntimeException(cause)

private fun persistSandboxProfile(profile: SandboxPermissionProfile) {
    CredentialsStore.update { credentials ->
        credentials.copy(
            runtimeConfig =
                credentials.runtimeConfig +
                    mapOf(
                        "SANDBOX_MODE" to profile.mode.name,
                        "SANDBOX_READABLE_ROOTS" to PrometheJson.encodeToString(profile.readableRoots),
                        "SANDBOX_WRITABLE_ROOTS" to PrometheJson.encodeToString(profile.writableRoots),
                    ),
        )
    }
}
