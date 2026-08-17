package dev.promethe.core

import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Handle-relative traversal is not implemented by the JDK Windows provider.
 * This fallback validates every path component without following links, rejects
 * reparse-like entries, and revalidates identities around each operation.
 */
internal object CanonicalNioWorkspaceAccess {
    data class Entry(
        val path: Path,
        val name: String,
        val attributes: BasicFileAttributes,
    )

    fun read(
        workspaceRoot: Path,
        relativePath: String,
    ): String {
        val root = workspaceRoot.toRealPath()
        val target = resolveExisting(root, validateRelative(relativePath), expectDirectory = false)
        val before = attributes(target)
        require(before.isRegularFile) { "workspace path is not a regular file" }
        val content =
            java.nio.channels.FileChannel.open(target, READ, NOFOLLOW_LINKS).use { channel ->
                Channels.newReader(channel, Charsets.UTF_8).use { reader -> reader.readText() }
            }
        requireSameIdentity(before, attributes(target))
        return content
    }

    fun write(
        workspaceRoot: Path,
        relativePath: String,
        content: String,
    ) {
        val root = workspaceRoot.toRealPath()
        val relative = validateRelative(relativePath)
        val parentRelative = relative.parent ?: Path.of("")
        val parent = resolveExisting(root, parentRelative, expectDirectory = true)
        val target = parent.resolve(relative.fileName).normalize()
        require(target.startsWith(root)) { "workspace path escapes the workspace" }
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            require(attributes(target).isRegularFile) { "workspace destination is not a regular file" }
        }

        val temporary = parent.resolve(".promethe-write-${UUID.randomUUID()}.tmp")
        var temporaryCreated = false
        try {
            val options = setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS)
            java.nio.channels.FileChannel.open(temporary, options).use { channel ->
                temporaryCreated = true
                val buffer = ByteBuffer.wrap(content.encodeToByteArray())
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            resolveExisting(root, parentRelative, expectDirectory = true)
            try {
                Files.move(temporary, target, ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                error("atomic workspace writes are unavailable on this filesystem")
            }
            temporaryCreated = false
            require(attributes(target).isRegularFile) { "workspace destination changed during write" }
        } finally {
            if (temporaryCreated) runCatching { Files.deleteIfExists(temporary) }
        }
    }

    fun listDirectory(
        workspaceRoot: Path,
        requestedPath: String,
    ): Pair<Path, List<Entry>> {
        val root = workspaceRoot.toRealPath()
        val directory = resolveExisting(root, validateRelative(requestedPath, allowRoot = true), expectDirectory = true)
        val before = attributes(directory)
        val entries =
            Files.newDirectoryStream(directory).use { stream ->
                stream.mapNotNull { candidate ->
                    val name = candidate.fileName.toString()
                    val candidateAttributes = runCatching { attributes(candidate) }.getOrNull() ?: return@mapNotNull null
                    if (!candidateAttributes.isDirectory && !candidateAttributes.isRegularFile) return@mapNotNull null
                    Entry(candidate, name, candidateAttributes)
                }.sortedBy(Entry::name)
            }
        requireSameIdentity(before, attributes(directory))
        return directory to entries
    }

    fun delete(
        workspaceRoot: Path,
        relativePath: String,
        recursive: Boolean,
    ): SecureDeletedEntry {
        val root = workspaceRoot.toRealPath()
        val target = resolveExisting(root, validateRelative(relativePath), expectDirectory = null)
        val targetAttributes = attributes(target)
        if (targetAttributes.isRegularFile) {
            Files.delete(target)
            return SecureDeletedEntry.FILE
        }
        require(targetAttributes.isDirectory) { "workspace path is not a file or directory" }
        if (!recursive && Files.newDirectoryStream(target).use { it.iterator().hasNext() }) {
            throw DirectoryNotEmptyException(relativePath)
        }
        if (recursive) deleteDirectoryContents(root, target)
        Files.delete(target)
        return if (recursive) SecureDeletedEntry.RECURSIVE_DIRECTORY else SecureDeletedEntry.EMPTY_DIRECTORY
    }

