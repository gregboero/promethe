package dev.promethe.app.a2ui.renderers

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.promethe.api.a2ui.UiNode
import dev.promethe.app.a2ui.A2UIRegistry
import dev.promethe.app.a2ui.DynamicContent

/**
 * FormRenderer — renders a form container with input fields.
 *
 * Children: rendered as form fields
 * Handlers: "onSubmit" → ActionDef (the data snapshot is sent as params)
 */
@Composable
fun FormRenderer(
    node: UiNode,
    data: DynamicContent,
    onAction: (String, Map<String, String>) -> Unit,
) {
    val submitHandler = node.handlers["onSubmit"]

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        A2UIRegistry.RenderChildren(node, data, onAction)

        if (submitHandler != null) {
            Button(
                onClick = {
                    // Merge form data into params
                    val formData = data.snapshot()
                    val params = submitHandler.params.toMutableMap()
                    for ((key, value) in formData) {
                        params[key] = value.toString().removeSurrounding("\"")
                    }
                    onAction(submitHandler.action, params)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    A2UIRegistry.resolveText(node, "submitLabel", data, default = "Submit"),
                )
            }
        }
    }
}

/**
 * InputRenderer — renders a text input field.
 *
 * Props: "label", "placeholder", "field" (data path to store value)
 * Bindings: "value" → data path
 */
@Composable
fun InputRenderer(
    node: UiNode,
    data: DynamicContent,
    onAction: (String, Map<String, String>) -> Unit,
) {
    val label = A2UIRegistry.resolveText(node, "label", data, default = "")
    val placeholder = A2UIRegistry.resolveText(node, "placeholder", data, default = "")
    val field = node.props["field"]?.toString()?.removeSurrounding("\"") ?: node.id ?: "input"
    val currentValue = data.resolveString(field)

    OutlinedTextField(
        value = currentValue,
        onValueChange = { data.setLocal(field, it) },
        label = if (label.isNotEmpty()) {
            { Text(label) }
        } else {
            null
        },
        placeholder = if (placeholder.isNotEmpty()) {
            { Text(placeholder) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}
