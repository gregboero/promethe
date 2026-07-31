package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@Composable
fun ContextWindowSection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    SectionHeader(
        stringResource(Res.string.settings_context_window_title),
        state.contextWindowExpanded,
        stringResource(Res.string.settings_context_window_description),
    ) {
        onUpdate { copy(contextWindowExpanded = !contextWindowExpanded) }
    }
    AnimatedVisibility(state.contextWindowExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(Res.string.settings_context_window_max_tokens), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    Text(
                        "${(state.maxContextTokens / 1000).toInt()}K",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.primary,
                    )
                }
                Slider(
                    value = state.maxContextTokens,
                    onValueChange = { onUpdate { copy(maxContextTokens = it) } },
                    valueRange = 8_000f..200_000f,
                    steps = 23,
                    modifier = Modifier.testTag("settings_max_context_tokens"),
                    colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
                )
                Text(
                    stringResource(Res.string.settings_context_window_max_tokens_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(Res.string.settings_context_window_compression), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    Text(
                        "${(state.compressionThreshold * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.primary,
                    )
                }
                Slider(
                    value = state.compressionThreshold,
                    onValueChange = { onUpdate { copy(compressionThreshold = it) } },
                    valueRange = 0.5f..0.95f,
                    steps = 8,
                    modifier = Modifier.testTag("settings_compression_threshold"),
                    colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
                )
                Text(
                    stringResource(Res.string.settings_context_window_compression_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
