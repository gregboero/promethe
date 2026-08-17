package dev.promethe.app.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.promethe.api.ProjectInfo
import dev.promethe.app.network.PrometheClient
import dev.promethe.app.screens.viewmodel.ProjectsViewModel
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.Res
import promethe.composeapp.generated.resources.action_cancel
import promethe.composeapp.generated.resources.action_refresh
import promethe.composeapp.generated.resources.action_save
import promethe.composeapp.generated.resources.projects_activate
import promethe.composeapp.generated.resources.projects_active
import promethe.composeapp.generated.resources.projects_archive
import promethe.composeapp.generated.resources.projects_archived
import promethe.composeapp.generated.resources.projects_description
import promethe.composeapp.generated.resources.projects_edit
import promethe.composeapp.generated.resources.projects_empty
import promethe.composeapp.generated.resources.projects_instructions
import promethe.composeapp.generated.resources.projects_memory_count
import promethe.composeapp.generated.resources.projects_name
import promethe.composeapp.generated.resources.projects_new
import promethe.composeapp.generated.resources.projects_restore
import promethe.composeapp.generated.resources.projects_session_count
import promethe.composeapp.generated.resources.projects_show_archived
import promethe.composeapp.generated.resources.projects_title
import promethe.composeapp.generated.resources.projects_workspace

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(client: PrometheClient) {
    val viewModel = remember { ProjectsViewModel(client) }
    val state by viewModel.state.collectAsState()
    var editedProject by remember { mutableStateOf<ProjectInfo?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.projects_title)) },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.showArchived,
                            onCheckedChange = viewModel::setShowArchived,
                        )
                        Text(stringResource(Res.string.projects_show_archived), style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = viewModel::load) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.action_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editedProject = null
                    showEditor = true
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.projects_new))
            }
        },
    ) { padding ->
        when {
            state.isLoading -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.visibleProjects.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.projects_empty))
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.visibleProjects, key = ProjectInfo::id) { project ->
                        ProjectCard(
                            project = project,
                            onActivate = { viewModel.activate(project.id) },
                            onEdit = {
                                editedProject = project
                                showEditor = true
                            },
                            onArchive = { viewModel.setArchived(project, !project.archived) },
                        )
                    }
                }
            }
        }
    }

    if (showEditor) {
        ProjectEditorDialog(
            project = editedProject,
            onDismiss = { showEditor = false },
            onSave = { name, description, instructions ->
                viewModel.save(editedProject, name, description, instructions) { showEditor = false }
            },
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectInfo,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.padding(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                    if (project.active) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.height(14.dp))
                            Text(stringResource(Res.string.projects_active), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (project.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(project.description, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.projects_workspace, project.workspacePath), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(Res.string.projects_session_count, project.sessionCount), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(Res.string.projects_memory_count, project.memoryCount), style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (!project.active && !project.archived) {
                    TextButton(onClick = onActivate) { Text(stringResource(Res.string.projects_activate)) }
                }
                IconButton(onClick = onEdit, enabled = !project.archived) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(Res.string.projects_edit))
                }
                IconButton(onClick = onArchive) {
                    Icon(
                        if (project.archived) Icons.Default.Unarchive else Icons.Default.Archive,
                        contentDescription = stringResource(if (project.archived) Res.string.projects_restore else Res.string.projects_archive),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectEditorDialog(
    project: ProjectInfo?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var name by remember(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var description by remember(project?.id) { mutableStateOf(project?.description.orEmpty()) }
    var instructions by remember(project?.id) { mutableStateOf(project?.instructions.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (project == null) Res.string.projects_new else Res.string.projects_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.projects_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(Res.string.projects_description)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text(stringResource(Res.string.projects_instructions)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, description, instructions) }, enabled = name.isNotBlank()) {
                Text(stringResource(Res.string.action_save))
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