    fun move(
        workspaceRoot: Path,
        sourcePath: String,
        destinationPath: String,
    ) {
        val root = workspaceRoot.toRealPath()
        val source = resolveExisting(root, validateRelative(sourcePath), expectDirectory = null)
        val destinationRelative = validateRelative(destinationPath)
        val destinationParent =
            resolveExisting(root, destinationRelative.parent ?: Path.of(""), expectDirectory = true)
        val destination = destinationParent.resolve(destinationRelative.fileName).normalize()
        require(destination.startsWith(root)) { "workspace destination escapes the workspace" }
        require(!Files.exists(destination, NOFOLLOW_LINKS)) { "workspace destination already exists" }
        try {
            Files.move(source, destination, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            error("atomic workspace moves are unavailable on this filesystem")
        }
        attributes(destination)
    }

    private fun deleteDirectoryContents(
        root: Path,
        directory: Path,
    ) {
        val (_, entries) = listDirectory(root, root.relativize(directory).toString())
        entries.forEach { entry ->
            if (entry.attributes.isDirectory) {
                deleteDirectoryContents(root, entry.path)
            }
            Files.delete(entry.path)
        }
    }

    private fun resolveExisting(
        root: Path,
        relative: Path,
        expectDirectory: Boolean?,
    ): Path {
        var current = root
        relative.forEachIndexed { index, segment ->
            current = current.resolve(segment).normalize()
            require(current.startsWith(root)) { "workspace path escapes the workspace" }
            val currentAttributes = attributes(current)
            if (index < relative.nameCount - 1) {
                require(currentAttributes.isDirectory) { "workspace parent is not a directory" }
            }
        }
        val finalAttributes = attributes(current)
        when (expectDirectory) {
            true -> require(finalAttributes.isDirectory) { "workspace path is not a directory" }

            false -> require(finalAttributes.isRegularFile) { "workspace path is not a regular file" }

            null -> require(finalAttributes.isDirectory || finalAttributes.isRegularFile) {
                "workspace path is not a file or directory"
            }
        }
        val real = current.toRealPath()
        require(real.startsWith(root)) { "workspace path resolves outside the workspace" }
        return current
    }

    private fun attributes(path: Path): BasicFileAttributes {
        val result = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(!result.isSymbolicLink && !result.isOther) { "links and reparse points are forbidden" }
        return result
    }

    private fun requireSameIdentity(
        before: BasicFileAttributes,
        after: BasicFileAttributes,
    ) {
        val beforeKey = before.fileKey()
        val afterKey = after.fileKey()
        if (beforeKey != null && afterKey != null) {
            require(beforeKey == afterKey) { "workspace path changed during access" }
        }
    }

    private fun validateRelative(
        value: String,
        allowRoot: Boolean = false,
    ): Path {
        require(value.isNotBlank()) { "workspace path is required" }
        val relative = Path.of(value).normalize()
        val rootRequest = value == "." || relative.toString().isBlank()
        require(!relative.isAbsolute) { "workspace path must be relative" }
        if (rootRequest) {
            require(allowRoot) { "workspace path must identify a file or directory" }
            return Path.of("")
        }
        require(relative.nameCount > 0) {
            "workspace path must be relative"
        }
        val segments = relative.map(Path::toString)
        require(segments.none { it == ".." || it == "." || it.isBlank() }) { "parent traversal is forbidden" }
        require(segments.firstOrNull()?.lowercase() !in WorkspacePathPolicy.protectedNames) {
            "protected workspace metadata is not accessible"
        }
        if (isWindows()) segments.forEach(::validateWindowsSegment)
        return relative
    }

    private fun validateWindowsSegment(segment: String) {
        require(':' !in segment && !segment.endsWith(' ') && !segment.endsWith('.')) {
            "ambiguous Windows workspace path is forbidden"
        }
        val baseName = segment.substringBefore('.').uppercase()
        require(baseName !in WINDOWS_RESERVED_NAMES && !WINDOWS_RESERVED_PORT.matches(baseName)) {
            "reserved Windows workspace path is forbidden"
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").contains("win", ignoreCase = true)

    private val WINDOWS_RESERVED_NAMES = setOf("CON", "PRN", "AUX", "NUL", "CLOCK$")
    private val WINDOWS_RESERVED_PORT = Regex("(?:COM|LPT)[1-9]")
}
