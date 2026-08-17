package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxBackend
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.api.SandboxStatus
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.Res
import promethe.composeapp.generated.resources.settings_sandbox_backend
import promethe.composeapp.generated.resources.settings_sandbox_backend_windows
import promethe.composeapp.generated.resources.action_cancel
import promethe.composeapp.generated.resources.settings_sandbox_add_folder
import promethe.composeapp.generated.resources.settings_sandbox_description
import promethe.composeapp.generated.resources.settings_sandbox_full_access_confirm
import promethe.composeapp.generated.resources.settings_sandbox_full_access_confirm_button
import promethe.composeapp.generated.resources.settings_sandbox_full_access_warning
import promethe.composeapp.generated.resources.settings_sandbox_full_access
import promethe.composeapp.generated.resources.settings_sandbox_main_workspace
import promethe.composeapp.generated.resources.settings_sandbox_limits
import promethe.composeapp.generated.resources.settings_sandbox_memory
import promethe.composeapp.generated.resources.settings_sandbox_install
import promethe.composeapp.generated.resources.settings_sandbox_mode
import promethe.composeapp.generated.resources.settings_sandbox_network
import promethe.composeapp.generated.resources.settings_sandbox_network_off
import promethe.composeapp.generated.resources.settings_sandbox_processes
import promethe.composeapp.generated.resources.settings_sandbox_read_only
import promethe.composeapp.generated.resources.settings_sandbox_repair
import promethe.composeapp.generated.resources.settings_sandbox_refresh
import promethe.composeapp.generated.resources.settings_sandbox_self_test
import promethe.composeapp.generated.resources.settings_sandbox_self_test_failed
import promethe.composeapp.generated.resources.settings_sandbox_self_test_passed
import promethe.composeapp.generated.resources.settings_sandbox_setup_required
import promethe.composeapp.generated.resources.settings_sandbox_timeout
import promethe.composeapp.generated.resources.settings_sandbox_title
import promethe.composeapp.generated.resources.settings_sandbox_unavailable
import promethe.composeapp.generated.resources.settings_sandbox_workspace
import promethe.composeapp.generated.resources.settings_sandbox_selected_folders
import promethe.composeapp.generated.resources.settings_sandbox_no_selected_folders
import promethe.composeapp.generated.resources.settings_sandbox_folder_writable
import promethe.composeapp.generated.resources.settings_sandbox_remove_folder
import promethe.composeapp.generated.resources.settings_sandbox_full_access_local_only

