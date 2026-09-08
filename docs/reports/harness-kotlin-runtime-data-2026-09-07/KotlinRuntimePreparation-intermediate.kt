package dev.promethe.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
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
        Files.walk(from).use { paths ->
            paths.sorted().forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Runtime distribution must not contain symbolic links" }
                require(path.toRealPath() == path.toAbsolutePath().normalize()) { "Runtime paths must not traverse links" }
                val relative = from.relativize(path)
                digest.update((label + "/" + relative.toString() + "\u0000").encodeToByteArray())
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    to?.resolve(relative)?.let { Files.createDirectories(it) }
                } else {
                    require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                    digest.update((Files.size(path).toString() + "\u0000").encodeToByteArray())
                    DigestInputStream(Files.newInputStream(path), digest).use { input ->
                        if (to != null) Files.copy(input, to.resolve(relative)) else input.transferTo(java.io.OutputStream.nullOutputStream())
                    }
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

        fun remove(path: Path) {
            // Never recurse through a junction/symlink, including one introduced after staging.
            if (Files.isDirectory(path, NOFOLLOW_LINKS) && path.toRealPath() == path.toAbsolutePath().normalize()) {
                Files.newDirectoryStream(path).use { children -> children.forEach(::remove) }
            }
            Files.deleteIfExists(path)
        }
        remove(target)
    }
}
