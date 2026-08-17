package dev.promethe.gateway

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class WebUiDirectoryResolverTest {
    @Test
    fun `desktop run resolves assets without duplicating composeApp`() {
        val root = Files.createTempDirectory("promethe-web-root").toFile()
        val composeModule = File(root, "composeApp").apply { mkdirs() }

        val resolved = resolveWebUiDirectory(null, composeModule)

        assertEquals(
            File(composeModule, "build/dist/wasmJs/productionExecutable").canonicalFile,
            resolved,
        )
    }

    @Test
    fun `repository run resolves assets inside composeApp module`() {
        val root = Files.createTempDirectory("promethe-web-root").toFile()
        val composeModule = File(root, "composeApp").apply { mkdirs() }

        val resolved = resolveWebUiDirectory(null, root)

        assertEquals(
            File(composeModule, "build/dist/wasmJs/productionExecutable").canonicalFile,
            resolved,
        )
    }

    @Test
    fun `configured relative path resolves from working directory`() {
        val root = Files.createTempDirectory("promethe-web-root").toFile()

        val resolved = resolveWebUiDirectory("custom/web", root)

        assertEquals(File(root, "custom/web").canonicalFile, resolved)
    }
}
