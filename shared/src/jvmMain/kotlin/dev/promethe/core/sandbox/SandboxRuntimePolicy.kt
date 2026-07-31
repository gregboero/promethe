package dev.promethe.core.sandbox

import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxNetworkMode
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.api.SandboxResourceLimits
import dev.promethe.core.AgentConfig
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class SandboxRuntimePolicy(
    workspaceRoot: String,
    config: AgentConfig,
) {
    private val workspaceRoot = Path.of(workspaceRoot).toRealPath().toString()
    private val current =
        AtomicReference(
            normalize(
                SandboxPermissionProfile(
                    mode = config.sandboxMode,
                    approvalPolicy = config.sandboxApprovalPolicy,
                    networkMode = config.sandboxNetworkMode,
                    allowedDomains = config.sandboxAllowedDomains,
                    limits =
                        SandboxResourceLimits(
                            timeoutMillis = config.executionTimeoutMs,
                            maxOutputBytesPerStream = config.maxOutputBytes,
                        ),
                ),
            ),
        )

    fun get(): SandboxPermissionProfile = current.get()

    fun update(
        requested: SandboxPermissionProfile,
        localOwner: Boolean,
    ): SandboxPermissionProfile {
        if (requested.mode == SandboxMode.FULL_ACCESS) {
            check(localOwner) { "FULL_ACCESS requires the local loopback owner." }
        }
        return normalize(requested).also(current::set)
    }

    fun withTimeout(timeoutMillis: Long): SandboxPermissionProfile {
        val profile = get()
        return profile.copy(
            limits =
                profile.limits.copy(
                    timeoutMillis = timeoutMillis.coerceIn(1, MAX_TIMEOUT_MILLIS),
                ),
        )
    }

    private fun normalize(profile: SandboxPermissionProfile): SandboxPermissionProfile {
        if (profile.mode == SandboxMode.FULL_ACCESS) {
            throw SandboxPolicyException("FULL_ACCESS is unavailable until a native unrestricted profile is certified.")
        }
        if (profile.networkMode != SandboxNetworkMode.OFF || profile.allowedDomains.isNotEmpty()) {
            throw SandboxPolicyException("Sandbox network access is unavailable until the authenticated proxy is certified.")
        }
        val requiredProtectedPaths = listOf(".git", ".promethe", ".codex", ".agents")
        return SandboxPolicy.validate(
            profile.copy(
                readableRoots = listOf(workspaceRoot),
                writableRoots =
                    if (profile.mode == SandboxMode.READ_ONLY) {
                        emptyList()
                    } else {
                        listOf(workspaceRoot)
                    },
                protectedPaths = (requiredProtectedPaths + profile.protectedPaths).distinct(),
                limits =
                    profile.limits.copy(
                        timeoutMillis = profile.limits.timeoutMillis.coerceIn(1, MAX_TIMEOUT_MILLIS),
                        maxOutputBytesPerStream =
                            profile.limits.maxOutputBytesPerStream.coerceIn(
                                1,
                                MAX_OUTPUT_BYTES,
                            ),
                    ),
            ),
        )
    }

    private companion object {
        const val MAX_TIMEOUT_MILLIS = 15 * 60 * 1_000L
        const val MAX_OUTPUT_BYTES = 10 * 1024 * 1024
    }
}