@Composable
fun SandboxSection(
    state: SettingsState,
    onToggle: () -> Unit,
    onSelectMode: (SandboxMode) -> Unit,
    onAddRoot: () -> Unit,
    onRemoveRoot: (String) -> Unit,
    onSetRootWritable: (String, Boolean) -> Unit,
    onSetup: () -> Unit,
    onSelfTest: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val status = state.sandboxStatus
    val profile = state.sandboxProfile
    var showFullAccessWarning by remember { mutableStateOf(false) }
    val localConfigurationAllowed = status?.localConfigurationAllowed == true
    val statusColor = when {
        status?.available == true && status.selfTestPassed -> Color(0xFF43A047)
        status?.available == true -> colors.tertiary
        else -> colors.error
    }

    SectionHeader(
        title = stringResource(Res.string.settings_sandbox_title),
        expanded = state.sandboxExpanded,
        description = stringResource(Res.string.settings_sandbox_description),
        statusColor = statusColor,
        onClick = onToggle,
    )

    AnimatedVisibility(state.sandboxExpanded) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.sandboxLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                status?.let { SandboxStatusSummary(it, colors) }
                profile?.let { profileValue ->
                    Text(stringResource(Res.string.settings_sandbox_mode), style = MaterialTheme.typography.bodyMedium)
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth().testTag("settings_sandbox_mode"),
                    ) {
                        val modes = listOf(SandboxMode.READ_ONLY, SandboxMode.WORKSPACE_WRITE)
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = profileValue.mode == mode,
                                onClick = { onSelectMode(mode) },
                                enabled = localConfigurationAllowed,
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            ) {
                                Text(
                                    stringResource(
                                        if (mode == SandboxMode.READ_ONLY) {
                                            Res.string.settings_sandbox_read_only
                                        } else {
                                            Res.string.settings_sandbox_workspace
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = profileValue.mode == SandboxMode.FULL_ACCESS,
                            onClick = { showFullAccessWarning = true },
                            enabled = localConfigurationAllowed,
                            shape = SegmentedButtonDefaults.itemShape(0, 1),
                            modifier = Modifier.fillMaxWidth().testTag("settings_sandbox_full_access"),
                        ) {
                            Text(stringResource(Res.string.settings_sandbox_full_access))
                        }
                    }
                    if (profileValue.mode == SandboxMode.FULL_ACCESS) {
                        Text(
                            stringResource(Res.string.settings_sandbox_full_access_local_only),
                            color = colors.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Text(stringResource(Res.string.settings_sandbox_main_workspace), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        status?.workspaceRoot ?: "-",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Text(stringResource(Res.string.settings_sandbox_selected_folders), style = MaterialTheme.typography.bodyMedium)
                    val extraRoots = profileValue.readableRoots.filterNot { root ->
                        status?.workspaceRoot?.let { root.equals(it, ignoreCase = true) } == true
                    }
                    if (extraRoots.isEmpty()) {
                        Text(
                            stringResource(Res.string.settings_sandbox_no_selected_folders),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            extraRoots.forEach { root ->
                                val writable = profileValue.writableRoots.any { it.equals(root, ignoreCase = true) }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(root, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Checkbox(
                                        checked = writable,
                                        onCheckedChange = { checked -> onSetRootWritable(root, checked) },
                                        enabled = localConfigurationAllowed && profileValue.mode != SandboxMode.READ_ONLY,
                                    )
                                    Text(
                                        stringResource(Res.string.settings_sandbox_folder_writable),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    IconButton(
                                        onClick = { onRemoveRoot(root) },
                                        enabled = localConfigurationAllowed,
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(Res.string.settings_sandbox_remove_folder),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = onAddRoot,
                        enabled = localConfigurationAllowed,
                        modifier = Modifier.testTag("settings_sandbox_add_folder"),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(Res.string.settings_sandbox_add_folder))
                    }
                    LimitsSummary(profileValue)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (
                        status?.backend == SandboxBackend.WINDOWS_ELEVATED &&
                        !status.available &&
                        status.setupAvailable
                    ) {
                        Button(
                            onClick = onSetup,
                            enabled = localConfigurationAllowed && !state.sandboxSetupRunning,
                            modifier = Modifier.testTag("settings_sandbox_setup"),
                        ) {
                            if (state.sandboxSetupRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(
                                stringResource(
                                    if (status.setupRequired) {
                                        Res.string.settings_sandbox_install
                                    } else {
                                        Res.string.settings_sandbox_repair
                                    },
                                ),
                            )
                        }
                    }
                    Button(
                        onClick = onSelfTest,
                        enabled =
                            localConfigurationAllowed &&
                                !state.sandboxSetupRunning &&
                                !state.sandboxSelfTestRunning &&
                                status.available,
                        modifier = Modifier.testTag("settings_sandbox_self_test"),
                    ) {
                        if (state.sandboxSelfTestRunning) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(Res.string.settings_sandbox_self_test))
                    }
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("settings_sandbox_refresh"),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.settings_sandbox_refresh))
                    }
                }
            }
            state.sandboxError?.let {
                Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showFullAccessWarning) {
        AlertDialog(
            onDismissRequest = { showFullAccessWarning = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = colors.error) },
            title = { Text(stringResource(Res.string.settings_sandbox_full_access_warning)) },
            text = { Text(stringResource(Res.string.settings_sandbox_full_access_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFullAccessWarning = false
                        onSelectMode(SandboxMode.FULL_ACCESS)
                    },
                ) {
                    Text(stringResource(Res.string.settings_sandbox_full_access_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullAccessWarning = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SandboxStatusSummary(
    status: SandboxStatus,
    colors: androidx.compose.material3.ColorScheme,
) {
    val icon = if (status.available && status.selfTestPassed) Icons.Default.CheckCircle else Icons.Default.Warning
    val iconColor = if (status.available && status.selfTestPassed) Color(0xFF43A047) else colors.error
    val backendName =
        if (status.backend == SandboxBackend.WINDOWS_ELEVATED) {
            stringResource(Res.string.settings_sandbox_backend_windows)
        } else {
            status.backend.name
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        Column {
            Text(
                if (status.available) {
                    if (status.selfTestPassed) {
                        stringResource(Res.string.settings_sandbox_self_test_passed)
                    } else {
                        stringResource(Res.string.settings_sandbox_self_test_failed)
                    }
                } else {
                    stringResource(Res.string.settings_sandbox_unavailable)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${stringResource(Res.string.settings_sandbox_backend)}: $backendName",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            val statusMessage =
                if (status.setupRequired) {
                    stringResource(Res.string.settings_sandbox_setup_required)
                } else {
                    status.message
                }
            statusMessage?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LimitsSummary(profile: SandboxPermissionProfile) {
    val colors = MaterialTheme.colorScheme
    val limits = profile.limits
    Text(stringResource(Res.string.settings_sandbox_limits), style = MaterialTheme.typography.bodyMedium)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "${stringResource(Res.string.settings_sandbox_timeout)}: ${limits.timeoutMillis / 1000}s",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Text(
            "${stringResource(Res.string.settings_sandbox_memory)}: ${limits.memoryBytes / (1024 * 1024)} MB",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Text(
            "${stringResource(Res.string.settings_sandbox_processes)}: ${limits.processLimit}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Text(
            "${stringResource(Res.string.settings_sandbox_network)}: ${if (profile.networkMode.name == "OFF") stringResource(Res.string.settings_sandbox_network_off) else profile.networkMode.name}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}
