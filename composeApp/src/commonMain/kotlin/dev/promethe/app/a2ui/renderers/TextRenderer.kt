package dev.promethe.app.a2ui.renderers

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.promethe.api.a2ui.UiNode
import dev.promethe.app.a2ui.A2UIRegistry
import dev.promethe.app.a2ui.DynamicContent

/**
 * TextRenderer — renders text with optional style binding.
 *
 * Props: "text", "style" (headline|title|body|label)
 * Bindings: "text" → data path
 */
@Composable
fun TextRenderer(
    node: UiNode,
    data: DynamicContent,
    onAction: (String, Map<String, String>) -> Unit,
) {
    val text = A2UIRegistry.resolveText(node, "text", data)
    val styleName = node.props["style"]?.toString()?.removeSurrounding("\"") ?: "body"

    val style = when (styleName) {
        "headline" -> MaterialTheme.typography.headlineMedium
        "title" -> MaterialTheme.typography.titleLarge
        "label" -> MaterialTheme.typography.labelLarge
        else -> MaterialTheme.typography.bodyLarge
    }

    Text(
        text = text,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
