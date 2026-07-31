package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.promethe.app.util.checkDockerAvailable
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

internal val EXECUTION_BACKENDS = listOf("local", "docker", "ssh")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecutionSection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    val executionBackendLabels = mapOf(
        "local" to stringResource(Res.string.settings_execution_local),
        "docker" to stringResource(Res.string.settings_execution_docker),
        "ssh" to stringResource(Res.string.settings_execution_ssh),
    )

    SectionHeader(
        stringResource(Res.string.settings_execution_title),
        state.executionExpanded,
        stringResource(Res.string.settings_execution_description),
    ) {
        onUpdate { copy(executionExpanded = !executionExpanded) }
    }
    AnimatedVisibility(state.executionExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Docker availability check
            var dockerAvailable by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) { dockerAvailable = checkDockerAvailable() }

            Text(stringResource(Res.string.settings_execution_backend_label), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().testTag("settings_execution_backend")) {
                EXECUTION_BACKENDS.forEachIndexed { index, key ->
                    SegmentedButton(
                        selected = state.selectedExecutionBackend == index,
                        onClick = { onUpdate { copy(selectedExecutionBackend = index) } },
                        shape = SegmentedButtonDefaults.itemShape(index, EXECUTION_BACKENDS.size),
                    ) {
                        val label = executionBackendLabels[key] ?: key
                        val suffix = if (key == "docker") {
                            when (dockerAvailable) {
                                true -> " ✅"
                                false -> " ⚠️"
                                null -> " …"
                            }
                        } else {
                            ""
                        }
                        Text(label + suffix, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Timeout
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(Res.string.settings_execution_timeout), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    Text(
                        "${(state.executionTimeoutMs / 1000).toInt()}s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.primary,
                    )
                }
                Slider(
                    value = state.executionTimeoutMs,
                    onValueChange = { onUpdate { copy(executionTimeoutMs = it) } },
                    valueRange = 5_000f..300_000f,
                    steps = 58,
                    modifier = Modifier.testTag("settings_execution_timeout"),
                    colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
                )
            }

            OutlinedTextField(
                value = state.maxOutputBytes,
                onValueChange = { onUpdate { copy(maxOutputBytes = it.filter { c -> c.isDigit() }) } },
                label = { Text(stringResource(Res.string.settings_execution_max_output)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("settings_max_output_bytes"),
                shape = RoundedCornerShape(12.dp),
                colors = settingsFieldColors(colors),
                supportingText = { Text(stringResource(Res.string.settings_execution_max_output_hint)) },
            )

            // Docker (conditional)
            val currentBackend = EXECUTION_BACKENDS[state.selectedExecutionBackend]
            AnimatedVisibility(currentBackend == "docker") {
                OutlinedTextField(
                    value = state.dockerImage,
                    onValueChange = { onUpdate { copy(dockerImage = it) } },
                    label = { Text(stringResource(Res.string.settings_execution_docker_image)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_docker_image"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_execution_docker_image_hint)) },
                )
            }

            // SSH (conditional)
            AnimatedVisibility(currentBackend == "ssh") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.sshHost,
                        onValueChange = { onUpdate { copy(sshHost = it) } },
                        label = { Text(stringResource(Res.string.settings_execution_ssh_host)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_ssh_host"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.sshUser,
                            onValueChange = { onUpdate { copy(sshUser = it) } },
                            label = { Text(stringResource(Res.string.settings_execution_ssh_user)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("settings_ssh_user"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                        )
                        OutlinedTextField(
                            value = state.sshPort,
                            onValueChange = { onUpdate { copy(sshPort = it.filter { c -> c.isDigit() }) } },
                            label = { Text(stringResource(Res.string.settings_execution_ssh_port)) },
                            singleLine = true,
                            modifier = Modifier.width(100.dp).testTag("settings_ssh_port"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                        )
                    }
                    OutlinedTextField(
                        value = state.sshKeyPath,
                        onValueChange = { onUpdate { copy(sshKeyPath = it) } },
                        label = { Text(stringResource(Res.string.settings_execution_ssh_key_path)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_ssh_key_path"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                        supportingText = { Text(stringResource(Res.string.settings_execution_ssh_key_path_hint)) },
                    )
                }
            }
        }
    }
}
