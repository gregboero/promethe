package dev.promethe.core.tools.fs

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.SecureDeletedEntry
import dev.promethe.core.CanonicalNioWorkspaceAccess
import dev.promethe.core.WorkspaceDirectoryResolver
import dev.promethe.core.WorkspaceFileMutator
import dev.promethe.core.sandbox.SandboxPolicyFileAccess
import dev.promethe.core.WorkspacePathPolicy
import dev.promethe.core.projectScopedPath
import kotlinx.serialization.Serializable
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Read-only traversal for agent-visible workspace files. Links and Windows
 * reparse points are deliberately invisible: resolving them could escape the
 * workspace before a tool gets a chance to apply its policy.
 */
private class SafeWorkspaceTraversal private constructor(
    private val root: SecureDirectoryStream<Path>?,
    private val openedStreams: List<DirectoryStream<Path>>,
    private val fallbackWorkspaceRoot: Path?,
    private val fallbackDirectory: Path?,
    private val protectRootMetadata: Boolean,
    val displayName: String,
) : AutoCloseable {
    data class Node(
        val name: String,
        val relativePath: String,
        val directory: Boolean,
        val size: Long,
        val children: List<Node>?,
    )

    fun snapshot(maxDepth: Int): List<Node> =
        root?.let { snapshot(it, Path.of(""), 0, maxDepth.coerceAtLeast(0), protectRootMetadata) }
            ?: snapshotFallback(
                requireNotNull(fallbackDirectory),
                Path.of(""),
                0,
                maxDepth.coerceAtLeast(0),
                protectRootMetadata,
            )

    fun search(
        pattern: String,
        maxResults: Int,
    ): List<String> {
        val results = mutableListOf<String>()
        val loweredPattern = pattern.lowercase()
        val isGlob = loweredPattern.contains("*") || loweredPattern.contains("?")
        val regex =
            if (isGlob) {
                Regex(
                    loweredPattern.replace(".", "\\.").replace("*", ".*").replace("?", "."),
                    RegexOption.IGNORE_CASE,
                )
            } else {
                null
            }
        root?.let { secureRoot ->
            visit(secureRoot, Path.of(""), protectRootMetadata) { _, name, relative, attributes, child ->
                if (results.size >= maxResults.coerceAtLeast(0)) return@visit false
                val matches = regex?.matches(name) ?: name.lowercase().contains(loweredPattern)
                if (matches) results += relative + if (attributes.isDirectory) "/" else ""
                child
            }
        } ?: visitFallback(requireNotNull(fallbackDirectory), Path.of(""), protectRootMetadata) { entry, relative ->
            if (results.size >= maxResults.coerceAtLeast(0)) return@visitFallback false
            val matches = regex?.matches(entry.name) ?: entry.name.lowercase().contains(loweredPattern)
            if (matches) results += relative + if (entry.attributes.isDirectory) "/" else ""
            entry.attributes.isDirectory
        }
        return results
    }

    fun grep(
        regex: Regex,
        includeRegex: Regex?,
        binaryExtensions: Set<String>,
        maxResults: Int,
    ): List<String> {
        val results = mutableListOf<String>()
        root?.let { secureRoot ->
            visit(secureRoot, Path.of(""), protectRootMetadata) { directory, name, relative, attributes, child ->
                if (results.size >= maxResults.coerceAtLeast(0)) return@visit false
                if (
                    attributes.isRegularFile &&
                    name.substringAfterLast('.', "").lowercase() !in binaryExtensions &&
                    (includeRegex == null || includeRegex.matches(name))
                ) {
                    directory.newByteChannel(Path.of(name), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                        Channels
                            .newReader(channel, StandardCharsets.UTF_8)
                            .buffered()
                            .useLines { lines -> collectMatchingLines(lines, regex, relative, maxResults, results) }
                    }
                }
                child
            }
        } ?: visitFallback(requireNotNull(fallbackDirectory), Path.of(""), protectRootMetadata) { entry, relative ->
            if (results.size >= maxResults.coerceAtLeast(0)) return@visitFallback false
            if (
                entry.attributes.isRegularFile &&
                entry.name.substringAfterLast('.', "").lowercase() !in binaryExtensions &&
                (includeRegex == null || includeRegex.matches(entry.name))
            ) {
                val workspaceRoot = requireNotNull(fallbackWorkspaceRoot)
                val workspaceRelative = workspaceRoot.relativize(entry.path).toString()
                CanonicalNioWorkspaceAccess
                    .read(workspaceRoot, workspaceRelative)
                    .lineSequence()
                    .let { lines -> collectMatchingLines(lines, regex, relative, maxResults, results) }
            }
            entry.attributes.isDirectory
        }
        return results
    }

    private fun collectMatchingLines(
        lines: Sequence<String>,
        regex: Regex,
        relative: String,
        maxResults: Int,
        results: MutableList<String>,
    ) {
        lines.forEachIndexed lineLoop@{ index, line ->
            if (results.size >= maxResults.coerceAtLeast(0)) return@lineLoop
            if (regex.containsMatchIn(line)) results += "$relative:${index + 1}: ${line.take(200)}"
        }
    }

    private fun snapshot(
        directory: SecureDirectoryStream<Path>,
        prefix: Path,
        depth: Int,
        maxDepth: Int,
        protectMetadata: Boolean,
    ): List<Node> =
        entries(directory, protectMetadata).map { entry ->
            val relative = prefix.resolve(entry.name)
            val children =
                if (entry.attributes.isDirectory && depth < maxDepth) {
                    directory.newDirectoryStream(Path.of(entry.name), NOFOLLOW_LINKS).use { child ->
                        snapshot(child, relative, depth + 1, maxDepth, false)
                    }
                } else if (entry.attributes.isDirectory) {
                    null
                } else {
                    emptyList()
                }
            Node(
                name = entry.name,
                relativePath = relative.toString(),
                directory = entry.attributes.isDirectory,
                size = entry.attributes.size(),
                children = children,
            )
        }

    private fun snapshotFallback(
        directory: Path,
        prefix: Path,
        depth: Int,
        maxDepth: Int,
        protectMetadata: Boolean,
    ): List<Node> =
        fallbackEntries(directory, protectMetadata).map { entry ->
            val relative = prefix.resolve(entry.name)
            val children =
                if (entry.attributes.isDirectory && depth < maxDepth) {
                    snapshotFallback(entry.path, relative, depth + 1, maxDepth, false)
                } else if (entry.attributes.isDirectory) {
                    null
                } else {
                    emptyList()
                }
            Node(
                name = entry.name,
                relativePath = relative.toString(),
                directory = entry.attributes.isDirectory,
                size = entry.attributes.size(),
                children = children,
            )
        }

    private fun visit(
        directory: SecureDirectoryStream<Path>,
        prefix: Path,
        protectMetadata: Boolean,
        visitor: (
            directory: SecureDirectoryStream<Path>,
            name: String,
            relativePath: String,
            attributes: BasicFileAttributes,
            visitChildren: Boolean,
        ) -> Boolean,
    ) {
        entries(directory, protectMetadata).forEach { entry ->
            val relative = prefix.resolve(entry.name)
            val visitChildren = visitor(directory, entry.name, relative.toString(), entry.attributes, entry.attributes.isDirectory)
            if (entry.attributes.isDirectory && visitChildren) {
                directory.newDirectoryStream(Path.of(entry.name), NOFOLLOW_LINKS).use { child ->
                    visit(child, relative, false, visitor)
                }
            }
        }
    }

    private fun visitFallback(
        directory: Path,
        prefix: Path,
        protectMetadata: Boolean,
        visitor: (CanonicalNioWorkspaceAccess.Entry, String) -> Boolean,
    ) {
        fallbackEntries(directory, protectMetadata).forEach { entry ->
            val relative = prefix.resolve(entry.name)
            val visitChildren = visitor(entry, relative.toString())
            if (entry.attributes.isDirectory && visitChildren) {
                visitFallback(entry.path, relative, false, visitor)
            }
        }
    }

    private fun fallbackEntries(
        directory: Path,
        protectMetadata: Boolean,
    ): List<CanonicalNioWorkspaceAccess.Entry> {
        val workspaceRoot = requireNotNull(fallbackWorkspaceRoot)
        val relative = workspaceRoot.relativize(directory).toString().ifBlank { "." }
        return CanonicalNioWorkspaceAccess
            .listDirectory(workspaceRoot, relative)
            .second
            .filterNot { protectMetadata && it.name.lowercase() in WorkspacePathPolicy.protectedNames }
    }

    private fun entries(
        directory: SecureDirectoryStream<Path>,
        protectMetadata: Boolean,
    ): List<SecureEntry> =
        directory
            .mapNotNull { path ->
                val name = path.fileName.toString()
                if (protectMetadata && name.lowercase() in WorkspacePathPolicy.protectedNames) return@mapNotNull null
                val attributes =
                    runCatching {
                        directory
                            .getFileAttributeView(Path.of(name), BasicFileAttributeView::class.java, NOFOLLOW_LINKS)
                            .readAttributes()
                    }.getOrNull() ?: return@mapNotNull null
                if (!attributes.isDirectory && !attributes.isRegularFile) return@mapNotNull null
                SecureEntry(name, attributes)
            }.sortedBy(SecureEntry::name)

    override fun close() {
        openedStreams.asReversed().forEach { stream -> runCatching { stream.close() } }
    }

    private data class SecureEntry(
        val name: String,
        val attributes: BasicFileAttributes,
    )

    companion object {
        fun open(
            workDir: String,
            requestedPath: String,
        ): SafeWorkspaceTraversal {
            val workspace = Path.of(workDir).toRealPath()
            val requested =
                WorkspacePathPolicy.resolve(workDir, requestedPath)?.toPath()?.toAbsolutePath()?.normalize()
                    ?: error("access denied: path must be inside the workspace")
            require(requested.startsWith(workspace)) { "access denied: path must be inside the workspace" }
            return openResolved(workspace, requested)
        }

        fun openAbsolute(requestedPath: String): SafeWorkspaceTraversal {
            val requested = Path.of(requestedPath).toRealPath()
            require(Files.isDirectory(requested, NOFOLLOW_LINKS)) { "access denied: path must be a directory" }
            return openResolved(requested, requested)
        }

        private fun openResolved(
            workspace: Path,
            requested: Path,
        ): SafeWorkspaceTraversal {
            val relative = workspace.relativize(requested)
            val opened = mutableListOf<DirectoryStream<Path>>()
            val workspaceStream = Files.newDirectoryStream(workspace)
            opened += workspaceStream
            val secureWorkspaceStream = workspaceStream as? SecureDirectoryStream<Path>
            if (secureWorkspaceStream == null) {
                opened.asReversed().forEach { stream -> runCatching { stream.close() } }
                CanonicalNioWorkspaceAccess.listDirectory(
                    workspace,
                    workspace.relativize(requested).toString().ifBlank { "." },
                )
                return SafeWorkspaceTraversal(
                    root = null,
                    openedStreams = emptyList(),
                    fallbackWorkspaceRoot = workspace,
                    fallbackDirectory = requested,
                    protectRootMetadata = relative.toString().isBlank(),
                    displayName = requested.fileName?.toString() ?: workspace.fileName.toString(),
                )
            }
            try {
                var current: SecureDirectoryStream<Path> = requireNotNull(secureWorkspaceStream)
                if (relative.toString().isNotBlank()) {
                    relative.forEach { segment ->
                        val next = current.newDirectoryStream(segment, NOFOLLOW_LINKS)
                        opened += next
                        current = next
                    }
                }
                return SafeWorkspaceTraversal(
                    root = current,
                    openedStreams = opened,
                    fallbackWorkspaceRoot = null,
                    fallbackDirectory = null,
                    protectRootMetadata = relative.toString().isBlank(),
                    displayName = requested.fileName?.toString() ?: workspace.fileName.toString(),
                )
            } catch (error: Exception) {
                opened.asReversed().forEach { stream -> runCatching { stream.close() } }
                throw error
            }
        }
    }
}

