package dev.promethe.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class HarnessProbePromptTest {
    @Test fun `capture uses one fake model call with no tool execution or personal profile`() =
        runTest {
            val directory = Files.createTempDirectory("probe-prompt")
            try {
                val (prompt, history) = captureHarnessProbePrompt(directory, "Read eight pages")
                assertEquals(listOf("user" to "Read eight pages"), history)
                assertTrue(prompt.contains("json_query"))
                assertFalse(prompt.contains("harness_inspect"))
                assertTrue(ToolRegistry.listTools().isEmpty())
            } finally {
                check(directory.toAbsolutePath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()))
                directory.toFile().deleteRecursively()
            }
        }
}
