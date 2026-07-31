package dev.promethe.api

import kotlinx.serialization.Serializable

/** Remote device login request. */
@Serializable
data class LoginRequest(
    val user: String,
    val password: String,
    val clientKind: LoginClientKind = LoginClientKind.NATIVE,
)

/** Client category used to choose a bearer token or an HttpOnly browser session. */
@Serializable
enum class LoginClientKind {
    NATIVE,
    BROWSER,
}

/** Successful remote login. Native clients retain [accessToken] in memory only. */
@Serializable
data class LoginResponse(
    val accessToken: String? = null,
    val expiresAt: Long,
    val csrfToken: String? = null,
)

@Serializable
data class AuthSessionResponse(
    val authenticated: Boolean = true,
    val sessionId: String,
    val ownerId: String,
    val expiresAt: Long,
    val csrfToken: String? = null,
)

/** Request to create or replace the single remote owner from a local authenticated client. */
@Serializable
data class SetupRemoteOwnerRequest(
    val user: String,
    val password: String,
)

/** Response returned after creating or changing the remote owner. */
@Serializable
data class SetupRemoteOwnerResponse(
    val user: String,
    val sessionsRevoked: Boolean,
)
