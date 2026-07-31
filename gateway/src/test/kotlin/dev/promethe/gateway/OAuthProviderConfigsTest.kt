package dev.promethe.gateway

import dev.promethe.core.config.ConfigProvider
import dev.promethe.gateway.auth.OAuthProviderConfigs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OAuth provider map construction from config — the OAUTH_ENABLED gate in
 * GatewayModule only builds an OAuthManager when this map is non-empty.
 */
class OAuthProviderConfigsTest {
    private fun configOf(map: Map<String, String>): ConfigProvider =
        object : ConfigProvider {
            override fun get(key: String): String? = map[key]

            override fun get(
                key: String,
                default: String,
            ): String = map[key] ?: default

            override fun getBoolean(
                key: String,
                default: Boolean,
            ): Boolean = map[key]?.lowercase()?.let { it == "true" } ?: default

            override fun getInt(
                key: String,
                default: Int,
            ): Int = map[key]?.toIntOrNull() ?: default

            override fun getLong(
                key: String,
                default: Long,
            ): Long = map[key]?.toLongOrNull() ?: default

            override fun getFloat(
                key: String,
                default: Float,
            ): Float = map[key]?.toFloatOrNull() ?: default

            override fun keys(): Set<String> = map.keys
        }

    @Test
    fun `no configured provider yields empty map`() {
        assertTrue(OAuthProviderConfigs.fromConfig(configOf(emptyMap())).isEmpty())
    }

    @Test
    fun `provider with only client id is not enabled`() {
        val providers =
            OAuthProviderConfigs.fromConfig(
                configOf(mapOf("OAUTH_GITHUB_CLIENT_ID" to "id-only")),
            )
        assertTrue(providers.isEmpty(), "Both CLIENT_ID and CLIENT_SECRET are required")
    }

    @Test
    fun `github provider is enabled with both credentials`() {
        val providers =
            OAuthProviderConfigs.fromConfig(
                configOf(
                    mapOf(
                        "OAUTH_GITHUB_CLIENT_ID" to "gh-id",
                        "OAUTH_GITHUB_CLIENT_SECRET" to "gh-secret",
                    ),
                ),
            )
        assertEquals(setOf("github"), providers.keys)
        val github = providers.getValue("github")
        assertEquals("gh-id", github.clientId)
        assertEquals("https://github.com/login/oauth/authorize", github.authorizationEndpoint)
        assertTrue(github.scopes.isNotEmpty())
    }

    @Test
    fun `google provider uses offline access type`() {
        val providers =
            OAuthProviderConfigs.fromConfig(
                configOf(
                    mapOf(
                        "OAUTH_GOOGLE_CLIENT_ID" to "g-id",
                        "OAUTH_GOOGLE_CLIENT_SECRET" to "g-secret",
                    ),
                ),
            )
        assertEquals("offline", providers.getValue("google").accessType)
    }
}