// ── Args ─────────────────────────────────────────────────────────────

@Serializable
data class FileDeleteArgs(
    @property:LLMDescription("Path to the file or directory to delete.")
    val path: String,
    @property:LLMDescription("If true, delete directories recursively. Default false.")
    val recursive: Boolean = false,
)

@Serializable
data class FileMoveArgs(
    @property:LLMDescription("Source path of the file or directory.")
    val source: String,
    @property:LLMDescription("Destination path.")
    val destination: String,
)

@Serializable
data class DirectoryTreeArgs(
    @property:LLMDescription("Root path to list. Defaults to current directory.")
    val path: String = ".",
    @property:LLMDescription("Maximum depth to recurse. Default 3.")
    val maxDepth: Int = 3,
    @property:LLMDescription("Include every selected file root when listing the main workspace. Default true.")
    val includeSelectedRoots: Boolean = true,
)

@Serializable
data class WorkspaceRootsArgs(
    @property:LLMDescription("Include read/write permissions in the result.")
    val includePermissions: Boolean = true,
)

@Serializable
data class FileSearchArgs(
    @property:LLMDescription("Root directory to search in.")
    val path: String = ".",
    @property:LLMDescription("Glob pattern (e.g. '*.kt') or substring to match against file names.")
    val pattern: String,
    @property:LLMDescription("Maximum number of results. Default 50.")
    val maxResults: Int = 50,
)

