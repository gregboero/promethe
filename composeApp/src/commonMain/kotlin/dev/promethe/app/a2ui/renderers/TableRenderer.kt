package dev.promethe.app.a2ui.renderers

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.promethe.api.a2ui.UiNode
import dev.promethe.app.a2ui.A2UIRegistry
import dev.promethe.app.a2ui.DynamicContent

/**
 * TableRenderer — renders a data table.
 *
 * Props:
 *   "columns" → comma-separated column headers
 *   "rows" → binding path to data array (TODO: for now, children render rows)
 *
 * Children: each child is a TableRow (type = "table_row")
 */
@Composable
fun TableRenderer(
    node: UiNode,
    data: DynamicContent,
    onAction: (String, Map<String, String>) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val columnsStr = node.props["columns"]?.toString()?.removeSurrounding("\"") ?: ""
    val columns = columnsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(8.dp))
            .padding(1.dp),
    ) {
        // Header row
        if (columns.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                columns.forEach { col ->
                    Text(
                        text = col,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
        }

        // Data rows (children)
        A2UIRegistry.RenderChildren(node, data, onAction)
    }
}

/**
 * TableRowRenderer — renders a single table row.
 *
 * Props:
 *   "cells" → comma-separated cell values
 * Bindings: "cells" → data path
 */
@Composable
fun TableRowRenderer(
    node: UiNode,
    data: DynamicContent,
    onAction: (String, Map<String, String>) -> Unit,
) {
    val cellsStr = A2UIRegistry.resolveText(node, "cells", data)
    val cells = cellsStr.split(",").map { it.trim() }
    val handler = node.handlers["onTap"]

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        cells.forEach { cell ->
            Text(
                text = cell,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
