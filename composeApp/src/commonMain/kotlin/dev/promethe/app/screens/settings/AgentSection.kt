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
import dev.promethe.app.util.fmt
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@Composable
fun AgentSection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateToAgents: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme

    SectionHeader(
        stringResource(Res.string.settings_agent_title),
        state.agentExpanded,
        stringResource(Res.string.settings_agent_description),
    ) {
        onUpdate { copy(agentExpanded = !agentExpanded) }
    }

    AnimatedVisibility(state.agentExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(Res.string.settings_agent_provider_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )

            // ── Température ───────────────────────────────────────────────────
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(Res.string.settings_agent_temperature), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    Text(
                        state.temperature.fmt(1),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.primary,
                    )
                }
                Slider(
                    value = state.temperature,
                    onValueChange = { onUpdate { copy(temperature = it) } },
                    valueRange = 0f..2f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth().testTag("settings_temperature"),
                    colors = SliderDefaults.colors(
                        thumbColor = colors.primary,
                        activeTrackColor = colors.primary,
                    ),
                )
                val tempMode = when {
                    state.temperature <= 0.3f -> Triple(
                        stringResource(Res.string.settings_agent_temp_deterministic),
                        stringResource(Res.string.settings_agent_temp_deterministic_desc),
                        Color(0xFF2196F3),
                    )

                    state.temperature <= 0.7f -> Triple(
                        stringResource(Res.string.settings_agent_temp_balanced),
                        stringResource(Res.string.settings_agent_temp_balanced_desc),
                        Color(0xFF4CAF50),
                    )

                    state.temperature <= 1.2f -> Triple(
                        stringResource(Res.string.settings_agent_temp_creative),
                        stringResource(Res.string.settings_agent_temp_creative_desc),
                        Color(0xFFF97316),
                    )

                    else -> Triple(
                        stringResource(Res.string.settings_agent_temp_experimental),
                        stringResource(Res.string.settings_agent_temp_experimental_desc),
                        Color(0xFFEF4444),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = tempMode.third.copy(alpha = 0.1f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tempMode.first,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = tempMode.third,
                        )
                        Text(
                            "— ${tempMode.second}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Tokens max ────────────────────────────────────────────────────
            OutlinedTextField(
                value = state.maxTokens,
                onValueChange = { onUpdate { copy(maxTokens = it.filter { c -> c.isDigit() }) } },
                label = { Text(stringResource(Res.string.settings_agent_max_tokens_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("settings_max_tokens"),
                shape = RoundedCornerShape(12.dp),
                colors = settingsFieldColors(colors),
                supportingText = { Text(stringResource(Res.string.settings_agent_max_tokens_hint)) },
            )

            // ── Itérations max ────────────────────────────────────────────────
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(Res.string.settings_agent_max_iterations), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    Text(
                        "${state.maxIterations.toInt()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.primary,
                    )
                }
                Slider(
                    value = state.maxIterations,
                    onValueChange = { onUpdate { copy(maxIterations = it) } },
                    valueRange = 1f..50f,
                    steps = 48,
                    modifier = Modifier.fillMaxWidth().testTag("settings_max_iterations"),
                    colors = SliderDefaults.colors(
                        thumbColor = colors.primary,
                        activeTrackColor = colors.primary,
                    ),
                )
                Text(
                    stringResource(Res.string.settings_agent_max_iterations_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