@Serializable
data class CodeGrepArgs(
    @property:LLMDescription("Text or regex pattern to search for in file contents.")
    val pattern: String,
    @property:LLMDescription("Root directory to search in. Defaults to current directory.")
    val path: String = ".",
    @property:LLMDescription("Glob filter for file names (e.g. '*.kt'). Empty means all files.")
    val include: String = "",
    @property:LLMDescription("Maximum number of matching lines to return. Default 50.")
    val maxResults: Int = 50,
)

// ── Tools ────────────────────────────────────────────────────────────

class WorkspaceRootsTool(
    private val fileAccess: SandboxPolicyFileAccess,
) : SimpleTool<WorkspaceRootsArgs>(
        argsType = typeToken<WorkspaceRootsArgs>(),
        name = "workspace_roots",
        description = "List the main workspace and additional folders available to file tools. Use this before file operations when no path was supplied.",
    ) {
    override suspend fun execute(args: WorkspaceRootsArgs): String = fileAccess.describeRoots(args.includePermissions)
}

class FileDeleteTool(
    private val workDir: String,
    private val mutator: WorkspaceFileMutator?,
) : SimpleTool<FileDeleteArgs>(
        argsType = typeToken<FileDeleteArgs>(),
        name = "file_delete",
        description = "Delete a file or directory. Use recursive=true for non-empty directories.",
    ) {
    override suspend fun execute(args: FileDeleteArgs): String {
        val path = projectScopedPath(args.path)
        val secureMutator =
            mutator
                ?: return "[ERROR] Secure workspace mutations are unavailable"
        return try {
            when (secureMutator.delete(path, args.recursive)) {
                SecureDeletedEntry.FILE -> "Deleted file: $path"
                SecureDeletedEntry.EMPTY_DIRECTORY -> "Deleted empty directory: $path"
                SecureDeletedEntry.RECURSIVE_DIRECTORY -> "Deleted directory recursively: $path"
            }
        } catch (_: DirectoryNotEmptyException) {
            "[ERROR] Directory not empty: $path"
        } catch (e: Exception) {
            "[ERROR] Delete failed: ${e.message}"
        }
    }
}

