package dev.promethe.gateway.auth

import dev.promethe.core.Log
import dev.promethe.core.OAuthTokenProvider
import dev.promethe.core.OAuthTokenRegistry
import dev.promethe.core.security.SecretCipher
import dev.promethe.db.OAuthAuthorizationRow
import dev.promethe.db.OAuthConnectionRow
import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.db.SecurityAuditLogRow
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Single-owner OAuth authorization-code manager for GitHub and Google Calendar. */
class OAuthManager(
    private val database: PrometheDatabaseApi,
    private val httpClient: HttpClient,
    private val providers: Map<String, OAuthProviderConfig>,
    private val redirectUri: String,
    private val cipher: SecretCipher,
) : OAuthTokenProvider {
    private val logger = Log.create("OAuthManager")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        validateRedirectUri(redirectUri)
        OAuthTokenRegistry.install(this)
    }

    suspend fun beginAuthorization(
        provider: String,
        ownerId: String,
        remoteAddress: String,
    ): String {
        val config = providerConfig(provider)
        val now = System.currentTimeMillis()
        val expiresAt = now + STATE_TTL_MS
        val verifier = randomUrlToken(48)
        val state = signedState(ownerId, provider, expiresAt)

        database.insertOAuthAuthorization(
            OAuthAuthorizationRow(
                stateHash = sha256(state),
                ownerId = ownerId,
                provider = provider,
                redirectUri = redirectUri,
                encryptedVerifier = cipher.encrypt(verifier),
                expiresAt = expiresAt,
            ),
        )
        audit("oauth_authorization_started", ownerId, remoteAddress, provider)

        return URLBuilder(config.authorizationEndpoint).apply {
            parameters.append("client_id", config.clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("response_type", "code")
            parameters.append("scope", config.scopes.joinToString(" "))
            parameters.append("state", state)
            config.accessType?.let { parameters.append("access_type", it) }
            config.prompt?.let { parameters.append("prompt", it) }
            if (config.supportsPkce) {
                parameters.append("code_challenge", codeChallenge(verifier))
                parameters.append("code_challenge_method", "S256")
            }
        }.buildString()
    }

    suspend fun completeAuthorization(
        code: String,
        state: String,
        remoteAddress: String,
    ): String {
        // The callback path is intentionally provider-neutral so one fixed
        // OAUTH_REDIRECT_URI can be registered for both supported providers.
        // completeAuthorization(provider, ...) verifies the signed state before
        // it consumes anything or exchanges a token.
        val provider = state.split('.').getOrNull(2) ?: throw IllegalArgumentException("Malformed OAuth state")
        return completeAuthorization(provider, code, state, remoteAddress)
    }

    suspend fun completeAuthorization(
        provider: String,
        code: String,
        state: String,
        remoteAddress: String,
    ): String {
        val stateData = verifyState(state, provider)
        val authorization = database.consumeOAuthAuthorization(sha256(state), System.currentTimeMillis())
            ?: throw IllegalArgumentException("OAuth state is invalid, expired, or already used")
        require(authorization.ownerId == stateData.ownerId && authorization.provider == provider) { "OAuth state does not match the authorization request" }
        require(authorization.redirectUri == redirectUri) { "OAuth redirect URI mismatch" }

        val config = providerConfig(provider)
        val verifier = cipher.decrypt(authorization.encryptedVerifier)
        val response =
            httpClient.submitForm(
                url = config.tokenEndpoint,
                formParameters =
                    parameters {
                        append("grant_type", "authorization_code")
                        append("code", code)
                        append("client_id", config.clientId)
                        append("client_secret", config.clientSecret)
                        append("redirect_uri", redirectUri)
                        if (config.supportsPkce) append("code_verifier", verifier)
                    },
            ) {
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
        if (!response.status.isSuccess()) throw IllegalStateException("Token endpoint returned ${response.status.value}")

        val tokenResponse = json.decodeFromString<OAuthTokenResponse>(response.bodyAsText())
        val now = System.currentTimeMillis()
        val tokens = OAuthTokens(
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken,
            expiresAt = resolveExpiry(config, tokenResponse.expiresIn, now),
            provider = provider,
        )
        saveTokens(authorization.ownerId, tokens)
        audit("oauth_connected", authorization.ownerId, remoteAddress, provider)
        return authorization.ownerId
    }

    override suspend fun getValidToken(
        provider: String,
        ownerId: String,
    ): String? {
        val connection = database.getOAuthConnection(ownerId, provider) ?: return null
        val tokens = runCatching { json.decodeFromString<OAuthTokens>(cipher.decrypt(connection.encryptedTokens)) }.getOrElse {
            logger.warn(it) { "Unable to decrypt OAuth token for provider=$provider" }
            return null
        }
        if (tokens.expiresAt > System.currentTimeMillis() + REFRESH_SKEW_MS) return tokens.accessToken
        val refreshToken = tokens.refreshToken ?: return null
        return refreshTokens(provider, ownerId, refreshToken)
    }

    suspend fun disconnect(
        provider: String,
        ownerId: String,
        remoteAddress: String,
    ) {
        providerConfig(provider)
        database.deleteOAuthConnection(ownerId, provider)
        audit("oauth_disconnected", ownerId, remoteAddress, provider)
    }

    suspend fun listConnections(ownerId: String): List<String> = database.getOAuthConnections(ownerId).map { it.provider }.sorted()

    private suspend fun refreshTokens(
        provider: String,
        ownerId: String,
        refreshToken: String,
    ): String? {
        val config = providers[provider] ?: return null
        return try {
            val response =
                httpClient.submitForm(
                    url = config.tokenEndpoint,
                    formParameters =
                        parameters {
                            append("grant_type", "refresh_token")
                            append("refresh_token", refreshToken)
                            append("client_id", config.clientId)
                            append("client_secret", config.clientSecret)
                        },
                ) {
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                }
            if (!response.status.isSuccess()) throw IllegalStateException("Token refresh returned ${response.status.value}")
            val refreshed = json.decodeFromString<OAuthTokenResponse>(response.bodyAsText())
            val now = System.currentTimeMillis()
            val updated = OAuthTokens(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken ?: refreshToken,
                expiresAt = resolveExpiry(config, refreshed.expiresIn, now),
                provider = provider,
            )
            saveTokens(ownerId, updated)
            audit("oauth_refreshed", ownerId, "", provider)
            updated.accessToken
        } catch (error: Exception) {
            logger.warn(error) { "OAuth refresh failed for provider=$provider" }
            null
        }
    }

    private suspend fun saveTokens(
        ownerId: String,
        tokens: OAuthTokens,
    ) {
        database.upsertOAuthConnection(
            OAuthConnectionRow(
                ownerId = ownerId,
                provider = tokens.provider,
                encryptedTokens = cipher.encrypt(json.encodeToString(OAuthTokens.serializer(), tokens)),
                expiresAt = tokens.expiresAt,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun signedState(
        ownerId: String,
        provider: String,
        expiresAt: Long,
    ): String {
        val payload = listOf(STATE_VERSION, ownerId, provider, expiresAt.toString(), randomUrlToken(18)).joinToString(".")
        return "$payload.${cipher.sign(payload)}"
    }

    private fun verifyState(
        state: String,
        expectedProvider: String,
    ): StateData {
        val parts = state.split('.')
        require(parts.size == 6 && parts[0] == STATE_VERSION) { "Malformed OAuth state" }
        val payload = parts.take(5).joinToString(".")
        require(cipher.verifySignature(payload, parts.last())) { "Invalid OAuth state signature" }
        val expiresAt = parts[3].toLongOrNull() ?: throw IllegalArgumentException("Malformed OAuth state expiry")
        require(expiresAt > System.currentTimeMillis()) { "OAuth state expired" }
        require(parts[2] == expectedProvider) { "OAuth provider mismatch" }
        return StateData(parts[1], parts[2], expiresAt)
    }

    private fun providerConfig(provider: String): OAuthProviderConfig = providers[provider] ?: throw IllegalArgumentException("OAuth provider '$provider' is not enabled")

    private fun resolveExpiry(
        config: OAuthProviderConfig,
        expiresInSeconds: Long?,
        now: Long,
    ): Long =
        (expiresInSeconds ?: config.defaultTokenTtlSeconds)
            ?.let { ttl -> now + ttl * 1_000 }
            ?: Long.MAX_VALUE

    private suspend fun audit(
        event: String,
        ownerId: String,
        remoteAddress: String,
        provider: String,
    ) {
        database.insertSecurityAuditLog(
            SecurityAuditLogRow(
                eventType = event,
                actor = ownerId,
                remoteAddress = remoteAddress.take(255),
                detail = "provider=$provider",
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun randomUrlToken(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

    private fun codeChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun validateRedirectUri(value: String) {
        val uri = URI(value)
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.query == null && uri.fragment == null) {
            "OAUTH_REDIRECT_URI must be an absolute http(s) URL without query or fragment"
        }
    }

    private data class StateData(
        val ownerId: String,
        val provider: String,
        val expiresAt: Long,
    )

    private companion object {
        const val STATE_VERSION = "v1"
        const val STATE_TTL_MS = 10 * 60_000L
        const val REFRESH_SKEW_MS = 60_000L
    }
}

data class OAuthProviderConfig(
    val clientId: String,
    val clientSecret: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val scopes: List<String>,
    val accessType: String? = null,
    val prompt: String? = null,
    val supportsPkce: Boolean = true,
    val defaultTokenTtlSeconds: Long? = 3_600L,
)

object OAuthProviderConfigs {
    private data class KnownProvider(
        val name: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val scopes: List<String>,
        val accessType: String? = null,
        val prompt: String? = null,
        val defaultTokenTtlSeconds: Long? = 3_600L,
    )

    private val known =
        listOf(
            KnownProvider(
                "github",
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                listOf("repo", "read:user"),
                defaultTokenTtlSeconds = null,
            ),
            KnownProvider(
                "google",
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                listOf("https://www.googleapis.com/auth/calendar"),
                accessType = "offline",
                prompt = "consent",
            ),
        )

    fun fromConfig(config: dev.promethe.core.config.ConfigProvider = dev.promethe.core.config.ConfigProvider.get()): Map<String, OAuthProviderConfig> =
        known.mapNotNull { provider ->
            val prefix = "OAUTH_${provider.name.uppercase()}"
            val clientId = config.get("${prefix}_CLIENT_ID", "")
            val clientSecret = config.get("${prefix}_CLIENT_SECRET", "")
            if (clientId.isBlank() || clientSecret.isBlank()) {
                null
            } else {
                provider.name to
                    OAuthProviderConfig(
                        clientId = clientId,
                        clientSecret = clientSecret,
                        authorizationEndpoint = provider.authorizationEndpoint,
                        tokenEndpoint = provider.tokenEndpoint,
                        scopes = provider.scopes,
                        accessType = provider.accessType,
                        prompt = provider.prompt,
                        defaultTokenTtlSeconds = provider.defaultTokenTtlSeconds,
                    )
            }
        }.toMap()
}

@Serializable
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long,
    val provider: String,
)

@Serializable
private data class OAuthTokenResponse(
    val access_token: String,
    val refresh_token: String? = null,
    val expires_in: Long? = null,
) {
    val accessToken get() = access_token
    val refreshToken get() = refresh_token
    val expiresIn get() = expires_in
}
