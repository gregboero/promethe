package dev.promethe.core

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileArtifactStoreTest {
    @Test
    fun `content is addressed by sha256 and deduplicated`() =
        runTest {
            val root = createTempDirectory("promethe-artifact-store")
            try {
                val store = FileArtifactStore(root)
                val request = writeRequest("durable output")

                val first = store.put(request)
                val second = store.put(request)

                assertEquals(first, second)
                assertTrue(first.uri.endsWith(first.hash))
                assertContentEquals(request.content, store.read(first.hash))
                assertEquals(1, Files.walk(root.resolve("sha256")).use { paths -> paths.filter(Files::isRegularFile).count() })
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `quota survives reopening and deduplication does not consume space`() =
        runTest {
            val root = createTempDirectory("promethe-artifact-quota")
            try {
                val first = FileArtifactStore(root, maxStoreBytes = 4).put(writeRequest("1234"))
                val reopened = FileArtifactStore(root, maxStoreBytes = 4)
                assertEquals(first, reopened.put(writeRequest("1234")))
                assertFailsWith<ArtifactQuotaException> { reopened.put(writeRequest("5")) }
                assertTrue(reopened.retentionCandidates(setOf(first.hash), Long.MAX_VALUE).isEmpty())
                assertEquals(listOf(first.hash), reopened.retentionCandidates(emptySet(), Long.MAX_VALUE).map { it.hash })
                assertContentEquals("1234".encodeToByteArray(), reopened.read(first.hash), "Planning never deletes")
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `concurrent store instances cannot exceed shared quota`() =
        runTest {
            val root = createTempDirectory("promethe-artifact-race")
            try {
                val results = (1..8).map { index ->
                    async {
                        runCatching { FileArtifactStore(root, maxStoreBytes = 4).put(writeRequest("$index$index$index$index")) }
                    }
                }.awaitAll()
                assertEquals(1, results.count { it.isSuccess })
                assertEquals(7, results.count { it.exceptionOrNull() is ArtifactQuotaException })
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `tampered artifacts fail integrity verification`() =
        runTest {
            val root = createTempDirectory("promethe-artifact-tamper")
            try {
                val store = FileArtifactStore(root)
                val reference = store.put(writeRequest("original"))
                val stored = root.resolve("sha256").resolve(reference.hash.take(2)).resolve(reference.hash)
                Files.writeString(stored, "tampered")

                assertFailsWith<ArtifactIntegrityException> { store.read(reference.hash) }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `invalid hashes and oversized artifacts are rejected`() =
        runTest {
            val root = createTempDirectory("promethe-artifact-limits")
            try {
                val store = FileArtifactStore(root, maxArtifactBytes = 4)

                assertFailsWith<IllegalArgumentException> { store.read("../escape") }
                assertFailsWith<IllegalArgumentException> { store.put(writeRequest("12345")) }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `symbolic artifact addresses are never followed`() =
        runTest {
            val root = createTempDirectory("promethe-artifact-symlink")
            try {
                val store = FileArtifactStore(root)
                val reference = store.put(writeRequest("original"))
                val stored = root.resolve("sha256").resolve(reference.hash.take(2)).resolve(reference.hash)
                val outside = Files.createTempFile("promethe-artifact-outside", ".txt")
                Files.delete(stored)
                try {
                    if (runCatching { Files.createSymbolicLink(stored, outside) }.isSuccess) {
                        assertFailsWith<ArtifactIntegrityException> { store.read(reference.hash) }
                    }
                } finally {
                    Files.deleteIfExists(outside)
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    private fun writeRequest(content: String) =
        ArtifactWriteRequest(
            content = content.encodeToByteArray(),
            mediaType = "text/plain; charset=utf-8",
            kind = "tool-output",
        )
}
