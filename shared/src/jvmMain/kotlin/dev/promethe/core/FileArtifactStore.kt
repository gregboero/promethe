package dev.promethe.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class ArtifactIntegrityException(
    message: String,
) : IllegalStateException(message)

class ArtifactQuotaException(
    message: String,
) : IllegalStateException(message)

data class ArtifactRetentionCandidate(
    val hash: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)

class FileArtifactStore(
    root: Path,
    private val maxArtifactBytes: Int = DEFAULT_MAX_ARTIFACT_BYTES,
    private val maxStoreBytes: Long = DEFAULT_MAX_STORE_BYTES,
) : ArtifactStore {
    private val root = root.toAbsolutePath().normalize()
    private val canonicalRoot: Path
    private val writeMutex = Mutex()

    init {
        require(maxArtifactBytes > 0) { "Maximum artifact size must be positive" }
        require(maxStoreBytes > 0) { "Maximum store size must be positive" }
        Files.createDirectories(this.root)
        canonicalRoot = this.root.toRealPath()
    }

    override suspend fun put(request: ArtifactWriteRequest): ArtifactReference =
        withContext(Dispatchers.IO) {
            require(request.content.size <= maxArtifactBytes) {
                "Artifact exceeds the $maxArtifactBytes byte storage limit"
            }
            require(request.mediaType.isNotBlank()) { "Artifact media type must not be blank" }
            val hash = sha256(request.content)
            writeMutex.withLock {
                withStoreLock {
                    val target = pathFor(hash)
                    Files.createDirectories(target.parent)
                    requireContainedDirectory(target.parent)
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        verifyExisting(target, hash)
                    } else {
                        val used = inventory().sumOf { it.sizeBytes }
                        if (request.content.size.toLong() > maxStoreBytes - used) {
                            throw ArtifactQuotaException("Artifact store quota of $maxStoreBytes bytes exhausted; review retention before retrying")
                        }
                        writeAtomically(target, request.content, hash)
                    }
                }
            }
            ArtifactReference(
                hash = hash,
                uri = "artifact://sha256/$hash",
                sizeBytes = request.content.size.toLong(),
                mediaType = request.mediaType,
            )
        }

    override suspend fun read(hash: String): ByteArray? =
        withContext(Dispatchers.IO) {
            require(ARTIFACT_HASH.matches(hash)) { "Artifact hash must be 64 lowercase hexadecimal characters" }
            val target = pathFor(hash)
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return@withContext null
            requireContainedDirectory(target.parent)
            requireRegularBoundedArtifact(target)
            val content = Files.readAllBytes(target)
            if (sha256(content) != hash) throw ArtifactIntegrityException("Artifact content does not match its SHA-256 address")
            content
        }

    private fun pathFor(hash: String): Path = root.resolve("sha256").resolve(hash.take(2)).resolve(hash)

    /** Read-only plan: callers must supply all ledger references. Nothing is deleted automatically. */
    suspend fun retentionCandidates(
        referencedHashes: Set<String>,
        olderThanMillis: Long,
    ): List<ArtifactRetentionCandidate> =
        withContext(Dispatchers.IO) {
            require(referencedHashes.all { ARTIFACT_HASH.matches(it) }) { "Invalid protected artifact hash" }
            writeMutex.withLock {
                withStoreLock {
                    inventory().filter { it.hash !in referencedHashes && it.modifiedAtMillis < olderThanMillis }
                        .sortedBy { it.modifiedAtMillis }
                }
            }
        }

    private fun inventory(): List<ArtifactRetentionCandidate> {
        val directory = root.resolve("sha256")
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        requireContainedDirectory(directory)
        return Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map { path ->
                    requireContainedDirectory(path.parent)
                    ArtifactRetentionCandidate(path.fileName.toString(), Files.size(path), Files.getLastModifiedTime(path).toMillis())
                }.toList()
        }
    }

    private suspend fun <T> withStoreLock(block: () -> T): T {
        val lockPath = root.resolve(".store.lock")
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            return withTimeout(10_000) {
                var lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                while (lock == null) {
                    delay(10)
                    lock = try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                }
                lock.use { block() }
            }
        }
    }

    private fun verifyExisting(
        target: Path,
        expectedHash: String,
    ) {
        requireRegularBoundedArtifact(target)
        val existing = Files.readAllBytes(target)
        if (sha256(existing) != expectedHash) {
            throw ArtifactIntegrityException("Existing artifact content does not match its SHA-256 address")
        }
    }

    private fun requireContainedDirectory(directory: Path) {
        if (directory.toRealPath() != canonicalRoot.resolve(root.relativize(directory))) {
            throw ArtifactIntegrityException("Artifact directory is redirected or escapes the configured store")
        }
    }

    private fun requireRegularBoundedArtifact(target: Path) {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw ArtifactIntegrityException("Artifact address is not a regular file")
        }
        if (Files.size(target) > maxArtifactBytes) {
            throw ArtifactIntegrityException("Artifact exceeds the configured size limit")
        }
    }

    private fun writeAtomically(
        target: Path,
        content: ByteArray,
        expectedHash: String,
    ) {
        val temporary = Files.createTempFile(target.parent, ".artifact-", ".tmp")
        try {
            Files.write(temporary, content)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target)
            } catch (_: FileAlreadyExistsException) {
                verifyExisting(target, expectedHash)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun sha256(content: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(content)
            .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val DEFAULT_MAX_ARTIFACT_BYTES = 16 * 1024 * 1024
        private const val DEFAULT_MAX_STORE_BYTES = 256L * 1024 * 1024
    }
}
