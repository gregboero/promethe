package dev.promethe.app.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import dev.promethe.app.screens.viewmodel.SessionsViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.promethe.api.SessionInfo
import dev.promethe.api.ProjectInfo
import dev.promethe.app.network.PrometheClient
import dev.promethe.app.util.formatDateTime
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    client: PrometheClient,
    onSessionSelected: (String) -> Unit,
    onManageProjects: () -> Unit = {},
) {
    val viewModel = remember { SessionsViewModel(client) }
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
                            placeholder = { Text(stringResource(Res.string.sessions_search_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("sessions_search"),
                            shape = RoundedCornerShape(16.dp),
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = colors.primary,
                                    unfocusedBorderColor = colors.outline,
                                    cursorColor = colors.primary,
                                ),
                        )
                    } else {
                        Column {
                            Text(stringResource(Res.string.sessions_title), style = MaterialTheme.typography.headlineSmall)
                            state.activeProject?.let { project ->
                                Text(
                                    project.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.primary,
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onManageProjects) {
                        Icon(Icons.Default.FolderOpen, contentDescription = stringResource(Res.string.projects_manage))
                    }
                    IconButton(onClick = { viewModel.toggleSearch() }) {
                        Icon(
                            if (state.isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (state.isSearchActive) stringResource(Res.string.sessions_close_search) else stringResource(Res.string.action_search),
                        )
                    }
                    IconButton(onClick = { viewModel.loadSessions() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.action_refresh))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.background,
                        titleContentColor = colors.onBackground,
                    ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.createSession(onSessionSelected)
                },
                modifier = Modifier.testTag("sessions_new"),
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.sessions_new_session))
            }
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

                state.showError -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⚠️", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(Res.string.sessions_server_error),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { viewModel.loadSessions() }) {
                            Text(stringResource(Res.string.action_retry))
                        }
                    }
                }

                state.filteredSessions.isEmpty() -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (state.searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.Forum,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(72.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (state.searchQuery.isNotBlank()) stringResource(Res.string.sessions_no_matching) else stringResource(Res.string.sessions_no_sessions),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (state.searchQuery.isNotBlank()) stringResource(Res.string.sessions_try_different_search) else stringResource(Res.string.sessions_tap_to_start),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("sessions_list"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.filteredSessions, key = { it.id }) { session ->
                            SwipeToDeleteSessionCard(
                                session = session,
                                onClick = { onSessionSelected(session.id) },
                                onDelete = {
                                    viewModel.deleteSession(session.id)
                                },
                                projects = state.projects,
                                onAssignProject = { projectId -> viewModel.assignSessionProject(session.id, projectId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteSessionCard(
    session: SessionInfo,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    projects: List<ProjectInfo>,
    onAssignProject: (String?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    @Suppress("DEPRECATION")
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) {
                    onDelete()
                    true
                } else {
                    false
                }
            },
        )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val bgColor by animateColorAsState(
                targetValue =
                    if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                        colors.error
                    } else {
                        colors.surface
                    },
                label = "dismiss-bg",
            )
            val iconScale by animateFloatAsState(
                targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) 1f else 0.75f,
                label = "dismiss-icon",
            )

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(bgColor, RoundedCornerShape(16.dp))
                        .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    // TODO: i18n — uses runtime interpolation, consider stringResource with format args
                    contentDescription = stringResource(Res.string.sessions_delete_session, session.title ?: session.id.take(12)),
                    tint = colors.onError,
                    modifier = Modifier.scale(iconScale),
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        SessionCard(
            session = session,
            onClick = onClick,
            projects = projects,
            onAssignProject = onAssignProject,
        )
    }
}

@Composable
private fun SessionCard(
    session: SessionInfo,
    onClick: () -> Unit,
    projects: List<ProjectInfo>,
    onAssignProject: (String?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var showProjectMenu by remember { mutableStateOf(false) }
    val project = projects.find { it.id == session.projectId }
    val cardDesc = stringResource(Res.string.sessions_card_description, session.title ?: session.id.take(12))
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    role = Role.Button
                    contentDescription = cardDesc
                }
                .clickable(onClick = onClick)
                .testTag("sessions_card_${session.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.ChatBubbleOutline,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title ?: session.id.take(12),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatDate(session.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                project?.let {
                    Text(
                        text = it.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (session.messageCount > 0) {
                Badge(
                    containerColor = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                ) {
                    Text("${session.messageCount}")
                }
            }
            Box {
                IconButton(onClick = { showProjectMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.projects_assign))
                }
                DropdownMenu(
                    expanded = showProjectMenu,
                    onDismissRequest = { showProjectMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.projects_unassigned)) },
                        leadingIcon = {
                            if (session.projectId == null) Icon(Icons.Default.Check, contentDescription = null)
                        },
                        onClick = {
                            showProjectMenu = false
                            onAssignProject(null)
                        },
                    )
                    projects.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate.name) },
                            leadingIcon = {
                                if (session.projectId == candidate.id) Icon(Icons.Default.Check, contentDescription = null)
                            },
                            onClick = {
                                showProjectMenu = false
                                onAssignProject(candidate.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun formatDate(epochMillis: Long): String = formatDateTime(epochMillis)
