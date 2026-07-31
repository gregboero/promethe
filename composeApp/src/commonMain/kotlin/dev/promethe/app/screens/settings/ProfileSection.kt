package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.promethe.app.network.PrometheClient
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
    onNavigateToAgents: (() -> Unit)? = null,
    client: PrometheClient? = null,
) {
    val colors = MaterialTheme.colorScheme

    SectionHeader(
        stringResource(Res.string.settings_profile_title),
        state.profileExpanded,
        stringResource(Res.string.settings_profile_description),
        statusColor = if (state.profiles.isNotEmpty()) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
    ) { onUpdate { copy(profileExpanded = !profileExpanded) } }

    AnimatedVisibility(state.profileExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.profilesLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.settings_profile_loading), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            } else if (state.profiles.isEmpty()) {
                Text(
                    stringResource(Res.string.settings_profile_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            } else {
                var profileDropdownExpanded by remember { mutableStateOf(false) }
                val activeProfile = state.profiles.firstOrNull { it.id == state.activeProfileId }

                ExposedDropdownMenuBox(
                    expanded = profileDropdownExpanded,
                    onExpandedChange = { profileDropdownExpanded = !profileDropdownExpanded },
                ) {
                    OutlinedTextField(
                        value = activeProfile?.name ?: state.activeProfileId,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(Res.string.settings_profile_active_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                            .testTag("settings_profile_dropdown"),
                    )
                    ExposedDropdownMenu(
                        expanded = profileDropdownExpanded,
                        onDismissRequest = { profileDropdownExpanded = false },
                    ) {
                        state.profiles.forEach { profile ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(profile.name)
                                        if (profile.isSystem) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = colors.primary.copy(alpha = 0.15f),
                                            ) {
                                                Text(
                                                    stringResource(Res.string.settings_profile_active_badge),
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = colors.primary,
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    onUpdate { copy(activeProfileId = profile.id) }
                                    profileDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                // Profile info chips
                if (activeProfile != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.surfaceVariant.copy(alpha = 0.5f),
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(stringResource(Res.string.settings_profile_provider_label), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                Text(
                                    activeProfile.provider,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface,
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.surfaceVariant.copy(alpha = 0.5f),
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(stringResource(Res.string.settings_profile_model_label), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                Text(
                                    activeProfile.model,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface,
                                )
                            }
                        }
                        if (activeProfile.tools.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.surfaceVariant.copy(alpha = 0.5f),
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Text(stringResource(Res.string.settings_profile_tools_label), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                    Text(
                                        "${activeProfile.tools.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }

                // Link to full profiles screen
                if (onNavigateToAgents != null) {
                    TextButton(
                        onClick = onNavigateToAgents,
                        modifier = Modifier.padding(top = 4.dp).testTag("settings_manage_profiles"),
                    ) {
                        Text(stringResource(Res.string.settings_profile_manage), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
