package dev.promethe.core.tools.builtin

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

// ── render_ui ──────────────────────────────────────────────

@Serializable
data class RenderUIArgs(
    @property:LLMDescription("JSON string of the UiNode tree (type, id, props, children, bindings, handlers).")
    val uiTree: String,
    @property:LLMDescription("Optional JSON object of initial data to populate the UI bindings.")
    val data: String? = null,
)

/**
 * Sends a dynamic UI tree to the client for rendering.
 *
 * The agent calls this tool when it wants to display interactive widgets
 * (forms, tables, cards, buttons) instead of plain text/markdown.
 *
 * The UI tree follows the A2UI protocol (Agent-to-UI):
 * - "text": display text with optional style
 * - "button": clickable button with onTap handler
 * - "card": elevated container for grouping
 * - "row"/"column": layout containers
 * - "form": collects user input with submit handler
 * - "input": text field bound to a data path
 * - "table": data table with headers and rows
 *
 * The tool returns the tree as metadata in the ChatEvent, which the
 * client renders via A2UIRegistry instead of markdown.
 */
class RenderUITool :
    SimpleTool<RenderUIArgs>(
        argsType = typeToken<RenderUIArgs>(),
        name = "render_ui",
        description = """Render an interactive UI in the chat. Use this instead of markdown when:
        |• You need user input (forms, buttons)
        |• You're showing structured data (tables, cards)
        |• You want interactive elements (toggles, selections)
        |
        |The uiTree parameter is a JSON UiNode. Example:
        |{"type":"card","children":[{"type":"text","props":{"text":"Hello"}}]}
        """.trimMargin(),
    ) {
    override suspend fun execute(args: RenderUIArgs): String {
        // The tool itself just validates and passes through.
        // The actual rendering happens client-side.
        // The ActionExecutor wraps the result in a ChatEvent with metadata.
        return buildString {
            appendLine("[A2UI] UI tree accepted for rendering.")
            appendLine("__a2ui_tree__=${args.uiTree}")
            if (args.data != null) {
                appendLine("__a2ui_data__=${args.data}")
            }
        }
    }
}
