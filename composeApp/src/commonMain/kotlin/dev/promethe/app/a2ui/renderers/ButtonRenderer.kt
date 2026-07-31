package dev.promethe.app.a2ui.renderers

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.promethe.api.a2ui.UiNode
import dev.promethe.app.a2ui.A2UIRegistry
import dev.promethe.app.a2ui.DynamicContent

/**
 * ButtonRenderer — renders filled or outlined buttons.
 *
 * Props: "label", "variant" (filled|outlined)
 * Handlers: "onTap" → ActionDef
 */
@Composable
fun ButtonRenderer(
    node: UiNode,
    data: DynamicContent,
    onAction: (String, Map<String, String>) -> Unit,
) {
    val label = A2UIRegistry.resolveText(node, "label", data, default = "Button")
    val variant = node.props["variant"]?.toString()?.removeSurrounding("\"") ?: "filled"
    val handler = node.handlers["onTap"]

    val onClick: () -> Unit = {
        if (handler != null) {
            onAction(handler.action, handler.params)
        }
    }

    when (variant) {
        "outlined" -> OutlinedButton(
            onClick = onClick,
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            Text(label)
        }

        else -> Button(
            onClick = onClick,
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            Text(label, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
