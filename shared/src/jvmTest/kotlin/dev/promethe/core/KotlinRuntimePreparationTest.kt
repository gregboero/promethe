package dev.promethe.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class KotlinRuntimePreparationTest {
    private fun fixture(block: (Path, KotlinRuntimePreparation) -> Unit) {
        val root = Files.createTempDirectory("runtime-preparation-test")
        try {
            Files.writeString(Files.createDirectories(root.resolve("dist/lib")).resolve("worker.jar"), "original")
            Files.writeString(Files.createDirectories(root.resolve("dist/bin")).resolve("harness-jvm.exe"), "launcher")
            Files.writeString(Files.createDirectories(root.resolve("jre/bin")).resolve("java.exe"), "java")
            block(root, KotlinRuntimePreparation(root.resolve("dist"), root.resolve("jre"), root.resolve("scratch"), true, maxSessions = 1))
        } finally {
            check(root.toAbsolutePath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()))
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `reuse checks bytes in both source and prepared files`() =
        fixture { root, preparation ->
            val first = preparation.acquire("one")
            assertTrue(first.retained)
            assertEquals(first, preparation.acquire("one"))
            val original = root.resolve("dist/lib/worker.jar")
            val timestamp = Files.getLastModifiedTime(original)
            Files.writeString(original, "modified") // Same size and timestamp must still invalidate.
            Files.setLastModifiedTime(original, timestamp)
            val second = preparation.acquire("one")
            assertNotEquals(first.fingerprint, second.fingerprint)
            assertFalse(Files.exists(first.directory))
            assertEquals("modified", Files.readString(second.directory.resolve("lib/worker.jar")))
            Files.writeString(second.directory.resolve("lib/worker.jar"), "tampered")
            val third = preparation.acquire("one")
            assertNotEquals(second.directory, third.directory)
            assertEquals(second.fingerprint, third.fingerprint)
            assertEquals("modified", Files.readString(third.directory.resolve("lib/worker.jar")))
            assertFalse(Files.exists(second.directory))
            preparation.clear("one")
            assertEquals(0, preparation.size())
            Files.list(root.resolve("scratch")).use { assertEquals(0L, it.count()) }
        }

    @Test fun `capacity never evicts an active session and overflow remains disposable`() =
        fixture { _, preparation ->
            val first = preparation.acquire("one")
            val overflow = preparation.acquire("two")
            assertFalse(overflow.retained)
            assertNotEquals(first.directory, overflow.directory)
            preparation.release("two", overflow, true)
            assertFalse(Files.exists(overflow.directory))
            assertEquals(first, preparation.acquire("one"))
            preparation.release("one", first, false)
            assertFalse(Files.exists(first.directory))
            assertEquals(0, preparation.size())
        }

    @Test fun `unreadable source discards its prior prepared snapshot`() =
        fixture { root, preparation ->
            val first = preparation.acquire("one")
            Files.delete(root.resolve("dist/lib/worker.jar"))
            Files.delete(root.resolve("dist/lib"))
            assertFailsWith<IllegalArgumentException> { preparation.acquire("one") }
            assertEquals(0, preparation.size())
            assertFalse(Files.exists(first.directory))
        }
}
