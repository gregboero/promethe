package dev.promethe.core.tools.fs

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.SecureDeletedEntry
import dev.promethe.core.SecureJvmWorkspaceFileMutator
import dev.promethe.core.WorkspacePathPolicy
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
    private val root: SecureDirectoryStream<Path>,
    private val openedStreams: List<DirectoryStream<Path>>,
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

    fun snapshot(maxDepth: Int): List<Node> = snapshot(root, Path.of(""), 0, maxDepth.coerceAtLeast(0), protectRootMetadata)

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
        visit(root, Path.of(""), protectRootMetadata) { _, name, relative, attributes, child ->
            if (results.size >= maxResults.coerceAtLeast(0)) return@visit false
            val matches = regex?.matches(name) ?: name.lowercase().contains(loweredPattern)
            if (matches) results += relative + if (attributes.isDirectory) "/" else ""
            child
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
        visit(root, Path.of(""), protectRootMetadata) { directory, name, relative, attributes, child ->
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
                        .useLines { lines ->
                            lines.forEachIndexed lineLoop@{ index, line ->
                                if (results.size >= maxResults.coerceAtLeast(0)) return@lineLoop
                                if (regex.containsMatchIn(line)) {
                                    results += "$relative:${index + 1}: ${line.take(200)}"
                                }
                            }
                        }
                }
            }
            child
        }
        return results
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
            val relative = workspace.relativize(requested)
            val opened = mutableListOf<DirectoryStream<Path>>()
            val workspaceStream = Files.newDirectoryStream(workspace)
            opened += workspaceStream
            try {
                @Suppress("UNCHECKED_CAST")
                var current =
                    workspaceStream as? SecureDirectoryStream<Path>
                        ?: error("secure workspace traversal is unavailable on this filesystem")
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

class FileDeleteTool(
    private val workDir: String,
    private val mutator: SecureJvmWorkspaceFileMutator?,
) : SimpleTool<FileDeleteArgs>(
        argsType = typeToken<FileDeleteArgs>(),
        name = "file_delete",
        description = "Delete a file or directory. Use recursive=true for non-empty directories.",
    ) {
    override suspend fun execute(args: FileDeleteArgs): String {
        val secureMutator =
            mutator
                ?: return "[ERROR] Secure workspace mutations are unavailable"
        return try {
            when (secureMutator.delete(args.path, args.recursive)) {
                SecureDeletedEntry.FILE -> "Deleted file: ${args.path}"
                SecureDeletedEntry.EMPTY_DIRECTORY -> "Deleted empty directory: ${args.path}"
                SecureDeletedEntry.RECURSIVE_DIRECTORY -> "Deleted directory recursively: ${args.path}"
            }
        } catch (_: DirectoryNotEmptyException) {
            "[ERROR] Directory not empty: ${args.path}"
        } catch (e: Exception) {
            "[ERROR] Delete failed: ${e.message}"
        }
    }
}

class FileMoveTool(
    private val workDir: String,
    private val mutator: SecureJvmWorkspaceFileMutator?,
) : SimpleTool<FileMoveArgs>(
        argsType = typeToken<FileMoveArgs>(),
        name = "file_move",
        description = "Move or rename a file or directory.",
    ) {
    override suspend fun execute(args: FileMoveArgs): String {
        val secureMutator =
            mutator
                ?: return "[ERROR] Secure workspace mutations are unavailable"
        return try {
            secureMutator.move(args.source, args.destination)
            "Moved ${args.source} → ${args.destination}"
        } catch (e: Exception) {
            "[ERROR] Move failed: ${e.message}"
        }
    }
}

class DirectoryTreeTool(
    private val workDir: String,
) : SimpleTool<DirectoryTreeArgs>(
        argsType = typeToken<DirectoryTreeArgs>(),
        name = "directory_tree",
        description = "Show directory tree structure with configurable depth.",
    ) {
    override suspend fun execute(args: DirectoryTreeArgs): String =
        try {
            SafeWorkspaceTraversal.open(workDir, args.path).use { traversal ->
                val output = StringBuilder().appendLine(traversal.displayName + "/")
                renderNodes(traversal.snapshot(args.maxDepth), "", output)
                output.toString()
            }
        } catch (e: Exception) {
            "[ERROR] Directory traversal failed: ${e.message}"
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
) : SimpleTool<FileSearchArgs>(
        argsType = typeToken<FileSearchArgs>(),
        name = "file_search",
        description = "Search for files by name pattern (glob or substring) in a directory tree.",
    ) {
    override suspend fun execute(args: FileSearchArgs): String =
        try {
            val results =
                SafeWorkspaceTraversal.open(workDir, args.path).use { traversal ->
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
}

class CodeGrepTool(
    private val workDir: String,
) : SimpleTool<CodeGrepArgs>(
        argsType = typeToken<CodeGrepArgs>(),
        name = "code_grep",
        description = "Search file contents for a text pattern (like ripgrep). Returns matching lines with file paths and line numbers.",
    ) {
    override suspend fun execute(args: CodeGrepArgs): String =
        try {
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
                SafeWorkspaceTraversal.open(workDir, args.path).use { traversal ->
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
}
