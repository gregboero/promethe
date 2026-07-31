package dev.promethe.app.a2ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.promethe.api.a2ui.UiNode

/**
 * A2UIRegistry — extensible widget renderer registry.
 *
 * Inspired by Flutter rfw's LocalWidgetLibrary: maps widget type strings
 * to Composable renderers. Unknown types get a graceful fallback.
 *
 * Built-in types: text, button, card, row, column, form, table
 * Custom types can be registered via [register].
 */
object A2UIRegistry {
    /**
     * Renderer function signature.
     * Receives the node, data state, and an action callback.
     */
    typealias Renderer = @Composable (
        node: UiNode,
        data: DynamicContent,
        onAction: (action: String, params: Map<String, String>) -> Unit,
    ) -> Unit

    private val renderers = mutableMapOf<String, Renderer>()

    /**
     * Register a custom renderer for a widget type.
     */
    fun register(
        type: String,
        renderer: Renderer,
    ) {
        renderers[type] = renderer
    }

    /**
     * Render a UiNode tree recursively.
     * Dispatches to the registered renderer or falls back gracefully.
     */
    @Composable
    fun Render(
        node: UiNode,
        data: DynamicContent,
        onAction: (action: String, params: Map<String, String>) -> Unit,
    ) {
        val renderer = renderers[node.type]
        if (renderer != null) {
            renderer(node, data, onAction)
        } else {
            FallbackRenderer(node, data, onAction)
        }
    }

    /**
     * Render all children of a node.
     */
    @Composable
    fun RenderChildren(
        node: UiNode,
        data: DynamicContent,
        onAction: (action: String, params: Map<String, String>) -> Unit,
    ) {
        for (child in node.children) {
            Render(child, data, onAction)
        }
    }

    /**
     * Resolve a prop or binding to a display string.
     * Priority: binding (dynamic) → prop (static) → default.
     */
    fun resolveText(
        node: UiNode,
        propName: String,
        data: DynamicContent,
        default: String = "",
    ): String {
        // Check binding first (dynamic data)
        val bindingPath = node.bindings[propName]
        if (bindingPath != null) {
            val resolved = data.resolveString(bindingPath)
            if (resolved.isNotEmpty()) return resolved
        }
        // Fall back to static prop
        val prop = node.props[propName]
        if (prop != null) {
            return prop.toString().removeSurrounding("\"")
        }
        return default
    }

    /**
     * Fallback renderer for unknown types — shows a debug placeholder.
     */
    @Composable
    private fun FallbackRenderer(
        node: UiNode,
        data: DynamicContent,
        onAction: (action: String, params: Map<String, String>) -> Unit,
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Text(
                text = "[${node.type}]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            // Still render children so partial trees don't break
            RenderChildren(node, data, onAction)
        }
    }
}
