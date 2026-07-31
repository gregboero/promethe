@file:Suppress("DEPRECATION")

package dev.promethe.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.promethe.app.screens.viewmodel.ChannelsViewModel
import dev.promethe.app.screens.viewmodel.ChannelUiModel
import dev.promethe.app.network.PrometheClient
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

/**
 * ChannelsScreen — messaging channel status and configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(client: PrometheClient) {
    val viewModel = remember { ChannelsViewModel(client) }
    val state by viewModel.state.collectAsState()

    val colors = MaterialTheme.colorScheme

    // Dismiss test result snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.testResult) {
        state.testResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearTestResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.channels_title), style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    IconButton(onClick = { viewModel.load() }, modifier = Modifier.testTag("channels_refresh")) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.channels_refresh_desc))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.background,
                        titleContentColor = colors.onBackground,
                    ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = colors.background,
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isLoading -> {
                    val loadingDesc = stringResource(Res.string.channels_loading_desc)
                    CircularProgressIndicator(
                        color = colors.primary,
                        modifier = Modifier.semantics { contentDescription = loadingDesc },
                    )
                }

                state.error != null -> {
                    val errorDesc = stringResource(Res.string.channels_error_desc, state.error ?: "")
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.semantics { contentDescription = errorDesc },
                    ) {
                        Text("⚠️", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.error ?: stringResource(Res.string.channels_unknown_error),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { viewModel.load() }) {
                            Text(stringResource(Res.string.action_retry))
                        }
                    }
                }

                state.channels.isEmpty() -> {
                    val emptyDesc = stringResource(Res.string.channels_empty_desc)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .testTag("channels_empty")
                            .semantics { contentDescription = emptyDesc },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forum,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(72.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(Res.string.channels_no_channels_available),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("channels_list"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.channels, key = { it.name }) { channel ->
                            ChannelCard(
                                channel = channel,
                                onConfigure = { viewModel.setEditingChannel(channel) },
                                onTest = {
                                    viewModel.testChannel(channel.name)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Configure Dialog ──
    state.editingChannel?.let { channel ->
        ConfigureChannelDialog(
            channel = channel,
            onDismiss = { viewModel.setEditingChannel(null) },
            onSave = { configMap ->
                viewModel.saveChannel(channel.name, configMap)
            },
        )
    }
}

@Composable
private fun ChannelCard(
    channel: ChannelUiModel,
    onConfigure: () -> Unit,
    onTest: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val icon = channelIcon(channel.name)
    val cardDescA11y = stringResource(Res.string.channels_card_desc, channel.name)
    val enabledStateDesc = stringResource(Res.string.label_enabled)
    val disabledStateDesc = stringResource(Res.string.label_disabled)
    val configuredLabel = stringResource(Res.string.channels_configured)
    val notConfiguredLabel = stringResource(Res.string.channels_not_configured)
    val testDescA11y = stringResource(Res.string.channels_test_desc, channel.name)
    val notConfiguredStatus = stringResource(Res.string.channels_not_configured_status)

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onConfigure)
                .testTag("channels_card_${channel.name}")
                .semantics {
                    role = Role.Button
                    contentDescription = cardDescA11y
                },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (channel.configured) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name.replaceFirstChar { it.uppercaseChar() },
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                if (channel.webhookPath.isNotBlank()) {
                    Text(
                        text = channel.webhookPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = notConfiguredStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
            Badge(
                containerColor = if (channel.configured) Color(0xFF22C55E).copy(alpha = 0.15f) else colors.errorContainer,
                contentColor = if (channel.configured) Color(0xFF22C55E) else colors.onErrorContainer,
                modifier = Modifier.semantics {
                    role = Role.Switch
                    stateDescription = if (channel.configured) enabledStateDesc else disabledStateDesc
                },
            ) {
                Text(if (channel.configured) configuredLabel else notConfiguredLabel)
            }
            if (channel.configured) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onTest, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = testDescA11y,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigureChannelDialog(
    channel: ChannelUiModel,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    val fieldValues = remember {
        mutableStateMapOf<String, String>().apply {
            channel.requiredKeys.forEach { key ->
                put(key, channel.config[key] ?: "")
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.9f).widthIn(max = 480.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.channels_configure_title, channel.name.replaceFirstChar { it.uppercaseChar() }),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (channel.requiredKeys.isEmpty()) {
                        Text(
                            stringResource(Res.string.channels_no_config_keys),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        channel.requiredKeys.forEach { key ->
                            OutlinedTextField(
                                value = fieldValues[key] ?: "",
                                onValueChange = { fieldValues[key] = it },
                                label = { Text(key) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(fieldValues.toMap()) },
                        enabled = channel.requiredKeys.isNotEmpty(),
                    ) {
                        Text(stringResource(Res.string.action_save))
                    }
                }
            }
        }
    }
}

private fun channelIcon(name: String): ImageVector =
    when (name.lowercase()) {
        "telegram" -> Icons.Filled.Send
        "discord" -> Icons.Default.Forum
        "slack" -> Icons.Default.Tag
        "whatsapp" -> Icons.Filled.Chat
        "signal" -> Icons.Default.Security
        "matrix" -> Icons.Default.GridView
        else -> Icons.Default.Notifications
    }
