package dev.promethe.core.sandbox

import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxNetworkMode
import dev.promethe.api.SandboxPermissionProfile

class SandboxPolicyException(
    message: String,
) : IllegalArgumentException(message)

object SandboxPolicy {
    private const val MAX_TIMEOUT_MILLIS = 15 * 60 * 1_000L
    private const val MAX_OUTPUT_BYTES = 10 * 1024 * 1024
    private const val MAX_MEMORY_BYTES = 16L * 1024L * 1024L * 1024L
    private const val MAX_PROCESS_LIMIT = 1_024

    fun validate(profile: SandboxPermissionProfile): SandboxPermissionProfile {
        val limits = profile.limits
        requirePolicy(limits.timeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "timeout is outside the supported range" }
        requirePolicy(limits.maxOutputBytesPerStream in 1..MAX_OUTPUT_BYTES) { "output limit is outside the supported range" }
        requirePolicy(limits.memoryBytes in 16L * 1024L * 1024L..MAX_MEMORY_BYTES) { "memory limit is outside the supported range" }
        requirePolicy(limits.cpuLimit > 0.0 && limits.cpuLimit <= 64.0) { "CPU limit is outside the supported range" }
        requirePolicy(limits.processLimit in 1..MAX_PROCESS_LIMIT) { "process limit is outside the supported range" }
        requirePolicy(profile.protectedPaths.none { it.isBlank() }) { "protected paths cannot be blank" }
        requirePolicy(profile.allowedDomains.none { it.isBlank() }) { "allowed domains cannot be blank" }
        requirePolicy(profile.networkMode != SandboxNetworkMode.OFF || profile.allowedDomains.isEmpty()) {
            "allowed domains require ALLOWLIST network mode"
        }
        requirePolicy(profile.mode != SandboxMode.READ_ONLY || profile.writableRoots.isEmpty()) {
            "READ_ONLY profiles cannot contain writable roots"
        }
        return profile.copy(
            readableRoots = profile.readableRoots.distinct(),
            writableRoots = profile.writableRoots.distinct(),
            protectedPaths = profile.protectedPaths.distinct(),
            allowedDomains = profile.allowedDomains.map(::normalizeDomainPattern).distinct(),
        )
    }

    private fun normalizeDomainPattern(value: String): String {
        val normalized = value.trim().lowercase().removeSuffix(".")
        requirePolicy(!normalized.contains("://")) { "domain rules must not contain a URL scheme" }
        requirePolicy(!normalized.contains('/')) { "domain rules must not contain a path" }
        requirePolicy(
            normalized.matches(Regex("""(\*\.)?[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?""")),
        ) { "invalid domain rule '$value'" }
        return normalized
    }

    private inline fun requirePolicy(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) throw SandboxPolicyException(message())
    }
}
