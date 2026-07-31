package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@Composable
fun GepaSection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    // Pre-resolve strings for use in Modifier.semantics{} (non-composable scope)
    val gepaToggleA11y = stringResource(Res.string.settings_gepa_toggle_a11y)
    val gepaToggleOn = stringResource(Res.string.settings_gepa_toggle_on)
    val gepaToggleOff = stringResource(Res.string.settings_gepa_toggle_off)
    val gepaAutoApplyA11y = stringResource(Res.string.settings_gepa_auto_apply_a11y)

    SectionHeader(
        stringResource(Res.string.settings_gepa_title),
        state.gepaSettingsExpanded,
        stringResource(Res.string.settings_gepa_description),
    ) {
        onUpdate { copy(gepaSettingsExpanded = !gepaSettingsExpanded) }
    }
    AnimatedVisibility(state.gepaSettingsExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(Res.string.settings_gepa_enable), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    Text(
                        stringResource(Res.string.settings_gepa_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.gepaEnabled,
                    onCheckedChange = { onUpdate { copy(gepaEnabled = it) } },
                    modifier = Modifier
                        .testTag("settings_gepa_toggle")
                        .semantics {
                            role = Role.Switch
                            contentDescription = gepaToggleA11y
                            stateDescription = if (state.gepaEnabled) gepaToggleOn else gepaToggleOff
                        },
                    colors = SwitchDefaults.colors(checkedThumbColor = colors.primary, checkedTrackColor = colors.primaryContainer),
                )
            }

            AnimatedVisibility(state.gepaEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(Res.string.settings_gepa_interval), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                            Text(
                                "${state.gepaIntervalMinutes.toInt()} min",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.primary,
                            )
                        }
                        Slider(
                            value = state.gepaIntervalMinutes,
                            onValueChange = { onUpdate { copy(gepaIntervalMinutes = it) } },
                            valueRange = 15f..1440f,
                            steps = 94,
                            modifier = Modifier.testTag("settings_gepa_interval"),
                            colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(stringResource(Res.string.settings_gepa_auto_apply), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                            Text(
                                stringResource(Res.string.settings_gepa_auto_apply_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.gepaAutoApply,
                            onCheckedChange = { onUpdate { copy(gepaAutoApply = it) } },
                            modifier = Modifier
                                .testTag("settings_gepa_auto_apply")
                                .semantics {
                                    role = Role.Switch
                                    contentDescription = gepaAutoApplyA11y
                                    stateDescription = if (state.gepaAutoApply) gepaToggleOn else gepaToggleOff
                                },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.primary,
                                checkedTrackColor = colors.primaryContainer,
                            ),
                        )
                    }
                }
            }
        }
    }
}
