package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.promethe.app.ui.SimpleMarkdownText
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

/**
 * Shared UI components for Settings sections.
 *
 * Extracted from the monolithic SettingsScreen to be reused across all section composables.
 */

// ── Section Header ──────────────────────────────────────────────────────────

@Composable
fun SectionHeader(
    title: String,
    expanded: Boolean,
    description: String? = null,
    statusColor: Color? = null,
    onClick: () -> Unit,
) {
    val actionLabel = if (expanded) stringResource(Res.string.settings_components_collapse) else stringResource(Res.string.settings_components_expand)
    val a11yDesc = if (expanded) stringResource(Res.string.settings_components_a11y_tap_collapse, title) else stringResource(Res.string.settings_components_a11y_tap_expand, title)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                contentDescription = a11yDesc
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (statusColor != null) {
                    Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(color = statusColor)
                    }
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (!expanded && description != null) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) stringResource(Res.string.settings_components_collapse_icon) else stringResource(Res.string.settings_components_expand_icon),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ── Shared Field Colors ─────────────────────────────────────────────────────

@Composable
fun settingsFieldColors(colors: ColorScheme = MaterialTheme.colorScheme) =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.primary,
        unfocusedBorderColor = colors.outline,
        cursorColor = colors.primary,
        errorBorderColor = colors.error,
    )

// ── Context File Editor ─────────────────────────────────────────────────────

@Composable
fun ContextFileEditor(
    filename: String,
    exists: Boolean,
    content: String,
    onContentChange: (String) -> Unit,
    colors: ColorScheme = MaterialTheme.colorScheme,
) {
    var promptTabSelected by remember { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(
                    color = if (exists) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = filename,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            TabRow(
                selectedTabIndex = promptTabSelected,
                modifier = Modifier.width(180.dp).height(32.dp),
                indicator = {},
                divider = {},
            ) {
                Tab(
                    selected = promptTabSelected == 0,
                    onClick = { promptTabSelected = 0 },
                    text = { Text(stringResource(Res.string.settings_components_edit_tab), style = MaterialTheme.typography.labelSmall) },
                )
                Tab(
                    selected = promptTabSelected == 1,
                    onClick = { promptTabSelected = 1 },
                    text = { Text(stringResource(Res.string.settings_components_preview_tab), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (promptTabSelected == 0) {
            OutlinedTextField(
                value = content,
                onValueChange = onContentChange,
                minLines = 6,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = settingsFieldColors(colors),
            )
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                if (content.isNotBlank()) {
                    SimpleMarkdownText(markdown = content)
                } else {
                    Text(
                        stringResource(Res.string.settings_components_empty_file),
                        color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

// ── Integration Card ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationCard(
    title: String,
    icon: ImageVector,
    configured: Boolean,
    available: Boolean = true,
    colors: ColorScheme = MaterialTheme.colorScheme,
    description: String = "",
    accentColor: Color = colors.primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val cardActionLabel =
        when {
            !available -> stringResource(Res.string.settings_components_unavailable)
            expanded -> stringResource(Res.string.settings_components_collapse)
            else -> stringResource(Res.string.settings_components_expand)
        }
    val cardA11yDesc = "$title - $cardActionLabel"
    Card(
        onClick = { if (available) expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                contentDescription = cardA11yDesc
            }
            .then(
                if (configured) {
                    Modifier.border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        modifier = Modifier.size(20.dp),
                        tint = when {
                            !available -> colors.outline
                            configured -> accentColor
                            else -> colors.onSurfaceVariant
                        },
                    )
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                        )
                        if (description.isNotBlank()) {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = when {
                            !available -> colors.surfaceVariant.copy(alpha = 0.55f)
                            configured -> accentColor.copy(alpha = 0.15f)
                            else -> colors.surfaceVariant
                        },
                    ) {
                        Text(
                            text = when {
                                !available -> stringResource(Res.string.settings_components_unavailable)
                                configured -> stringResource(Res.string.settings_components_connected)
                                else -> stringResource(Res.string.settings_components_not_configured)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (configured && available) accentColor else colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    if (available) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null, // described by parent Card semantics
                            modifier = Modifier.size(18.dp),
                            tint = colors.onSurfaceVariant,
                        )
                    }
                }
            }

            AnimatedVisibility(available && expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content,
                )
            }
        }
    }
}

// ── Connection Test State ───────────────────────────────────────────────────

sealed class ConnectionTestState {
    data object Idle : ConnectionTestState()

    data object Testing : ConnectionTestState()

    data object Success : ConnectionTestState()

    data class Error(
        val message: String,
    ) : ConnectionTestState()
}
