package dev.promethe.gateway

import dev.promethe.core.config.ConfigProvider
import java.net.URI

/**
 * Security-sensitive gateway settings. These values are intentionally read once
 * at startup so a Settings UI action cannot silently widen the network surface.
 */
data class GatewaySecurityConfig(
    val bindHost: String,
    val remoteAccessEnabled: Boolean,
    val allowedOrigins: Set<AllowedOrigin>,
    val remoteSessionTtlMinutes: Long,
    val publicBaseUrl: String? = null,
) {
    data class AllowedOrigin(
        val value: String,
        val hostWithPort: String,
        val scheme: String,
    )

    companion object {
        fun fromConfig(): GatewaySecurityConfig {
            val config = ConfigProvider.get()
            val bindHost = config.get("GATEWAY_BIND_HOST", "127.0.0.1").trim()
            require(bindHost.isNotBlank()) { "GATEWAY_BIND_HOST must not be blank" }

            val ttl = config.getLong("REMOTE_SESSION_TTL_MINUTES", 60)
            require(ttl in 15..1_440) { "REMOTE_SESSION_TTL_MINUTES must be between 15 and 1440" }

            val origins =
                config
                    .get("CORS_ALLOWED_ORIGINS", "")
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .map(::parseOrigin)
                    .toSet()

            val remoteAccessEnabled = config.getBoolean("REMOTE_ACCESS_ENABLED", false)
            val publicBaseUrl = config.get("PUBLIC_BASE_URL", "").trim().removeSuffix("/").ifBlank { null }
            if (remoteAccessEnabled) {
                requireNotNull(publicBaseUrl) { "PUBLIC_BASE_URL is required when REMOTE_ACCESS_ENABLED=true" }
                val publicUri = URI(publicBaseUrl)
                require(publicUri.scheme == "https" && !publicUri.host.isNullOrBlank() && publicUri.query == null && publicUri.fragment == null) {
                    "PUBLIC_BASE_URL must be an absolute HTTPS URL without query or fragment"
                }
                require(config.get("APPROVAL_MODE", "dangerous") != "auto") {
                    "APPROVAL_MODE=auto is forbidden when remote access is enabled"
                }
                require(config.get("SANDBOX_MODE", "WORKSPACE_WRITE").uppercase() != "FULL_ACCESS") {
                    "SANDBOX_MODE=FULL_ACCESS is forbidden when remote access is enabled"
                }
                require(config.get("SANDBOX_NETWORK_MODE", "OFF").uppercase() == "OFF") {
                    "Sandbox network access is unavailable when remote access is enabled"
                }
            }

            return GatewaySecurityConfig(
                bindHost = bindHost,
                remoteAccessEnabled = remoteAccessEnabled,
                allowedOrigins = origins,
                remoteSessionTtlMinutes = ttl,
                publicBaseUrl = publicBaseUrl,
            )
        }

        private fun parseOrigin(value: String): AllowedOrigin {
            val uri = try {
                URI(value.removeSuffix("/"))
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid CORS_ALLOWED_ORIGINS entry: $value", error)
            }
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.path.isNullOrEmpty()) {
                "CORS_ALLOWED_ORIGINS entries must be absolute http(s) origins: $value"
            }
            require(uri.query == null && uri.fragment == null && uri.userInfo == null) {
                "CORS_ALLOWED_ORIGINS entries must not contain path, query, fragment, or credentials: $value"
            }
            val isDefaultPort = (uri.scheme == "http" && uri.port in setOf(-1, 80)) || (uri.scheme == "https" && uri.port in setOf(-1, 443))
            val hostWithPort = if (isDefaultPort) uri.host else "${uri.host}:${uri.port}"
            return AllowedOrigin(
                value = uri.toString().removeSuffix("/"),
                hostWithPort = hostWithPort,
                scheme = uri.scheme,
            )
        }
    }
}
