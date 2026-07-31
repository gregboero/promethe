package dev.promethe.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.promethe.api.ChatEvent
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@Composable
fun AgentReasoningBlock(
    steps: List<ChatEvent>,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // Reset/update expanded state based on streaming status
    var expanded by remember(isStreaming) { mutableStateOf(isStreaming) }
    val colors = MaterialTheme.colorScheme

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded },
        colors =
            CardDefaults.cardColors(
                containerColor = colors.surfaceVariant.copy(alpha = 0.35f),
            ),
        shape = RoundedCornerShape(8.dp),
        border =
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(colors.outlineVariant.copy(alpha = 0.4f)),
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Header: 🧠 Raisonnement (X étapes) and expand/collapse icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (steps.size > 1) {
                        stringResource(Res.string.component_reasoning_title_plural, steps.size)
                    } else {
                        stringResource(Res.string.component_reasoning_title, steps.size)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )

                if (isStreaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = colors.secondary,
                    )
                    Spacer(Modifier.width(8.dp))
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) {
                        stringResource(Res.string.component_reasoning_collapse_a11y)
                    } else {
                        stringResource(Res.string.component_reasoning_expand_a11y)
                    },
                    tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }

            // Expandable Content
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    steps.forEach { step ->
                        ReasoningStepItem(step = step)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningStepItem(
    step: ChatEvent,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var detailsExpanded by remember { mutableStateOf(false) }

    val hasDetails = !step.content.isNullOrBlank() || step.args != null

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .then(
                    if (hasDetails) {
                        Modifier.clickable { detailsExpanded = !detailsExpanded }
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Icon / Emoji representing step type
            Text(
                text =
                    when (step.type) {
                        "thought" -> "💭"
                        "action" -> "🔧"
                        "observation" -> "📋"
                        else -> "ℹ️"
                    },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 8.dp),
            )

            // Header text / Summary of the step
            Column(modifier = Modifier.weight(1f)) {
                val thinkingLabel = stringResource(Res.string.component_reasoning_thinking)
                val actionDefaultLabel = stringResource(Res.string.component_reasoning_action_default)
                val observationPrefix = stringResource(Res.string.component_reasoning_observation_prefix, "%1\$s")
                val title =
                    when (step.type) {
                        "thought" -> {
                            val rawContent = step.content ?: ""
                            if (rawContent.length in 1..60) rawContent else thinkingLabel
                        }

                        "action" -> {
                            val toolName = step.tool
                                ?: step.content
                                    ?.removePrefix("🔧 ")
                                    ?.substringBefore("(")
                                    ?.trim()
                                    ?.takeIf { it.isNotBlank() }
                                ?: actionDefaultLabel
                            toolName
                        }

                        "observation" -> {
                            val rawContent = step.content ?: ""
                            val preview = if (rawContent.length > 80) "${rawContent.take(80)}..." else rawContent
                            observationPrefix.replace("%1\$s", preview)
                        }

                        else -> {
                            step.type.replaceFirstChar { it.uppercaseChar() }
                        }
                    }

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }

            if (hasDetails) {
                Icon(
                    imageVector = if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp).align(Alignment.CenterVertically),
                )
            }
        }

        // Details (full content, arguments, etc.)
        if (hasDetails) {
            AnimatedVisibility(
                visible = detailsExpanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .padding(start = 28.dp, top = 4.dp, end = 4.dp)
                            .fillMaxWidth(),
                ) {
                    val content = step.content
                    val shouldShowContent =
                        when (step.type) {
                            "thought" -> (content?.length ?: 0) > 60
                            "observation" -> (content?.length ?: 0) > 80
                            else -> true
                        }

                    if (shouldShowContent && !content.isNullOrBlank()) {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }

                    if (step.args != null) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = colors.surface.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = step.args.toString(),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
