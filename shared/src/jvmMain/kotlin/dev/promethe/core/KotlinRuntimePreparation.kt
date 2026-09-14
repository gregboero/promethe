package dev.promethe.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest

/** Trusted files only, never a JVM or a classloader. Caller holds the session's execution lock. */
internal class KotlinRuntimePreparation(
    private val distribution: Path,
    private val javaRuntime: Path,
    private val scratchRoot: Path,
    private val enabled: Boolean,
    private val maxSessions: Int = 4,
) {
    data class Lease(
        val directory: Path,
        val fingerprint: String,
        val retained: Boolean,
    )

    private val sessions = mutableMapOf<String, Lease>()
    private val windows = System.getProperty("os.name").startsWith("Windows")

    init {
        require(maxSessions > 0)
    }

    fun acquire(session: String): Lease {
        Files.createDirectories(scratchRoot)
        val existing = synchronized(sessions) { sessions[session] }
        val expected = try {
            if (enabled) fingerprint(distribution.resolve("lib"), javaRuntime, distribution.resolve("bin/harness-jvm.exe")) else null
        } catch (error: Throwable) {
            clear(session)
            throw error
        }
        if (existing != null) {
            val intact = runCatching { existing.fingerprint == expected && fingerprint(existing.directory.resolve("lib"), existing.directory.resolve("jre"), existing.directory.resolve("harness-jvm.exe")) == expected }.getOrDefault(false)
            if (intact) return existing
            clear(session)
        }
        val directory = Files.createTempDirectory(scratchRoot.toRealPath(), "kotlin-runtime-")
        try {
            val digest = newDigest()
            tree(distribution.resolve("lib"), directory.resolve("lib"), digest, "lib")
            tree(javaRuntime, directory.resolve("jre"), digest, "jre")
            if (windows) {
                val launcher = distribution.resolve("bin/harness-jvm.exe")
                require(Files.isRegularFile(launcher, NOFOLLOW_LINKS))
                val bytes = Files.readAllBytes(launcher)
                digest.update(bytes)
                Files.write(directory.resolve("harness-jvm.exe"), bytes)
            }
            val hash = hex(digest)
            check(expected == null || hash == expected) { "Runtime changed during preparation" }
            return synchronized(sessions) {
                val retained = enabled && sessions.size < maxSessions
                Lease(directory, hash, retained).also { if (retained) sessions[session] = it }
            }
        } catch (error: Throwable) {
            delete(directory)
            throw error
        }
    }

    fun release(
        session: String,
        lease: Lease,
        successful: Boolean,
    ) {
        if (!successful || !lease.retained) {
            synchronized(sessions) { if (sessions[session] == lease) sessions.remove(session) }
            delete(lease.directory)
        }
    }

    fun clear(session: String) {
        synchronized(sessions) { sessions.remove(session) }?.let { delete(it.directory) }
    }

    fun size(): Int = synchronized(sessions) { sessions.size }

    fun sourceFingerprint(): String = fingerprint(distribution.resolve("lib"), javaRuntime, distribution.resolve("bin/harness-jvm.exe"))

    private fun fingerprint(
        lib: Path,
        runtime: Path,
        launcher: Path,
    ): String {
        val digest = newDigest()
        tree(lib, null, digest, "lib")
        tree(runtime, null, digest, "jre")
        if (windows) {
            require(Files.isRegularFile(launcher, NOFOLLOW_LINKS))
            digest.update(Files.readAllBytes(launcher))
        }
        return hex(digest)
    }

    private fun tree(
        from: Path,
        to: Path?,
        digest: MessageDigest,
        label: String,
    ) {
        require(Files.isDirectory(from, NOFOLLOW_LINKS)) { "Runtime root must be a real directory" }
        require(from.toRealPath() == from.toAbsolutePath().normalize()) { "Runtime paths must not traverse links" }
        val entries = mutableListOf<Pair<Path, BasicFileAttributes>>()
        Files.walkFileTree(
            from,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    require(dir.toRealPath() == dir.toAbsolutePath().normalize()) { "Runtime paths must not traverse links" }
                    entries.add(dir to attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    require(attrs.isRegularFile && !attrs.isSymbolicLink) { "Runtime files must be regular files, not links" }
                    entries.add(file to attrs)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        entries.sortedBy { it.first }.forEach { (path, attrs) ->
            val relative = from.relativize(path)
            digest.update((label + "/" + relative.toString() + "\u0000").encodeToByteArray())
            if (attrs.isDirectory) {
                to?.resolve(relative)?.let { Files.createDirectories(it) }
            } else {
                digest.update((attrs.size().toString() + "\u0000").encodeToByteArray())
                DigestInputStream(Files.newInputStream(path), digest).use { input ->
                    if (to != null) Files.copy(input, to.resolve(relative)) else input.transferTo(java.io.OutputStream.nullOutputStream())
                }
            }
        }
    }

    private fun newDigest() = MessageDigest.getInstance("SHA-256").also { it.update("promethe-kotlin-artifact-v1".encodeToByteArray()) }

    private fun hex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it) }

    private fun delete(directory: Path) {
        val root = scratchRoot.toRealPath()
        val target = directory.toAbsolutePath().normalize()
        require(target != root && target.parent == root) { "Cleanup must stay inside the runtime scratch root" }
        if (!Files.exists(target, NOFOLLOW_LINKS)) return

        Files.walkFileTree(
            target,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    // Never recurse through a junction, including one introduced after staging.
                    if (dir.toRealPath() != dir.toAbsolutePath().normalize()) {
                        Files.delete(dir)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: java.io.IOException?,
                ): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
