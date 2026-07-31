package dev.promethe.app.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.promethe.app.screens.viewmodel.ToolsViewModel
import dev.promethe.app.screens.viewmodel.ToolUiModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.promethe.app.network.PrometheClient
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(client: PrometheClient) {
    val viewModel = remember { ToolsViewModel(client) }
    val state by viewModel.state.collectAsState()

    val colors = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (state.isSearchActive) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            placeholder = { Text(stringResource(Res.string.tools_search_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = colors.primary,
                                    unfocusedBorderColor = colors.outline,
                                    cursorColor = colors.primary,
                                ),
                        )
                    } else {
                        Text(stringResource(Res.string.tools_title), style = MaterialTheme.typography.headlineSmall)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.toggleSearch() },
                        modifier = Modifier.testTag("tools_search"),
                    ) {
                        Icon(
                            if (state.isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (state.isSearchActive) stringResource(Res.string.tools_close_search) else stringResource(Res.string.action_search),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
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
                            state.error ?: stringResource(Res.string.error_unknown),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.testTag("tools_error"),
                        )
                    }
                }

                state.filteredTools.isEmpty() -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (state.searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.Build,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(72.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (state.searchQuery.isNotBlank()) stringResource(Res.string.tools_no_matching) else stringResource(Res.string.tools_no_tools),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (state.searchQuery.isNotBlank()) {
                                stringResource(Res.string.tools_try_different_search)
                            } else {
                                stringResource(Res.string.tools_connect_hint)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("tools_list"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Count header
                        item {
                            Text(
                                text = stringResource(Res.string.tools_count, state.filteredTools.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }

                        state.grouped.forEach { (source, sourceTools) ->
                            item {
                                Text(
                                    text = source,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                            }
                            items(sourceTools, key = { "${it.source}:${it.name}" }) { tool ->
                                ToolCard(tool = tool)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(tool: ToolUiModel) {
    val colors = MaterialTheme.colorScheme
    val sourceColor = when (tool.source) {
        "Built-in" -> Color(0xFF6366F1)
        else -> Color(0xFFA855F7)
    }
    val toolDesc = stringResource(Res.string.tools_tool_desc, tool.name)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tools_card_${tool.name}")
            .semantics {
                role = Role.Button
                contentDescription = toolDesc
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = null,
                tint = sourceColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                if (tool.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = tool.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 3,
                    )
                }
            }
            Badge(
                containerColor = sourceColor.copy(alpha = 0.15f),
                contentColor = sourceColor,
            ) {
                Text(tool.source)
            }
        }
    }
}
