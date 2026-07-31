package dev.promethe.core.sandbox

import dev.promethe.api.SandboxedExecutionRequest
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.core.PrometheJson
import kotlinx.serialization.encodeToString
import java.security.MessageDigest

object SandboxApprovalFingerprint {
    fun sha256(request: SandboxedExecutionRequest): String {
        val canonical =
            request.copy(
                environment = request.environment.toSortedMap(),
                sensitiveEnvironmentKeys = request.sensitiveEnvironmentKeys.toSortedSet(),
                profile =
                    request.profile.copy(
                        readableRoots = request.profile.readableRoots.sorted(),
                        writableRoots = request.profile.writableRoots.sorted(),
                        protectedPaths = request.profile.protectedPaths.sorted(),
                        allowedDomains = request.profile.allowedDomains.sorted(),
                    ),
            )
        val bytes = PrometheJson.encodeToString(canonical).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun policySha256(
        profile: SandboxPermissionProfile,
        workspaceRoot: String,
    ): String {
        val canonical =
            profile.copy(
                readableRoots = profile.readableRoots.sorted(),
                writableRoots = profile.writableRoots.sorted(),
                protectedPaths = profile.protectedPaths.sorted(),
                allowedDomains = profile.allowedDomains.sorted(),
            )
        val value = "$workspaceRoot\n${PrometheJson.encodeToString(canonical)}"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