class FileMoveTool(
    private val workDir: String,
    private val mutator: WorkspaceFileMutator?,
) : SimpleTool<FileMoveArgs>(
        argsType = typeToken<FileMoveArgs>(),
        name = "file_move",
        description = "Move or rename a file or directory.",
    ) {
    override suspend fun execute(args: FileMoveArgs): String {
        val source = projectScopedPath(args.source)
        val destination = projectScopedPath(args.destination)
        val secureMutator =
            mutator
                ?: return "[ERROR] Secure workspace mutations are unavailable"
        return try {
            secureMutator.move(source, destination)
            "Moved $source → $destination"
        } catch (e: Exception) {
            "[ERROR] Move failed: ${e.message}"
        }
    }
}

class DirectoryTreeTool(
    private val workDir: String,
    private val directoryResolver: WorkspaceDirectoryResolver? = null,
) : SimpleTool<DirectoryTreeArgs>(
        argsType = typeToken<DirectoryTreeArgs>(),
        name = "directory_tree",
        description = "Show a directory tree. Call workspace_roots first when the user did not provide a path.",
    ) {
    override suspend fun execute(args: DirectoryTreeArgs): String =
        try {
            val path = projectScopedPath(args.path)
            val policyAccess = directoryResolver as? SandboxPolicyFileAccess
            val roots =
                if (
                    args.includeSelectedRoots &&
                    policyAccess != null &&
                    policyAccess.isWorkspacePath(path)
                ) {
                    policyAccess.readableRoots()
                } else {
                    emptyList()
                }
            if (roots.size > 1) {
                roots.joinToString("\n") { root -> renderTraversal(SafeWorkspaceTraversal.openAbsolute(root), args.maxDepth, root) }
            } else {
                openTraversal(path).use { traversal -> renderTraversal(traversal, args.maxDepth) }
            }
        } catch (e: Exception) {
            "[ERROR] Directory traversal failed: ${e.message}"
        }

    private fun openTraversal(path: String): SafeWorkspaceTraversal =
        directoryResolver?.let { SafeWorkspaceTraversal.openAbsolute(it.resolve(path)) }
            ?: SafeWorkspaceTraversal.open(workDir, path)

    private fun renderTraversal(
        traversal: SafeWorkspaceTraversal,
        maxDepth: Int,
        heading: String = traversal.displayName,
    ): String =
        traversal.use {
            val output = StringBuilder().appendLine("$heading/")
            renderNodes(it.snapshot(maxDepth), "", output)
            output.toString()
        }

    private fun renderNodes(
        nodes: List<SafeWorkspaceTraversal.Node>,
        prefix: String,
        output: StringBuilder,
    ) {
        nodes.forEachIndexed { index, node ->
            val isLast = index == nodes.lastIndex
            val connector = if (isLast) "└── " else "├── "
            val suffix = if (node.directory) "/" else " (${formatSize(node.size)})"
            output.appendLine("$prefix$connector${node.name}$suffix")
            if (node.directory) {
                val childPrefix = prefix + if (isLast) "    " else "│   "
                if (node.children == null) {
                    output.appendLine("$childPrefix└── ...")
                } else {
                    renderNodes(node.children, childPrefix, output)
                }
            }
        }
    }

    private fun formatSize(bytes: Long): String =
        when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> "${bytes / (1024 * 1024)}MB"
        }
}

