package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

internal val TRACING_BACKENDS = listOf("console", "langfuse", "otlp", "none")
internal val TRACING_BACKEND_LABELS = mapOf(
    "console" to "Console",
    "langfuse" to "Langfuse",
    "otlp" to "OTLP",
    "none" to "Off",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObservabilitySection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    SectionHeader(stringResource(Res.string.settings_observability_title), state.observabilityExpanded, stringResource(Res.string.settings_observability_description)) {
        onUpdate { copy(observabilityExpanded = !observabilityExpanded) }
    }
    AnimatedVisibility(state.observabilityExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.settings_observability_tracing_label), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().testTag("settings_tracing_backend")) {
                TRACING_BACKENDS.forEachIndexed { index, key ->
                    SegmentedButton(
                        selected = state.selectedTracingBackend == index,
                        onClick = { onUpdate { copy(selectedTracingBackend = index) } },
                        shape = SegmentedButtonDefaults.itemShape(index, TRACING_BACKENDS.size),
                    ) {
                        Text(TRACING_BACKEND_LABELS[key] ?: key, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            val currentTracing = TRACING_BACKENDS[state.selectedTracingBackend]

            AnimatedVisibility(currentTracing == "langfuse") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.langfusePublicKey,
                        onValueChange = { onUpdate { copy(langfusePublicKey = it) } },
                        label = { Text(stringResource(Res.string.settings_observability_langfuse_public_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_langfuse_public_key"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                    )
                    OutlinedTextField(
                        value = state.langfuseSecretKey,
                        onValueChange = { onUpdate { copy(langfuseSecretKey = it) } },
                        label = { Text(stringResource(Res.string.settings_observability_langfuse_secret_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("settings_langfuse_secret_key"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                    )
                    OutlinedTextField(
                        value = state.langfuseHost,
                        onValueChange = { onUpdate { copy(langfuseHost = it) } },
                        label = { Text(stringResource(Res.string.settings_observability_langfuse_host_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_langfuse_host"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                        supportingText = { Text(stringResource(Res.string.settings_observability_langfuse_host_hint)) },
                    )
                }
            }
            AnimatedVisibility(currentTracing == "otlp") {
                OutlinedTextField(
                    value = state.otlpEndpoint,
                    onValueChange = { onUpdate { copy(otlpEndpoint = it) } },
                    label = { Text(stringResource(Res.string.settings_observability_otlp_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_otlp_endpoint"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_observability_otlp_hint)) },
                )
            }
        }
    }
}
