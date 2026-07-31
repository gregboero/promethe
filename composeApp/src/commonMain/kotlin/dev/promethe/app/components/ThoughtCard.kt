package dev.promethe.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.promethe.api.ChatEvent
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@Composable
fun ThoughtCard(
    event: ChatEvent,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .clickable { expanded = !expanded },
        colors =
            CardDefaults.cardColors(
                containerColor = colors.surfaceVariant.copy(alpha = 0.6f),
            ),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = colors.secondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text =
                        when (event.type) {
                            "thought" -> stringResource(Res.string.component_thought_thinking)
                            "action" -> stringResource(Res.string.component_thought_action, event.tool ?: "?")
                            "observation" -> stringResource(Res.string.component_thought_observation)
                            else -> event.type.replaceFirstChar { it.uppercaseChar() }
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) {
                        stringResource(Res.string.component_thought_collapse_a11y)
                    } else {
                        stringResource(Res.string.component_thought_expand_a11y)
                    },
                    tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp),
                )
            }

            // Expandable content
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    event.content?.let { content ->
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    event.args?.let { args ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = args.toString(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}
