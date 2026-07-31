package dev.promethe.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.promethe.app.screens.viewmodel.OrchestratorViewModel
import dev.promethe.app.screens.viewmodel.SubAgentDto
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.promethe.app.network.PrometheClient
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * OrchestratorScreen — tableau de bord multi-agent.
 *
 * Affiche :
 * - Compteurs actifs/terminés
 * - Liste des sous-agents avec statut en temps réel
 * - Possibilité d'annuler un sous-agent
 * - Délégation d'une nouvelle tâche
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrchestratorScreen(client: PrometheClient) {
    val viewModel = remember { OrchestratorViewModel(client) }
    val state by viewModel.state.collectAsState()
    val colors = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(Res.string.orchestrator_header_title), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(Res.string.orchestrator_active_completed_summary, state.dashboard.activeCount, state.dashboard.completedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    // Toggle auto-refresh
                    IconButton(onClick = { viewModel.toggleAutoRefresh() }, modifier = Modifier.testTag("orchestrator_refresh_toggle")) {
                        Icon(
                            if (state.autoRefresh) Icons.Filled.Refresh else Icons.Filled.Close,
                            contentDescription = if (state.autoRefresh) stringResource(Res.string.orchestrator_pause_refresh) else stringResource(Res.string.orchestrator_resume_refresh),
                            tint = if (state.autoRefresh) colors.primary else colors.onSurfaceVariant,
                        )
                    }
                    // Nouvelle délégation
                    FilledTonalButton(
                        onClick = { viewModel.showDelegateDialog() },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("orchestrator_create_btn"),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.orchestrator_create_pipeline_a11y), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(Res.string.orchestrator_delegate))
                    }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("orchestrator_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Compteurs ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(
                        label = stringResource(Res.string.orchestrator_stat_active),
                        value = state.dashboard.activeCount.toString(),
                        color = Color(0xFF2196F3),
                        modifier = Modifier.weight(1f),
                        colors = colors,
                    )
                    StatCard(
                        label = stringResource(Res.string.orchestrator_stat_completed),
                        value = state.dashboard.completedCount.toString(),
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.weight(1f),
                        colors = colors,
                    )
                    StatCard(
                        label = stringResource(Res.string.orchestrator_stat_total),
                        value = state.dashboard.subAgents.size.toString(),
                        color = colors.primary,
                        modifier = Modifier.weight(1f),
                        colors = colors,
                    )
                }
            }

            // ── Liste des sous-agents ──
            item {
                Text(
                    stringResource(Res.string.orchestrator_sub_agents),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (state.dashboard.subAgents.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Filled.Person,
                                null,
                                modifier = Modifier.size(48.dp),
                                tint = colors.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(Res.string.orchestrator_no_sub_agents),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(Res.string.orchestrator_no_sub_agents_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            items(state.dashboard.subAgents) { agent ->
                SubAgentCard(
                    agent = agent,
                    colors = colors,
                    onCancel = { viewModel.cancelSubAgent(agent.sessionId) },
                )
            }

            // ── Erreur ──
            state.error?.let { errorMsg ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.errorContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Warning, null, tint = colors.error)
                            Text(errorMsg, color = colors.onErrorContainer)
                        }
                    }
                }
            }
        }
    }

    // ── Dialog de délégation ──
    if (state.showDelegateDialog) {
        DelegateDialog(
            onDismiss = { viewModel.dismissDelegateDialog() },
            onDelegate = { task, profileId ->
                viewModel.delegateTask(task, profileId.ifBlank { null })
            },
            colors = colors,
        )
    }
}

// ── Composants internes ──

@Composable
private fun StatCard(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    colors: ColorScheme,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubAgentCard(
    agent: SubAgentDto,
    colors: ColorScheme,
    onCancel: () -> Unit,
) {
    val statusColor = when (agent.status.uppercase()) {
        "RUNNING" -> Color(0xFF2196F3)
        "SUCCESS", "COMPLETED" -> Color(0xFF4CAF50)
        "FAILED" -> colors.error
        else -> colors.onSurfaceVariant
    }

    val statusIcon = when (agent.status.uppercase()) {
        "RUNNING" -> Icons.Filled.Refresh
        "SUCCESS", "COMPLETED" -> Icons.Filled.Check
        "FAILED" -> Icons.Filled.Close
        else -> Icons.Filled.Info
    }

    // Hoist contentDescription (stringResource is @Composable)
    val defaultProfileLabel = stringResource(Res.string.orchestrator_agent_default_profile)
    val agentDesc = stringResource(Res.string.orchestrator_agent_profile_desc, agent.profileId ?: defaultProfileLabel)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("orchestrator_agent_${agent.sessionId}")
            .semantics {
                role = Role.Button
                contentDescription = agentDesc
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // En-tête: profil + statut
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Badge statut
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(statusColor.copy(alpha = 0.3f), statusColor.copy(alpha = 0.1f)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(statusIcon, null, modifier = Modifier.size(16.dp), tint = statusColor)
                    }

                    Column {
                        Text(
                            agent.profileId ?: "default",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            agent.sessionId.take(8) + "…",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Durée
                    agent.durationMs?.let { ms ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = colors.surfaceContainerHighest,
                        ) {
                            Text(
                                formatDuration(ms),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }

                    // Statut badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            agent.status,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                        )
                    }

                    // Bouton annuler (seulement si en cours)
                    if (agent.status.uppercase() == "RUNNING") {
                        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp).testTag("orchestrator_cancel_${agent.sessionId}")) {
                            Icon(
                                Icons.Filled.Close,
                                stringResource(Res.string.orchestrator_cancel_a11y),
                                modifier = Modifier.size(16.dp),
                                tint = colors.error,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Tâche
            Text(
                agent.task,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = colors.onSurfaceVariant,
            )

            // Réponse (si terminé)
            agent.response?.let { resp ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surfaceContainerHighest,
                ) {
                    Text(
                        resp.take(300),
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DelegateDialog(
    onDismiss: () -> Unit,
    onDelegate: (task: String, profileId: String) -> Unit,
    colors: ColorScheme,
) {
    var task by remember { mutableStateOf("") }
    var profileId by remember { mutableStateOf("") }

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
                    text = stringResource(Res.string.orchestrator_delegate_task_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = task,
                        onValueChange = { task = it },
                        label = { Text(stringResource(Res.string.orchestrator_task_label)) },
                        placeholder = { Text(stringResource(Res.string.orchestrator_task_placeholder)) },
                        modifier = Modifier.fillMaxWidth().testTag("orchestrator_task_input"),
                        minLines = 3,
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = profileId,
                        onValueChange = { profileId = it },
                        label = { Text(stringResource(Res.string.orchestrator_profile_label)) },
                        placeholder = { Text(stringResource(Res.string.orchestrator_profile_placeholder)) },
                        modifier = Modifier.fillMaxWidth().testTag("orchestrator_profile_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("orchestrator_delegate_cancel"),
                    ) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { onDelegate(task, profileId) },
                        enabled = task.isNotBlank(),
                        modifier = Modifier.testTag("orchestrator_delegate_confirm"),
                    ) {
                        Text(stringResource(Res.string.orchestrator_delegate))
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