class FileSearchTool(
    private val workDir: String,
    private val directoryResolver: WorkspaceDirectoryResolver? = null,
) : SimpleTool<FileSearchArgs>(
        argsType = typeToken<FileSearchArgs>(),
        name = "file_search",
        description = "Search for files by name pattern (glob or substring) in a directory tree.",
    ) {
    override suspend fun execute(args: FileSearchArgs): String =
        try {
            val path = projectScopedPath(args.path)
            val results =
                openTraversal(path).use { traversal ->
                    traversal.search(args.pattern, args.maxResults)
                }
            if (results.isEmpty()) {
                "No files matching '${args.pattern}' found."
            } else {
                "Found ${results.size} result(s):\n${results.joinToString("\n")}"
            }
        } catch (e: Exception) {
            "[ERROR] File search failed: ${e.message}"
        }

    private fun openTraversal(path: String): SafeWorkspaceTraversal =
        directoryResolver?.let { SafeWorkspaceTraversal.openAbsolute(it.resolve(path)) }
            ?: SafeWorkspaceTraversal.open(workDir, path)
}

class CodeGrepTool(
    private val workDir: String,
    private val directoryResolver: WorkspaceDirectoryResolver? = null,
) : SimpleTool<CodeGrepArgs>(
        argsType = typeToken<CodeGrepArgs>(),
        name = "code_grep",
        description = "Search file contents for a text pattern (like ripgrep). Returns matching lines with file paths and line numbers.",
    ) {
    override suspend fun execute(args: CodeGrepArgs): String =
        try {
            val path = projectScopedPath(args.path)
            val regex =
                try {
                    Regex(args.pattern, RegexOption.IGNORE_CASE)
                } catch (_: Exception) {
                    Regex(Regex.escape(args.pattern), RegexOption.IGNORE_CASE)
                }
            val includeRegex =
                if (args.include.isNotBlank()) {
                    val glob = args.include.replace(".", "\\.").replace("*", ".*").replace("?", ".")
                    Regex(glob, RegexOption.IGNORE_CASE)
                } else {
                    null
                }
            val binaryExtensions = setOf("png", "jpg", "jpeg", "gif", "zip", "jar", "class", "exe", "dll", "so", "pdf")
            val results =
                openTraversal(path).use { traversal ->
                    traversal.grep(regex, includeRegex, binaryExtensions, args.maxResults)
                }
            if (results.isEmpty()) {
                "No matches for '${args.pattern}'."
            } else {
                "Found ${results.size} match(es):\n${results.joinToString("\n")}"
            }
        } catch (e: Exception) {
            logger.debug(e) { "Secure code search failed" }
            "[ERROR] Code search failed: ${e.message}"
        }

    private fun openTraversal(path: String): SafeWorkspaceTraversal =
        directoryResolver?.let { SafeWorkspaceTraversal.openAbsolute(it.resolve(path)) }
            ?: SafeWorkspaceTraversal.open(workDir, path)
}
