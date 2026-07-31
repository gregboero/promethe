package dev.promethe.core.sandbox

import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxPermissionProfile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.absolute

class WorkspaceAccessDeniedException(
    message: String,
) : SecurityException(message)

class CanonicalWorkspaceAccessBroker : WorkspaceAccessBroker {
    override fun resolveReadablePath(
        workspaceRoot: String,
        requestedPath: String,
        profile: SandboxPermissionProfile,
    ): String {
        val validated = SandboxPolicy.validate(profile)
        val workspace = canonicalExistingDirectory(Path.of(workspaceRoot))
        val candidate = resolveCandidate(workspace, requestedPath, mustExist = true)
        if (validated.mode != SandboxMode.FULL_ACCESS) {
            val roots = listOf(workspace) + validated.readableRoots.map(::canonicalExistingDirectory)
            requireInside(candidate, roots, "read")
        }
        return candidate.toString()
    }

    override fun resolveWritablePath(
        workspaceRoot: String,
        requestedPath: String,
        profile: SandboxPermissionProfile,
    ): String {
        val validated = SandboxPolicy.validate(profile)
        if (validated.mode == SandboxMode.READ_ONLY) {
            throw WorkspaceAccessDeniedException("sandbox profile is read-only")
        }
        val workspace = canonicalExistingDirectory(Path.of(workspaceRoot))
        val candidate = resolveCandidate(workspace, requestedPath, mustExist = false)
        if (validated.mode != SandboxMode.FULL_ACCESS) {
            val roots = listOf(workspace) + validated.writableRoots.map(::canonicalExistingDirectory)
            requireInside(candidate, roots, "write")
        }
        rejectProtectedPath(workspace, candidate, validated.protectedPaths)
        return candidate.toString()
    }

    private fun canonicalExistingDirectory(path: String): Path = canonicalExistingDirectory(Path.of(path))

    private fun canonicalExistingDirectory(path: Path): Path {
        val canonical = path.absolute().normalize().toRealPath()
        if (!Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) {
            throw WorkspaceAccessDeniedException("sandbox root is not a directory")
        }
        return canonical
    }

    private fun resolveCandidate(
        workspace: Path,
        requestedPath: String,
        mustExist: Boolean,
    ): Path {
        if (requestedPath.isBlank()) return workspace
        val requested = Path.of(requestedPath)
        val lexical = (if (requested.isAbsolute) requested else workspace.resolve(requested)).absolute().normalize()
        if (mustExist || Files.exists(lexical, LinkOption.NOFOLLOW_LINKS)) {
            return runCatching { lexical.toRealPath() }
                .getOrElse { throw WorkspaceAccessDeniedException("path cannot be resolved safely") }
        }

        var existingParent: Path? = lexical.parent
        val missing = ArrayDeque<String>()
        if (lexical.fileName != null) missing.addFirst(lexical.fileName.toString())
        while (existingParent != null && !Files.exists(existingParent, LinkOption.NOFOLLOW_LINKS)) {
            existingParent.fileName?.toString()?.let(missing::addFirst)
            existingParent = existingParent.parent
        }
        val canonicalParent =
            existingParent?.toRealPath()
                ?: throw WorkspaceAccessDeniedException("path has no resolvable parent")
        return missing.fold(canonicalParent) { current, segment -> current.resolve(segment) }.normalize()
    }

    private fun requireInside(
        candidate: Path,
        roots: List<Path>,
        operation: String,
    ) {
        if (roots.none(candidate::startsWith)) {
            throw WorkspaceAccessDeniedException("$operation path is outside the permitted roots")
        }
    }

    private fun rejectProtectedPath(
        workspace: Path,
        candidate: Path,
        protectedPaths: List<String>,
    ) {
        if (!candidate.startsWith(workspace)) return
        val relative = workspace.relativize(candidate)
        val denied =
            protectedPaths.any { protected ->
                val protectedPath = Path.of(protected).normalize()
                protectedPath.nameCount > 0 && relative.startsWith(protectedPath)
            }
        if (denied) throw WorkspaceAccessDeniedException("path is protected by the sandbox profile")
    }
}
