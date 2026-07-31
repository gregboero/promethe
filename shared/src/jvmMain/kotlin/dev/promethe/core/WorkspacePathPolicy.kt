package dev.promethe.core

import java.io.File
import java.nio.file.Files

/** Resolves user-controlled paths without allowing a sibling-prefix or ../ escape. */
object WorkspacePathPolicy {
    val protectedNames = setOf(".git", ".promethe", ".codex", ".agents")

    fun resolve(
        workspace: String,
        requested: String = ".",
    ): File? =
        runCatching {
            val root = File(workspace).toPath().toRealPath()
            val candidate = root.resolve(requested).normalize()
            if (!candidate.startsWith(root)) return null
            if (root.relativize(candidate).firstOrNull()?.toString()?.lowercase() in protectedNames) return null

            var existingAncestor = candidate
            while (!Files.exists(existingAncestor)) {
                existingAncestor = existingAncestor.parent ?: return null
            }
            val realAncestor = existingAncestor.toRealPath()
            if (!realAncestor.startsWith(root)) return null

            val resolved = realAncestor.resolve(existingAncestor.relativize(candidate)).normalize()
            resolved.takeIf { it.startsWith(root) }?.toFile()
        }.getOrNull()

    fun isProtected(
        workspace: File,
        candidate: File,
    ): Boolean {
        val workspacePath = workspace.toPath()
        val candidatePath = candidate.toPath()
        if (!candidatePath.startsWith(workspacePath)) return true
        val relative = workspacePath.relativize(candidatePath)
        return relative.firstOrNull()?.toString()?.lowercase() in protectedNames
    }
}
