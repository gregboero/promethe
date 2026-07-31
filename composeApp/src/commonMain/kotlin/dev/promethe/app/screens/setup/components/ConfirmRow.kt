package dev.promethe.app.screens.setup.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Key-value summary row for the confirmation step.
 */
@Composable
fun ConfirmRow(
    label: String,
    value: String,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
    }
}
