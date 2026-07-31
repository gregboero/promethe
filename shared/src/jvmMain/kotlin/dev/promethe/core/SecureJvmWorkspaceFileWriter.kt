package dev.promethe.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.util.UUID

class SecureJvmWorkspaceFileWriter(
    workspaceRoot: String,
) : WorkspaceFileWriter {
    private val workspaceRoot = Path.of(workspaceRoot).toRealPath()

    override suspend fun write(
        relativePath: String,
        content: String,
    ) = withContext(Dispatchers.IO) {
        val relative = validateSecureRelativePath(relativePath)
        val openedStreams = mutableListOf<DirectoryStream<Path>>()
        val rootStream = Files.newDirectoryStream(workspaceRoot)
        openedStreams += rootStream
        try {
            @Suppress("UNCHECKED_CAST")
            var current =
                rootStream as? SecureDirectoryStream<Path>
                    ?: error("secure workspace writes are unavailable on this filesystem")

            for (index in 0 until relative.nameCount - 1) {
                val next = current.newDirectoryStream(relative.getName(index), LinkOption.NOFOLLOW_LINKS)
                openedStreams += next
                current = next
            }

            val targetName = relative.fileName
            val temporaryName = Path.of(".promethe-write-${UUID.randomUUID()}.tmp")
            var temporaryCreated = false
            try {
                val options =
                    setOf<OpenOption>(
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                current.newByteChannel(temporaryName, options).use { channel ->
                    temporaryCreated = true
                    val bytes = content.encodeToByteArray()
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) {
                        channel.write(buffer)
                    }
                }

                try {
                    current.deleteFile(targetName)
                } catch (_: NoSuchFileException) {
                    // A missing destination is the normal create-file path.
                }
                current.move(temporaryName, current, targetName)
                temporaryCreated = false
            } finally {
                if (temporaryCreated) {
                    runCatching { current.deleteFile(temporaryName) }
                }
            }
        } finally {
            openedStreams.asReversed().forEach { stream -> runCatching { stream.close() } }
        }
    }
}

class SecureJvmWorkspaceFileReader(
    workspaceRoot: String,
) : WorkspaceFileReader {
    private val workspaceRoot = Path.of(workspaceRoot).toRealPath()

    override suspend fun read(relativePath: String): String =
        withContext(Dispatchers.IO) {
            val relative = validateSecureRelativePath(relativePath)
            val openedStreams = mutableListOf<DirectoryStream<Path>>()
            val rootStream = Files.newDirectoryStream(workspaceRoot)
            openedStreams += rootStream
            try {
                @Suppress("UNCHECKED_CAST")
                var current =
                    rootStream as? SecureDirectoryStream<Path>
                        ?: error("secure workspace reads are unavailable on this filesystem")

                for (index in 0 until relative.nameCount - 1) {
                    val next = current.newDirectoryStream(relative.getName(index), LinkOption.NOFOLLOW_LINKS)
                    openedStreams += next
                    current = next
                }

                val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                current.newByteChannel(relative.fileName, options).use { channel ->
                    Channels.newReader(channel, Charsets.UTF_8).use { reader -> reader.readText() }
                }
            } finally {
                openedStreams.asReversed().forEach { stream -> runCatching { stream.close() } }
            }
        }
}

enum class SecureDeletedEntry {
    FILE,
    EMPTY_DIRECTORY,
    RECURSIVE_DIRECTORY,
}

class SecureJvmWorkspaceFileMutator(
    workspaceRoot: String,
) {
    private val workspaceRoot = Path.of(workspaceRoot).toRealPath()

    suspend fun delete(
        relativePath: String,
        recursive: Boolean,
    ): SecureDeletedEntry =
        withContext(Dispatchers.IO) {
            val relative = validateSecureRelativePath(relativePath)
            openSecureParent(workspaceRoot, relative).use { parent ->
                val name = relative.fileName
                val attributes = parent.attributes(name)
                if (!attributes.isDirectory) {
                    parent.stream.deleteFile(name)
                    return@withContext SecureDeletedEntry.FILE
                }
                parent.stream.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { directory ->
                    if (!recursive && directory.iterator().hasNext()) {
                        throw DirectoryNotEmptyException(relativePath)
                    }
                    if (recursive) {
                        deleteDirectoryContents(directory)
                    }
                }
                parent.stream.deleteDirectory(name)
                if (recursive) SecureDeletedEntry.RECURSIVE_DIRECTORY else SecureDeletedEntry.EMPTY_DIRECTORY
            }
        }

    suspend fun move(
        sourcePath: String,
        destinationPath: String,
    ) = withContext(Dispatchers.IO) {
        val source = validateSecureRelativePath(sourcePath)
        val destination = validateSecureRelativePath(destinationPath)
        openSecureParent(workspaceRoot, source).use { sourceParent ->
            openSecureParent(workspaceRoot, destination).use { destinationParent ->
                sourceParent.attributes(source.fileName)
                sourceParent.stream.move(
                    source.fileName,
                    destinationParent.stream,
                    destination.fileName,
                )
            }
        }
    }

    private fun deleteDirectoryContents(directory: SecureDirectoryStream<Path>) {
        directory.forEach { entry ->
            val name = entry.fileName
            val attributes = directory.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
            if (attributes.isDirectory) {
                directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { child ->
                    deleteDirectoryContents(child)
                }
                directory.deleteDirectory(name)
            } else {
                directory.deleteFile(name)
            }
        }
    }
}

private class OpenSecureParent(
    val stream: SecureDirectoryStream<Path>,
    private val openedStreams: List<DirectoryStream<Path>>,
) : AutoCloseable {
    fun attributes(name: Path) =
        stream
            .getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            .readAttributes()

    override fun close() {
        openedStreams.asReversed().forEach { opened -> runCatching { opened.close() } }
    }
}

private fun openSecureParent(
    workspaceRoot: Path,
    relative: Path,
): OpenSecureParent {
    val openedStreams = mutableListOf<DirectoryStream<Path>>()
    val rootStream = Files.newDirectoryStream(workspaceRoot)
    openedStreams += rootStream
    try {
        @Suppress("UNCHECKED_CAST")
        var current =
            rootStream as? SecureDirectoryStream<Path>
                ?: error("secure workspace mutations are unavailable on this filesystem")
        for (index in 0 until relative.nameCount - 1) {
            val next = current.newDirectoryStream(relative.getName(index), LinkOption.NOFOLLOW_LINKS)
            openedStreams += next
            current = next
        }
        return OpenSecureParent(current, openedStreams)
    } catch (error: Exception) {
        openedStreams.asReversed().forEach { opened -> runCatching { opened.close() } }
        throw error
    }
}

private fun validateSecureRelativePath(value: String): Path {
    require(value.isNotBlank()) { "workspace path is required" }
    val relative = Path.of(value).normalize()
    require(!relative.isAbsolute && relative.nameCount > 0 && relative.toString().isNotBlank()) {
        "workspace path must be relative"
    }
    require(relative.none { segment -> segment.toString() == ".." }) { "parent traversal is forbidden" }
    require(relative.first().toString().lowercase() !in WorkspacePathPolicy.protectedNames) {
        "protected workspace metadata is not accessible"
    }
    return relative
}
