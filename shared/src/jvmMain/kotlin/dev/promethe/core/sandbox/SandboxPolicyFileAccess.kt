package dev.promethe.core.sandbox

import dev.promethe.api.SandboxMode
import dev.promethe.core.SecureDeletedEntry
import dev.promethe.core.SecureJvmWorkspaceFileMutator
import dev.promethe.core.SecureJvmWorkspaceFileReader
import dev.promethe.core.SecureJvmWorkspaceFileWriter
import dev.promethe.core.WorkspaceDirectoryResolver
import dev.promethe.core.WorkspaceFileMutator
import dev.promethe.core.WorkspaceFileReader
import dev.promethe.core.WorkspaceFileWriter
import java.nio.file.Path

/** Applies the live sandbox file policy before delegating to TOCTOU-resistant NIO accessors. */
class SandboxPolicyFileAccess(
    private val runtimePolicy: SandboxRuntimePolicy,
    private val broker: WorkspaceAccessBroker = CanonicalWorkspaceAccessBroker(),
) : WorkspaceFileReader,
    WorkspaceFileWriter,
    WorkspaceFileMutator,
    WorkspaceDirectoryResolver {
    private val workspaceRoot = runtimePolicy.workspaceRoot()

    override suspend fun read(relativePath: String): String {
        val target = readablePath(relativePath)
        val parent = target.parent ?: throw WorkspaceAccessDeniedException("file has no readable parent")
        return SecureJvmWorkspaceFileReader(parent.toString()).read(target.fileName.toString())
    }

    override suspend fun write(
        relativePath: String,
        content: String,
    ) {
        val target = writablePath(relativePath)
        val parent = target.parent ?: throw WorkspaceAccessDeniedException("file has no writable parent")
        SecureJvmWorkspaceFileWriter(parent.toString()).write(target.fileName.toString(), content)
    }

    override suspend fun delete(
        relativePath: String,
        recursive: Boolean,
    ): SecureDeletedEntry {
        val target = writablePath(relativePath)
        val parent = target.parent ?: throw WorkspaceAccessDeniedException("path has no writable parent")
        return SecureJvmWorkspaceFileMutator(parent.toString()).delete(target.fileName.toString(), recursive)
    }

    override suspend fun move(
        sourcePath: String,
        destinationPath: String,
    ) {
        val source = writablePath(sourcePath)
        val destination = writablePath(destinationPath)
        val root = commonWritableRoot(source, destination)
        val mutator = SecureJvmWorkspaceFileMutator(root.toString())
        mutator.move(root.relativize(source).toString(), root.relativize(destination).toString())
    }

    override fun resolve(requestedPath: String): String {
        val resolved = readablePath(requestedPath)
        if (!resolved.toFile().isDirectory) {
            throw WorkspaceAccessDeniedException("path is not a directory")
        }
        return resolved.toString()
    }

    fun describeRoots(includePermissions: Boolean = true): String {
        val profile = runtimePolicy.get()
        if (profile.mode == SandboxMode.FULL_ACCESS) {
            return "File tools: full local access (approval required).\nProcess workspace: $workspaceRoot"
        }
        return buildString {
            appendLine("File roots (inspect every root when the user did not specify a path):")
            readableRoots().forEachIndexed { index, root ->
                val writable = profile.writableRoots.any { it.equals(root, ignoreCase = isWindows()) }
                val permission = if (includePermissions) " [${if (writable) "read/write" else "read-only"}]" else ""
                val kind = if (index == 0) "main workspace" else "selected folder"
                appendLine("- $root [$kind]$permission")
            }
            append("Commands remain confined to the main workspace; file tools may use every root above.")
        }
    }

    fun readableRoots(): List<String> = runtimePolicy.get().readableRoots

    fun isWorkspacePath(requestedPath: String): Boolean =
        runCatching { readablePath(requestedPath).toString().equals(workspaceRoot, ignoreCase = isWindows()) }
            .getOrDefault(false)

    private fun readablePath(requestedPath: String): Path = Path.of(broker.resolveReadablePath(workspaceRoot, requestedPath, runtimePolicy.get()))

    private fun writablePath(requestedPath: String): Path = Path.of(broker.resolveWritablePath(workspaceRoot, requestedPath, runtimePolicy.get()))

    private fun commonWritableRoot(
        source: Path,
        destination: Path,
    ): Path {
        val profile = runtimePolicy.get()
        val roots =
            if (profile.mode == SandboxMode.FULL_ACCESS) {
                generateSequence(source.parent) { it.parent }.toList()
            } else {
                profile.writableRoots.map { Path.of(it).toRealPath() }
            }
        return roots
            .filter { root -> source.startsWith(root) && destination.startsWith(root) }
            .maxByOrNull(Path::getNameCount)
            ?: throw WorkspaceAccessDeniedException("move must remain inside one writable root")
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").contains("win", ignoreCase = true)
}
