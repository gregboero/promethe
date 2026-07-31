package dev.promethe.core.tools.builtin

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RenderUIToolTest {
    private val tool = RenderUITool()

    @Test
    fun `render_ui tool has correct name`() {
        assertEquals("render_ui", tool.name)
    }

    @Test
    fun `execute with simple tree returns a2ui markers`() =
        runTest {
            val result = tool.execute(RenderUIArgs(uiTree = """{"type":"text","text":"Hello"}"""))
            assertContains(result, "__a2ui_tree__=")
            assertContains(result, """{"type":"text","text":"Hello"}""")
            assertContains(result, "[A2UI] UI tree accepted for rendering.")
        }

    @Test
    fun `execute with data includes a2ui_data marker`() =
        runTest {
            val result = tool.execute(
                RenderUIArgs(
                    uiTree = """{"type":"text","text":"Hello"}""",
                    data = """{"name":"Alice"}""",
                ),
            )
            assertContains(result, "__a2ui_data__=")
            assertContains(result, """{"name":"Alice"}""")
        }

    @Test
    fun `execute without data omits a2ui_data marker`() =
        runTest {
            val result = tool.execute(RenderUIArgs(uiTree = """{"type":"text","text":"Hi"}"""))
            assertFalse(result.contains("__a2ui_data__"), "Output should not contain __a2ui_data__ when data is null")
        }

    @Test
    fun `execute with complex nested tree`() =
        runTest {
            val nestedTree =
                """
                {"type":"column","children":[
                    {"type":"text","text":"Title"},
                    {"type":"row","children":[
                        {"type":"button","label":"OK"},
                        {"type":"button","label":"Cancel"}
                    ]}
                ]}
                """.trimIndent()
            val result = tool.execute(RenderUIArgs(uiTree = nestedTree))
            assertContains(result, "__a2ui_tree__=$nestedTree")
            assertContains(result, "[A2UI] UI tree accepted for rendering.")
        }
}
