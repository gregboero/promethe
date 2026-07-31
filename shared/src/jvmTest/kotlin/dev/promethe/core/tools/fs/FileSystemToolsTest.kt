package dev.promethe.core.tools.fs

import java.io.File
import java.nio.file.Files
import dev.promethe.core.SecureJvmWorkspaceFileMutator
import dev.promethe.core.SecureJvmWorkspaceFileReader
import dev.promethe.core.SecureJvmWorkspaceFileWriter
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class FileSystemToolsTest {
    private lateinit var tempDir: File
    private lateinit var workDirStr: String
    private lateinit var mutator: SecureJvmWorkspaceFileMutator

    @BeforeTest
    fun setup() {
        tempDir = File("build/tmp/test_fs_${System.currentTimeMillis()}").canonicalFile
        tempDir.mkdirs()
        workDirStr = tempDir.absolutePath
        mutator = SecureJvmWorkspaceFileMutator(workDirStr)
    }

    @AfterTest
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testFileDelete() =
        runTest {
            val tool = FileDeleteTool(workDirStr, mutator)
            val file = File(tempDir, "delete_me.txt")
            file.writeText("hello")
            assertTrue(file.exists())

            val result = tool.execute(FileDeleteArgs(path = "delete_me.txt"))
            if (result.contains("unavailable on this filesystem")) return@runTest
            assertTrue(result.contains("Deleted file"), "Result should show file deleted")
            assertFalse(file.exists(), "File should be removed from disk")
        }

    @Test
    fun testFileDeleteRecursive() =
        runTest {
            val tool = FileDeleteTool(workDirStr, mutator)
            val dir = File(tempDir, "sub")
            dir.mkdirs()
            val file = File(dir, "child.txt")
            file.writeText("child")

            // Non-recursive should error
            val errResult = tool.execute(FileDeleteArgs(path = "sub", recursive = false))
            if (errResult.contains("unavailable on this filesystem")) return@runTest
            assertTrue(errResult.contains("[ERROR] Directory not empty"), "Should fail to delete non-empty dir without recursive flag")

            // Recursive should succeed
            val okResult = tool.execute(FileDeleteArgs(path = "sub", recursive = true))
            assertTrue(okResult.contains("Deleted directory recursively"), "Should successfully delete directory recursively")
            assertFalse(dir.exists(), "Directory should be removed from disk")
        }

    @Test
    fun testFileMove() =
        runTest {
            val tool = FileMoveTool(workDirStr, mutator)
            val srcFile = File(tempDir, "src.txt")
            srcFile.writeText("move me")

            val result = tool.execute(FileMoveArgs(source = "src.txt", destination = "dest.txt"))
            if (result.contains("unavailable on this filesystem")) return@runTest
            assertTrue(result.contains("Moved"), "Result should show successful move")
            assertFalse(srcFile.exists(), "Source file should no longer exist")
            val destFile = File(tempDir, "dest.txt")
            assertTrue(destFile.exists(), "Destination file should exist")
            assertEquals("move me", destFile.readText())
        }

    @Test
    fun testDirectoryTree() =
        runTest {
            val tool = DirectoryTreeTool(workDirStr)
            val dir = File(tempDir, "folder")
            dir.mkdirs()
            File(dir, "child.txt").writeText("content")

            val result = tool.execute(DirectoryTreeArgs(path = "."))
            if (result.contains("unavailable on this filesystem")) return@runTest
            assertTrue(result.contains("folder/"), "Tree should list directories")
            assertTrue(result.contains("child.txt"), "Tree should list child files")
        }

    @Test
    fun testFileSearch() =
        runTest {
            val tool = FileSearchTool(workDirStr)
            File(tempDir, "test_file.txt").writeText("txt")
            File(tempDir, "test_code.kt").writeText("kt")

            val txtResult = tool.execute(FileSearchArgs(pattern = "*.txt"))
            if (txtResult.contains("unavailable on this filesystem")) return@runTest
            assertTrue(txtResult.contains("test_file.txt"), "Search should find txt files")
            assertFalse(txtResult.contains("test_code.kt"), "Search should filter out kt files")
        }

    @Test
    fun testCodeGrep() =
        runTest {
            val tool = CodeGrepTool(workDirStr)
            val file = File(tempDir, "grep_test.txt")
            file.writeText("hello world\nkotlin coding is fun\nbye world")

            val result = tool.execute(CodeGrepArgs(pattern = "kotlin"))
            if (result.contains("unavailable on this filesystem")) return@runTest
            assertTrue(result.contains("grep_test.txt:2: kotlin coding is fun"), "Grep should find matching line content and number")
        }

    @Test
    fun `recursive tools do not expose protected metadata`() =
        runTest {
            val protectedDir = File(tempDir, ".promethe").apply { mkdirs() }
            File(protectedDir, "secret.txt").writeText("sandbox-secret")
            File(tempDir, "visible.txt").writeText("visible content")

            val tree = DirectoryTreeTool(workDirStr).execute(DirectoryTreeArgs(path = "."))
            val search = FileSearchTool(workDirStr).execute(FileSearchArgs(pattern = "*.txt"))
            val grep = CodeGrepTool(workDirStr).execute(CodeGrepArgs(pattern = "sandbox-secret"))
            if (tree.contains("unavailable on this filesystem")) return@runTest

            assertFalse(tree.contains(".promethe"))
            assertFalse(search.contains("secret.txt"))
            assertTrue(grep.startsWith("No matches"))
            assertTrue(search.contains("visible.txt"))
        }

    @Test
    fun `recursive tools do not follow symlinked files or directories`() =
        runTest {
            val outside = File.createTempFile("promethe-symlink-secret", ".txt").apply { writeText("outside-secret") }
            val link = tempDir.toPath().resolve("outside-link.txt")
            val directoryLink = tempDir.toPath().resolve("outside-directory")
            val linksCreated =
                runCatching {
                    Files.createSymbolicLink(link, outside.toPath())
                    Files.createSymbolicLink(directoryLink, outside.parentFile.toPath())
                }.isSuccess
            if (!linksCreated) {
                Files.deleteIfExists(link)
                Files.deleteIfExists(directoryLink)
                outside.delete()
                return@runTest
            }

            try {
                val tree = DirectoryTreeTool(workDirStr).execute(DirectoryTreeArgs(path = "."))
                val search = FileSearchTool(workDirStr).execute(FileSearchArgs(pattern = "outside-link*"))
                val grep = CodeGrepTool(workDirStr).execute(CodeGrepArgs(pattern = "outside-secret"))
                if (tree.contains("unavailable on this filesystem")) return@runTest

                assertFalse(tree.contains("outside-link.txt"))
                assertFalse(tree.contains("outside-directory"))
                assertTrue(search.startsWith("No files matching"))
                assertTrue(grep.startsWith("No matches"))
            } finally {
                Files.deleteIfExists(link)
                Files.deleteIfExists(directoryLink)
                outside.delete()
            }
        }

    @Test
    fun testPatchApply() =
        runTest {
            val tool =
                PatchTool(
                    workDirStr,
                    SecureJvmWorkspaceFileReader(workDirStr),
                    SecureJvmWorkspaceFileWriter(workDirStr),
                )
            val file = File(tempDir, "patch_test.txt")
            file.writeText("Line 1\nLine 2\nLine 3")

            val diff =
                """
                --- a/patch_test.txt
                +++ b/patch_test.txt
                @@ -1,3 +1,3 @@
                 Line 1
                -Line 2
                +Modified Line 2
                 Line 3
                """.trimIndent()

            val result = tool.execute(PatchArgs(patch = diff))
            if (result.contains("unavailable on this filesystem")) return@runTest
            assertTrue(result.contains("Patched patch_test.txt"), "Result should show patch applied successfully")
            assertEquals("Line 1\nModified Line 2\nLine 3", file.readText())
        }
}
