package dev.promethe.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginLoaderSecurityTest {
    @Test
    fun `plugins are disabled unless the local runtime explicitly enables LAB plugins`() {
        val root = Files.createTempDirectory("promethe-plugins-default").toFile()
        createPlugin(root, "sample")

        val disabled = PluginLoader(root.absolutePath).discover().single()
        val enabled = PluginLoader(root.absolutePath, runtimeEnabled = true).discover().single()

        assertFalse(disabled.enabled)
        assertTrue(enabled.enabled)
    }

    @Test
    fun `manifest identity must match its canonical directory`() {
        val root = Files.createTempDirectory("promethe-plugins-name").toFile()
        createPlugin(root, directoryName = "safe", manifestName = "../workspace")
        createPlugin(root, directoryName = "mismatch", manifestName = "other")

        val discovered = PluginLoader(root.absolutePath, runtimeEnabled = true).discover()

        assertTrue(discovered.isEmpty())
    }

    @Test
    fun `plugin assets cannot escape their canonical category directory`() {
        val root = Files.createTempDirectory("promethe-plugins-assets").toFile()
        val plugin = createPlugin(root, "sample", prompts = listOf("safe.md", "../../outside.md"))
        val prompts = plugin.resolve("prompts").also { it.mkdirs() }
        prompts.resolve("safe.md").writeText("safe")
        root.resolve("outside.md").writeText("outside")
        val loader = PluginLoader(root.absolutePath, runtimeEnabled = true)
        loader.discover()

        val fragments = loader.loadPromptFragments()

        assertEquals(listOf("safe"), fragments)
    }

    private fun createPlugin(
        root: java.io.File,
        directoryName: String,
        manifestName: String = directoryName,
        prompts: List<String> = emptyList(),
    ): java.io.File {
        val directory = root.resolve(directoryName).also { it.mkdirs() }
        val encodedPrompts = prompts.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        directory.resolve("plugin.json").writeText(
            """
            {
              "name": "$manifestName",
              "enabled": true,
              "prompts": $encodedPrompts
            }
            """.trimIndent(),
        )
        return directory
    }
}
