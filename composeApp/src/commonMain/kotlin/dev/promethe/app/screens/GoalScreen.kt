package dev.promethe.app.screens

import androidx.compose.animation.*
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
import dev.promethe.app.screens.viewmodel.GoalViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.promethe.app.network.PrometheClient
import dev.promethe.app.screens.viewmodel.GoalStatusDto
import dev.promethe.app.screens.viewmodel.GoalTaskDto
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * GoalScreen — Mode Autonome.
 *
 * Permet de lancer un objectif complexe que l'agent décompose
 * et exécute de manière autonome, tâche par tâche.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalScreen(client: PrometheClient) {
    val viewModel = remember { GoalViewModel(client) }
    val state by viewModel.state.collectAsState()

    val colors = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(Res.string.goal_autonomous_title), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(Res.string.goal_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("goal_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Zone de saisie ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = state.goalText,
                            onValueChange = { viewModel.updateGoalText(it) },
                            label = { Text(stringResource(Res.string.goal_label)) },
                            placeholder = { Text(stringResource(Res.string.goal_placeholder)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                            shape = RoundedCornerShape(12.dp),
                            enabled = !state.isRunning,
                        )

                        Spacer(Modifier.height(12.dp))

                        // Budget selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(Res.string.goal_budget_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                            )
                            val budgetMinimalLabel = stringResource(Res.string.goal_budget_minimal)
                            val budgetStandardLabel = stringResource(Res.string.goal_budget_standard)
                            val budgetExtendedLabel = stringResource(Res.string.goal_budget_extended)
                            val budgetContentDesc = stringResource(Res.string.goal_budget_content_desc)
                            listOf("MINIMAL", "STANDARD", "EXTENDED").forEach { preset ->
                                val presetLabel = when (preset) {
                                    "MINIMAL" -> budgetMinimalLabel
                                    "STANDARD" -> budgetStandardLabel
                                    "EXTENDED" -> budgetExtendedLabel
                                    else -> preset
                                }
                                FilterChip(
                                    selected = state.budgetPreset == preset,
                                    onClick = { viewModel.updateBudgetPreset(preset) },
                                    label = {
                                        Text(
                                            presetLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                    enabled = !state.isRunning,
                                    modifier = Modifier.semantics {
                                        role = Role.Tab
                                        contentDescription = budgetContentDesc.replace("%1\$s", presetLabel)
                                    },
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Boutons action
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.isRunning) {
                                OutlinedButton(
                                    onClick = { viewModel.cancelGoal() },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.goal_stop_desc), modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(Res.string.action_stop))
                                }
                            } else {
                                FilledTonalButton(
                                    onClick = { viewModel.launchGoal() },
                                    enabled = state.goalText.isNotBlank(),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(Res.string.goal_launch_desc), modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(Res.string.goal_launch_btn))
                                }
                            }
                        }
                    }
                }
            }

            // ── Statut en cours ──
            if (state.status.state != "IDLE") {
                item {
                    GoalStatusCard(state.status, colors)
                }
            }

            // ── Résultats des tâches ──
            if (state.status.results.isNotEmpty()) {
                item {
                    Text(
                        stringResource(Res.string.goal_task_results),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                items(state.status.results) { task ->
                    GoalTaskCard(task, colors)
                }
            }

            // ── Erreur ──
            state.error?.let { msg ->
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
                            Icon(Icons.Filled.Warning, contentDescription = stringResource(Res.string.label_error), tint = colors.error)
                            Text(msg, color = colors.onErrorContainer)
                        }
                    }
                }
            }
        }
    }
}

// ── Composants internes ──

@Composable
private fun GoalStatusCard(
    status: GoalStatusDto,
    colors: ColorScheme,
) {
    val stateColor = when (status.state) {
        "DECOMPOSING" -> Color(0xFFFFA726)

        // orange
        "RUNNING" -> Color(0xFF2196F3)

        // bleu
        "COMPLETED" -> Color(0xFF4CAF50)

        // vert
        "FAILED" -> colors.error

        "STOPPED" -> colors.onSurfaceVariant

        "BUDGET_EXCEEDED" -> Color(0xFFFF7043)

        // orange foncé
        else -> colors.onSurfaceVariant
    }

    val stateIdle = stringResource(Res.string.goal_state_idle)
    val stateDecomposing = stringResource(Res.string.goal_state_decomposing)
    val stateRunning = stringResource(Res.string.goal_state_running)
    val stateCompleted = stringResource(Res.string.goal_state_completed)
    val stateFailed = stringResource(Res.string.goal_state_failed)
    val stateStopped = stringResource(Res.string.goal_state_stopped)
    val stateBudgetExceeded = stringResource(Res.string.goal_state_budget_exceeded)

    val stateLabel = when (status.state) {
        "IDLE" -> stateIdle
        "DECOMPOSING" -> stateDecomposing
        "RUNNING" -> stateRunning
        "COMPLETED" -> stateCompleted
        "FAILED" -> stateFailed
        "STOPPED" -> stateStopped
        "BUDGET_EXCEEDED" -> stateBudgetExceeded
        else -> status.state
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = stateColor.copy(alpha = 0.08f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // En-tête
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stateLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = stateColor,
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = stateColor.copy(alpha = 0.15f),
                ) {
                    Text(
                        stringResource(Res.string.goal_tasks_progress, status.tasksCompleted, status.tasksTotal),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = stateColor,
                    )
                }
            }

            // Objectif
            if (status.goal.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    status.goal,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Barre de progression
            if (status.state in listOf("RUNNING", "DECOMPOSING")) {
                Spacer(Modifier.height(12.dp))
                val progress = if (status.tasksTotal > 0) {
                    status.tasksCompleted.toFloat() / status.tasksTotal
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = stateColor,
                    trackColor = stateColor.copy(alpha = 0.15f),
                )

                // Tâche en cours
                if (status.currentTask.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        status.currentTask,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Stats
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatChip("⏱ ${formatDurationGoal(status.elapsedMs)}", colors)
                if (status.totalTokens > 0) {
                    StatChip("📊 ${status.totalTokens} tokens", colors)
                }
                if (status.tasksFailed > 0) {
                    StatChip("❌ ${status.tasksFailed} ${stringResource(Res.string.goal_failures_count)}", colors)
                }
            }
        }
    }
}

@Composable
private fun GoalTaskCard(
    task: GoalTaskDto,
    colors: ColorScheme,
) {
    val statusColor = when (task.status.uppercase()) {
        "SUCCESS" -> Color(0xFF4CAF50)
        "FAILED" -> colors.error
        "SKIPPED" -> colors.onSurfaceVariant
        "BUDGET_EXCEEDED" -> Color(0xFFFF7043)
        else -> colors.onSurfaceVariant
    }

    val statusIcon = when (task.status.uppercase()) {
        "SUCCESS" -> "✅"
        "FAILED" -> "❌"
        "SKIPPED" -> "⏭"
        "BUDGET_EXCEEDED" -> "💰"
        else -> "⏳"
    }

    val taskDesc = stringResource(Res.string.goal_task_desc)
        .replace("%1\$d", task.index.toString())
        .replace("%2\$s", task.title)
        .replace("%3\$s", task.status)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                contentDescription = taskDesc
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(statusIcon)
                    Text(
                        "[${task.index}] ${task.title}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }

                Text(
                    formatDurationGoal(task.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }

            if (task.response.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surfaceContainerHighest,
                ) {
                    Text(
                        task.response.take(300),
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

@Composable
private fun StatChip(
    text: String,
    colors: ColorScheme,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.surfaceContainerHighest,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
    }
}

private fun formatDurationGoal(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 1 -> "<1s"
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
