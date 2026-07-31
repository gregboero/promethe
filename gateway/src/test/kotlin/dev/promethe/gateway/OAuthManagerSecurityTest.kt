package dev.promethe.gateway

import dev.promethe.core.OAuthTokenRegistry
import dev.promethe.core.security.SecretCipher
import dev.promethe.db.DatabaseFactory
import dev.promethe.db.RemoteOwnerRow
import dev.promethe.gateway.auth.OAuthManager
import dev.promethe.gateway.auth.OAuthProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class OAuthManagerSecurityTest {
    @Test
    fun `OAuth state is one-time and provider tokens are encrypted`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            database.upsertRemoteOwner(RemoteOwnerRow("owner", "owner", "argon2id\$test", 1, 1))
            var tokenCalls = 0
            val client =
                HttpClient(
                    MockEngine {
                        tokenCalls += 1
                        respond(
                            content =
                                if (tokenCalls == 1) {
                                    """{"access_token":"access-secret","refresh_token":"refresh-secret","expires_in":1}"""
                                } else {
                                    """{"access_token":"refreshed-secret","refresh_token":"refresh-secret","expires_in":3600}"""
                                },
                            status = HttpStatusCode.OK,
                        )
                    },
                )
            val manager =
                OAuthManager(
                    database = database,
                    httpClient = client,
                    providers =
                        mapOf(
                            "github" to
                                OAuthProviderConfig(
                                    clientId = "client-id",
                                    clientSecret = "client-secret",
                                    authorizationEndpoint = "https://provider.example/authorize",
                                    tokenEndpoint = "https://provider.example/token",
                                    scopes = listOf("repo"),
                                ),
                        ),
                    redirectUri = "https://gateway.example/auth/oauth/callback",
                    cipher = SecretCipher.fromBase64Key(Base64.getEncoder().encodeToString(ByteArray(32) { 9 })),
                )
            try {
                val authorizationUrl = manager.beginAuthorization("github", "owner", "127.0.0.1")
                val url = Url(authorizationUrl)
                val state = url.parameters["state"]!!
                assertEquals("https://gateway.example/auth/oauth/callback", url.parameters["redirect_uri"])
                assertFalse(state.contains("client-secret"))

                manager.completeAuthorization("provider-code", state, "127.0.0.1")
                val stored = database.getOAuthConnection("owner", "github")!!
                assertFalse(stored.encryptedTokens.contains("access-secret"))
                assertEquals("refreshed-secret", manager.getValidToken("github", "owner"))
                assertEquals(2, tokenCalls)
                assertFalse(database.getOAuthConnection("owner", "github")!!.encryptedTokens.contains("refreshed-secret"))
                assertFailsWith<IllegalArgumentException> {
                    manager.completeAuthorization("github", "provider-code", state, "127.0.0.1")
                }
            } finally {
                OAuthTokenRegistry.clear(manager)
                client.close()
            }
        }

    @Test
    fun `GitHub JSON token without expiry remains valid`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            database.upsertRemoteOwner(RemoteOwnerRow("owner", "owner", "argon2id\$test", 1, 1))
            var tokenCalls = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        tokenCalls += 1
                        assertEquals("application/json", request.headers[HttpHeaders.Accept])
                        respond("""{"access_token":"github-access","scope":"repo","token_type":"bearer"}""", HttpStatusCode.OK)
                    },
                )
            val manager =
                OAuthManager(
                    database = database,
                    httpClient = client,
                    providers =
                        mapOf(
                            "github" to
                                OAuthProviderConfig(
                                    clientId = "client-id",
                                    clientSecret = "client-secret",
                                    authorizationEndpoint = "https://github.example/authorize",
                                    tokenEndpoint = "https://github.example/token",
                                    scopes = listOf("repo"),
                                    defaultTokenTtlSeconds = null,
                                ),
                        ),
                    redirectUri = "https://gateway.example/auth/oauth/callback",
                    cipher = SecretCipher.fromBase64Key(Base64.getEncoder().encodeToString(ByteArray(32) { 7 })),
                )
            try {
                val state = Url(manager.beginAuthorization("github", "owner", "127.0.0.1")).parameters["state"]!!
                manager.completeAuthorization("code", state, "127.0.0.1")

                assertEquals("github-access", manager.getValidToken("github", "owner"))
                assertEquals(1, tokenCalls)
            } finally {
                OAuthTokenRegistry.clear(manager)
                client.close()
            }
        }
}
