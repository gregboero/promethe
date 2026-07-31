package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

internal val MEMORY_PROVIDERS = listOf("embedded", "honcho", "tencent")
internal val MEMORY_PROVIDER_LABELS = mapOf(
    "embedded" to "Embedded (SQLite)",
    "honcho" to "Honcho",
    "tencent" to "TencentDB",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    // Pre-resolve strings for semantics{} block
    val memoryA11y = stringResource(Res.string.settings_memory_auto_a11y)
    val enabledText = stringResource(Res.string.settings_memory_auto_enabled)
    val disabledText = stringResource(Res.string.settings_memory_auto_disabled)

    SectionHeader(
        stringResource(Res.string.settings_memory_title),
        state.memoryExpanded,
        stringResource(Res.string.settings_memory_description),
        statusColor = if (state.memoryEnabled) Color(0xFF22C55E) else Color(0xFF9E9E9E),
    ) { onUpdate { copy(memoryExpanded = !memoryExpanded) } }

    AnimatedVisibility(state.memoryExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(Res.string.settings_memory_auto_title), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    Text(
                        stringResource(Res.string.settings_memory_auto_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.memoryEnabled,
                    onCheckedChange = { onUpdate { copy(memoryEnabled = it) } },
                    modifier = Modifier
                        .testTag("settings_memory_toggle")
                        .semantics {
                            role = Role.Switch
                            contentDescription = memoryA11y
                            stateDescription = if (state.memoryEnabled) enabledText else disabledText
                        },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.primary,
                        checkedTrackColor = colors.primaryContainer,
                    ),
                )
            }

            if (state.memoryEnabled) {
                Text(stringResource(Res.string.settings_memory_provider_title), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().testTag("settings_memory_provider")) {
                    MEMORY_PROVIDERS.forEachIndexed { index, key ->
                        SegmentedButton(
                            selected = state.selectedMemoryProvider == index,
                            onClick = { onUpdate { copy(selectedMemoryProvider = index) } },
                            shape = SegmentedButtonDefaults.itemShape(index, MEMORY_PROVIDERS.size),
                        ) {
                            Text(MEMORY_PROVIDER_LABELS[key] ?: key, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                val currentProvider = MEMORY_PROVIDERS[state.selectedMemoryProvider]
                AnimatedVisibility(currentProvider == "honcho") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.honchoBaseUrl,
                            onValueChange = { onUpdate { copy(honchoBaseUrl = it) } },
                            label = { Text(stringResource(Res.string.settings_memory_honcho_url_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("settings_honcho_url"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                        )
                        OutlinedTextField(
                            value = state.honchoApiKey,
                            onValueChange = { onUpdate { copy(honchoApiKey = it) } },
                            label = { Text(stringResource(Res.string.settings_memory_honcho_key_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("settings_honcho_api_key"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                        )
                    }
                }
                AnimatedVisibility(currentProvider == "tencent") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.tencentMemoryUrl,
                            onValueChange = { onUpdate { copy(tencentMemoryUrl = it) } },
                            label = { Text(stringResource(Res.string.settings_memory_tencent_url_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("settings_tencent_url"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                        )
                        OutlinedTextField(
                            value = state.tencentMemoryServiceId,
                            onValueChange = { onUpdate { copy(tencentMemoryServiceId = it) } },
                            label = { Text(stringResource(Res.string.settings_memory_tencent_service_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("settings_tencent_service_id"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                        )
                        OutlinedTextField(
                            value = state.tencentMemoryApiKey,
                            onValueChange = { onUpdate { copy(tencentMemoryApiKey = it) } },
                            label = { Text(stringResource(Res.string.settings_memory_tencent_key_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("settings_tencent_api_key"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                        )
                    }
                }
            }
        }
    }
}
