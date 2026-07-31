package dev.promethe.core.sandbox

import dev.promethe.api.SandboxNetworkMode
import dev.promethe.api.SandboxPermissionProfile

data class SandboxNetworkDecision(
    val allowed: Boolean,
    val reason: String,
)

object SandboxDomainPolicy {
    fun evaluate(
        host: String,
        profile: SandboxPermissionProfile,
    ): SandboxNetworkDecision {
        val validated = SandboxPolicy.validate(profile)
        if (validated.networkMode == SandboxNetworkMode.OFF) {
            return SandboxNetworkDecision(false, "sandbox network access is disabled")
        }
        val normalizedHost = host.trim().lowercase().removeSuffix(".")
        if (normalizedHost.isBlank() || isIpLiteral(normalizedHost)) {
            return SandboxNetworkDecision(false, "direct IP destinations are blocked")
        }
        val matched =
            validated.allowedDomains.any { pattern ->
                if (pattern.startsWith("*.")) {
                    val suffix = pattern.removePrefix("*.")
                    normalizedHost != suffix && normalizedHost.endsWith(".$suffix")
                } else {
                    normalizedHost == pattern
                }
            }
        return if (matched) {
            SandboxNetworkDecision(true, "domain is allowed by the sandbox profile")
        } else {
            SandboxNetworkDecision(false, "domain is not present in the sandbox allow-list")
        }
    }

    private fun isIpLiteral(host: String): Boolean =
        host.startsWith("[") ||
            host.contains(':') ||
            host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }
}
