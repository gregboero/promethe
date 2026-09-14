package dev.promethe.gateway.voice

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import dev.promethe.core.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolSchemaExporterSecurityTest {
    @AfterTest
    fun cleanup() =
        runTest {
            ToolRegistry.clear()
        }

    @Test
    fun `voice registry export excludes effectful and unknown tools`() =
        runTest {
            ToolRegistry.clear()
            ToolRegistry.register(TestTool("read_file"))
            ToolRegistry.register(TestTool("shell"))
            ToolRegistry.register(TestTool("unknown_voice_tool"))

            val names = ToolSchemaExporter.exportFromRegistry().map { it.name }

            assertEquals(listOf("read_file"), names)
        }

    @Serializable
    private class TestArgs

    private class TestTool(
        name: String,
    ) : SimpleTool<TestArgs>(
            argsType = typeToken<TestArgs>(),
            name = name,
            description = "Policy export test",
        ) {
        override suspend fun execute(args: TestArgs): String = "unused"
    }
}
