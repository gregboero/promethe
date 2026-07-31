package dev.promethe.app.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.promethe.app.screens.viewmodel.PluginsViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.promethe.api.PluginListResponse
import dev.promethe.api.PluginResponse
import dev.promethe.app.network.PrometheClient
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(client: PrometheClient) {
    val viewModel = remember { PluginsViewModel(client) }
    val state by viewModel.state.collectAsState()

    val colors = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(Res.string.plugins_title), style = MaterialTheme.typography.headlineSmall)
                        state.pluginListResponse?.let {
                            Badge(
                                containerColor = colors.primaryContainer,
                                contentColor = colors.onPrimaryContainer,
                            ) {
                                Text("${it.totalPlugins}")
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.load() }, modifier = Modifier.testTag("plugins_refresh")) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.action_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground,
                ),
            )
        },
        containerColor = colors.background,
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(color = colors.primary)
                }

                state.error != null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⚠️", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.error ?: stringResource(Res.string.plugins_unknown_error),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { viewModel.load() }) {
                            Text(stringResource(Res.string.action_retry))
                        }
                    }
                }

                state.pluginListResponse == null || state.pluginListResponse!!.plugins.isEmpty() -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(72.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(Res.string.plugins_no_plugins_detected),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("plugins_empty"),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(Res.string.plugins_place_in_folder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("plugins_list"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.pluginListResponse!!.plugins, key = { it.name }) { plugin ->
                            PluginCard(plugin = plugin)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginCard(plugin: PluginResponse) {
    val colors = MaterialTheme.colorScheme
    val pluginDesc = stringResource(Res.string.plugins_plugin_desc, plugin.name)
    val stateDesc = if (plugin.enabled) stringResource(Res.string.plugins_state_enabled) else stringResource(Res.string.plugins_state_disabled)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plugins_card_${plugin.name}")
            .semantics {
                role = Role.Button
                contentDescription = pluginDesc
                stateDescription = stateDesc
            },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                    )
                    SuggestionChip(
                        onClick = {},
                        label = { Text("v${plugin.version}", style = MaterialTheme.typography.labelSmall) },
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val dotColor = if (plugin.enabled) Color(0xFF22C55E) else colors.error
                    val statusText = if (plugin.enabled) stringResource(Res.string.label_active) else stringResource(Res.string.label_inactive)

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (plugin.enabled) Color(0xFF22C55E).copy(alpha = 0.15f) else colors.errorContainer,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = dotColor,
                                modifier = Modifier.size(6.dp),
                            ) {}
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (plugin.enabled) Color(0xFF22C55E) else colors.onErrorContainer,
                            )
                        }
                    }
                }
            }

            if (plugin.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (plugin.toolCount > 0) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(Res.string.plugins_tool_count, plugin.toolCount)) },
                    )
                }
                if (plugin.hookCount > 0) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(Res.string.plugins_hook_count, plugin.hookCount)) },
                    )
                }
                if (plugin.promptCount > 0) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(Res.string.plugins_prompt_count, plugin.promptCount)) },
                    )
                }
            }

            if (plugin.author.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.plugins_author, plugin.author),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}
