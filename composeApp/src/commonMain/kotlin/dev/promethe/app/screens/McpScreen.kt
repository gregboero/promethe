package dev.promethe.app.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import dev.promethe.app.screens.viewmodel.McpViewModel
import dev.promethe.app.screens.viewmodel.ToolsViewModel
import dev.promethe.app.screens.viewmodel.ToolUiModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.promethe.app.screens.viewmodel.McpServerUiModel
import dev.promethe.app.network.PrometheClient
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

/**
 * McpScreen — MCP server management and tool discovery in a tabbed layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(client: PrometheClient) {
    val mcpViewModel = remember { McpViewModel(client) }
    val toolsViewModel = remember { ToolsViewModel(client) }
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(stringResource(Res.string.mcp_tab_servers), stringResource(Res.string.mcp_tab_tools))

    val colors = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(Res.string.nav_mcp), style = MaterialTheme.typography.headlineSmall) },
                    actions = {
                        if (selectedTab == 0) {
                            IconButton(
                                onClick = { mcpViewModel.load() },
                                modifier = Modifier.testTag("mcp_refresh"),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.action_refresh))
                            }
                        } else {
                            val toolsState by toolsViewModel.state.collectAsState()
                            IconButton(
                                onClick = { toolsViewModel.toggleSearch() },
                                modifier = Modifier.testTag("tools_search"),
                            ) {
                                Icon(
                                    if (toolsState.isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = if (toolsState.isSearchActive) stringResource(Res.string.mcp_close_search) else stringResource(Res.string.action_search),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.background,
                        titleContentColor = colors.onBackground,
                    ),
                )
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = { mcpViewModel.showAddDialog() },
                    modifier = Modifier.testTag("mcp_add_btn"),
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.mcp_add_server_fab))
                }
            }
        },
        containerColor = colors.background,
    ) { padding ->
        when (selectedTab) {
            0 -> McpServersList(viewModel = mcpViewModel, padding = padding)
            1 -> ToolsList(viewModel = toolsViewModel, padding = padding)
        }
    }
}

// ── Tab 0: MCP Servers ────────────────────────────────────────────────────────

@Composable
private fun McpServersList(
    viewModel: McpViewModel,
    padding: PaddingValues,
) {
    val state by viewModel.state.collectAsState()
    val colors = MaterialTheme.colorScheme

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
                        state.error ?: stringResource(Res.string.mcp_unknown_error),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { viewModel.load() }, modifier = Modifier.testTag("mcp_retry")) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }
            }

            state.servers.isEmpty() -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(72.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(Res.string.mcp_no_servers_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(Res.string.mcp_tap_to_register),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("mcp_list"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.servers, key = { it.id }) { server ->
                        McpServerCard(
                            server = server,
                            onConnect = { viewModel.connect(server.id) },
                            onDisconnect = { viewModel.disconnect(server.id) },
                        )
                    }
                }
            }
        }
    }

    // ── Add Server Dialog ──
    if (state.showAddDialog) {
        AddMcpServerDialog(
            onDismiss = { viewModel.dismissAddDialog() },
            onAdd = { name, transport, command, url ->
                viewModel.updateDialogName(name)
                viewModel.updateDialogTransport(transport)
                viewModel.updateDialogCommand(command)
                viewModel.updateDialogUrl(url)
                viewModel.addServer()
            },
        )
    }
}

// ── Tab 1: Tools ──────────────────────────────────────────────────────────────

@Composable
private fun ToolsList(
    viewModel: ToolsViewModel,
    padding: PaddingValues,
) {
    val state by viewModel.state.collectAsState()
    val colors = MaterialTheme.colorScheme

    // Search bar (shown when search is active)
    if (state.isSearchActive) {
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = { Text(stringResource(Res.string.mcp_search_tools_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outline,
                        cursorColor = colors.primary,
                    ),
                )
                ToolsContent(state = state, colors = colors)
            }
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            ToolsContent(state = state, colors = colors)
        }
    }
}

@Composable
private fun ToolsContent(
    state: dev.promethe.app.screens.viewmodel.ToolsUiState,
    colors: ColorScheme,
) {
    when {
        state.isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.primary)
            }
        }

        state.error != null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️", style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.error ?: stringResource(Res.string.mcp_unknown_error),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.testTag("tools_error"),
                    )
                }
            }
        }

        state.filteredTools.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (state.searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.Build,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(72.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (state.searchQuery.isNotBlank()) stringResource(Res.string.mcp_no_matching_tools) else stringResource(Res.string.mcp_no_tools),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (state.searchQuery.isNotBlank()) {
                            stringResource(Res.string.mcp_try_different_search)
                        } else {
                            stringResource(Res.string.mcp_connect_servers_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
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
                    // TODO: i18n — plural logic, consider Android quantity strings or keep as-is
                    Text(
                        text = "${state.filteredTools.size} tool${if (state.filteredTools.size != 1) "s" else ""}",
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

// ── Shared composables ────────────────────────────────────────────────────────

@Composable
private fun ToolCard(tool: ToolUiModel) {
    val colors = MaterialTheme.colorScheme
    val sourceColor = when (tool.source) {
        "Built-in" -> Color(0xFF6366F1)
        else -> Color(0xFFA855F7)
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("tools_card_${tool.name}"),
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

@Composable
private fun McpServerCard(
    server: McpServerUiModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val isConnected = server.status == "connected"

    Card(
        modifier = Modifier.fillMaxWidth().testTag("mcp_server_${server.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column {
            // Header row
            val serverDesc = stringResource(Res.string.mcp_server_description, server.name)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (server.tools.isNotEmpty()) expanded = !expanded }
                    .semantics {
                        role = Role.Button
                        contentDescription = serverDesc
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = if (isConnected) Color(0xFF22C55E) else colors.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        // TODO: i18n — complex interpolation with runtime transport + tools count
                        text = "${server.transport} · ${server.tools.size} tools",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Badge(
                    containerColor = if (isConnected) Color(0xFF22C55E).copy(alpha = 0.15f) else colors.errorContainer,
                    contentColor = if (isConnected) Color(0xFF22C55E) else colors.onErrorContainer,
                ) {
                    Text(if (isConnected) stringResource(Res.string.mcp_connected) else stringResource(Res.string.mcp_disconnected))
                }
                Spacer(Modifier.width(8.dp))
                if (isConnected) {
                    IconButton(onClick = onDisconnect, modifier = Modifier.size(32.dp).testTag("mcp_disconnect_${server.id}")) {
                        Icon(
                            Icons.Default.LinkOff,
                            contentDescription = stringResource(Res.string.action_disconnect),
                            tint = colors.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    IconButton(onClick = onConnect, modifier = Modifier.size(32.dp).testTag("mcp_connect_${server.id}")) {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = stringResource(Res.string.action_connect),
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (server.tools.isNotEmpty()) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) stringResource(Res.string.mcp_collapse) else stringResource(Res.string.mcp_expand),
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Expandable tools section
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(Res.string.mcp_tools),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                    server.tools.forEach { tool ->
                        Surface(
                            color = colors.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Build,
                                    contentDescription = null,
                                    tint = colors.tertiary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = tool,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = colors.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddMcpServerDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, transport: String, command: String, url: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("stdio") }
    var command by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

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
                    text = stringResource(Res.string.mcp_register_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(Res.string.mcp_server_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    // Transport selector
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(Res.string.mcp_transport_label), style = MaterialTheme.typography.labelMedium)
                        FilterChip(
                            selected = transport == "stdio",
                            onClick = { transport = "stdio" },
                            label = { Text("stdio") },
                        )
                        FilterChip(
                            selected = transport == "sse",
                            onClick = { transport = "sse" },
                            label = { Text("SSE") },
                        )
                    }
                    if (transport == "stdio") {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            label = { Text(stringResource(Res.string.mcp_command_label)) },
                            placeholder = { Text(stringResource(Res.string.mcp_command_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )
                    } else {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text(stringResource(Res.string.mcp_url_label)) },
                            placeholder = { Text(stringResource(Res.string.mcp_url_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )
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
                        onClick = { onAdd(name.trim(), transport, command.trim(), url.trim()) },
                        enabled = name.isNotBlank() && (if (transport == "stdio") command.isNotBlank() else url.isNotBlank()),
                    ) {
                        Text(stringResource(Res.string.mcp_register_action))
                    }
                }
            }
        }
    }
}
