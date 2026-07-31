package dev.promethe.app.a2ui.renderers

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.promethe.api.a2ui.UiNode
import dev.promethe.app.a2ui.A2UIRegistry
import dev.promethe.app.a2ui.DynamicContent

/**
 * RowRenderer — horizontal layout.
 *
 * Props: "spacing" (dp), "align" (start|center|end)
 */
@Composable
fun RowRenderer(
    node: UiNode,
    data: DynamicContent,
    onAction: (String, Map<String, String>) -> Unit,
) {
    val spacing = node.props["spacing"]?.toString()?.removeSurrounding("\"")?.toIntOrNull() ?: 8

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        A2UIRegistry.RenderChildren(node, data, onAction)
    }
}

/**
 * ColumnRenderer — vertical layout.
 *
 * Props: "spacing" (dp)
 */
@Composable
fun ColumnRenderer(
    node: UiNode,
    data: DynamicContent,
    onAction: (String, Map<String, String>) -> Unit,
) {
    val spacing = node.props["spacing"]?.toString()?.removeSurrounding("\"")?.toIntOrNull() ?: 4

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.dp),
    ) {
        A2UIRegistry.RenderChildren(node, data, onAction)
    }
}
