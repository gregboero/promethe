package dev.promethe.core.sandbox

import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxNetworkMode
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.api.SandboxResourceLimits
import dev.promethe.core.AgentConfig
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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
                    readableRoots = config.sandboxReadableRoots,
                    writableRoots = config.sandboxWritableRoots,
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

    fun workspaceRoot(): String = workspaceRoot

    fun update(
        requested: SandboxPermissionProfile,
        localOwner: Boolean,
    ): SandboxPermissionProfile {
        val previous = current.get()
        if (requested.mode == SandboxMode.FULL_ACCESS) {
            check(localOwner) { "FULL_ACCESS requires the local loopback owner." }
        }
        if (
            requested.readableRoots.toSet() != previous.readableRoots.toSet() ||
            requested.writableRoots.toSet() != previous.writableRoots.toSet()
        ) {
            check(localOwner) { "Changing sandbox roots requires the local loopback owner." }
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

    fun processProfile(timeoutMillis: Long = get().limits.timeoutMillis): SandboxPermissionProfile {
        val profile = withTimeout(timeoutMillis)
        return profile.copy(
            mode = if (profile.mode == SandboxMode.READ_ONLY) SandboxMode.READ_ONLY else SandboxMode.WORKSPACE_WRITE,
            readableRoots = listOf(workspaceRoot),
            writableRoots = if (profile.mode == SandboxMode.READ_ONLY) emptyList() else listOf(workspaceRoot),
        )
    }

    private fun normalize(profile: SandboxPermissionProfile): SandboxPermissionProfile {
        if (profile.networkMode != SandboxNetworkMode.OFF || profile.allowedDomains.isNotEmpty()) {
            throw SandboxPolicyException("Sandbox network access is unavailable until the authenticated proxy is certified.")
        }
        val requiredProtectedPaths = listOf(".git", ".promethe", ".codex", ".agents")
        val requestedReadable = profile.readableRoots.filterNot(::isWorkspaceRoot).map(::canonicalRoot)
        val requestedWritable = profile.writableRoots.filterNot(::isWorkspaceRoot).map(::canonicalRoot)
        if (requestedReadable.size > MAX_ADDITIONAL_ROOTS || requestedWritable.size > MAX_ADDITIONAL_ROOTS) {
            throw SandboxPolicyException("Too many additional sandbox roots.")
        }
        val readableRoots = distinctRoots(listOf(workspaceRoot) + requestedReadable)
        val writableRoots =
            if (profile.mode == SandboxMode.READ_ONLY) {
                emptyList()
            } else {
                distinctRoots(listOf(workspaceRoot) + requestedWritable)
            }
        if (writableRoots.any { writable -> readableRoots.none { readable -> Path.of(writable).startsWith(Path.of(readable)) } }) {
            throw SandboxPolicyException("Every writable sandbox root must also be readable.")
        }
        return SandboxPolicy.validate(
            profile.copy(
                readableRoots = readableRoots,
                writableRoots = writableRoots,
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

    private fun isWorkspaceRoot(value: String): Boolean = runCatching { Path.of(value).toAbsolutePath().normalize() == Path.of(workspaceRoot) }.getOrDefault(false)

    private fun canonicalRoot(value: String): String {
        if (value.isBlank()) throw SandboxPolicyException("Sandbox roots cannot be blank.")
        val path =
            runCatching { Path.of(value).toAbsolutePath().normalize() }
                .getOrElse { throw SandboxPolicyException("Invalid sandbox root.") }
        val attributes =
            runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
                .getOrElse { throw SandboxPolicyException("Sandbox roots must be existing directories.") }
        if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.isOther) {
            throw SandboxPolicyException("Sandbox roots must be existing directories, not links or reparse points.")
        }
        return runCatching { path.toRealPath().toString() }
            .getOrElse { throw SandboxPolicyException("Sandbox root cannot be resolved safely.") }
    }

    private fun distinctRoots(values: List<String>): List<String> {
        val windows = System.getProperty("os.name").contains("win", ignoreCase = true)
        return values.distinctBy { value -> if (windows) value.lowercase() else value }
    }

    private companion object {
        const val MAX_TIMEOUT_MILLIS = 15 * 60 * 1_000L
        const val MAX_OUTPUT_BYTES = 10 * 1024 * 1024
        const val MAX_ADDITIONAL_ROOTS = 32
    }
}
