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
import dev.promethe.app.screens.viewmodel.SchedulerViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import dev.promethe.api.ScheduledTaskResponse
import dev.promethe.app.network.PrometheClient
import dev.promethe.app.util.CronUtils
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * SchedulerScreen — CRUD for scheduled/cron tasks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulerScreen(client: PrometheClient) {
    val viewModel = remember { SchedulerViewModel(client) }
    val state by viewModel.state.collectAsState()
    val colors = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.scheduler_title), style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    IconButton(onClick = { viewModel.loadTasks() }, modifier = Modifier.testTag("scheduler_refresh")) {
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
                onClick = { viewModel.showCreateDialog() },
                modifier = Modifier.testTag("scheduler_create_btn"),
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.scheduler_new_task_fab))
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

                state.error != null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⚠️", style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.error ?: stringResource(Res.string.error_unknown),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { viewModel.loadTasks() }) {
                            Text(stringResource(Res.string.action_retry))
                        }
                    }
                }

                state.tasks.isEmpty() -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(72.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(Res.string.scheduler_no_tasks),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(Res.string.scheduler_tap_to_create),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("scheduler_list"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.tasks, key = { it.id }) { task ->
                            ScheduledTaskCard(
                                task = task,
                                profiles = state.profiles,
                                onToggle = { viewModel.toggleTask(task.id) },
                                onDelete = { viewModel.deleteTask(task.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Create Dialog ──
    if (state.showCreateDialog) {
        CreateTaskDialog(
            profiles = state.profiles,
            onDismiss = { viewModel.dismissCreateDialog() },
            onCreate = { name, cron, message, profileId ->
                viewModel.createTask(name, cron, message, profileId)
            },
        )
    }
}

@Composable
private fun ScheduledTaskCard(
    task: ScheduledTaskResponse,
    profiles: List<dev.promethe.api.AgentProfile>,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val cronLabels = rememberCronLabels()
    val humanLabel = remember(task.cronExpression, cronLabels) { cronToHumanLabel(task.cronExpression, cronLabels) }
    val nextRun = remember(task.cronExpression) {
        try {
            CronUtils.computeNextRuns(task.cronExpression, count = 1).firstOrNull()
        } catch (e: Exception) {
            logger.debug(e) { "Cron parse error" }
            null
        }
    }
    val taskDescA11y = stringResource(Res.string.scheduler_task_desc, task.name)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scheduler_task_${task.id}")
            .semantics {
                role = Role.Button
                contentDescription = taskDescA11y
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = if (task.enabled) Color(0xFF22C55E) else colors.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = humanLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.primary,
                    )
                    if (task.prompt.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = task.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                    val assignedProfile = task.profileId?.let { pid -> profiles.find { it.id == pid } }
                    if (assignedProfile != null) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.primaryContainer.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                colors.primary.copy(alpha = 0.15f),
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "${assignedProfile.name} (${assignedProfile.provider}/${assignedProfile.model})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
                val activeLabel = stringResource(Res.string.label_active)
                val pausedLabel = stringResource(Res.string.scheduler_paused)
                val pauseDesc = stringResource(Res.string.scheduler_pause)
                val resumeDesc = stringResource(Res.string.scheduler_resume)
                val deleteDesc = stringResource(Res.string.scheduler_delete_task_desc, task.name)
                Badge(
                    containerColor = if (task.enabled) Color(0xFF22C55E).copy(alpha = 0.15f) else colors.surfaceVariant,
                    contentColor = if (task.enabled) Color(0xFF22C55E) else colors.onSurfaceVariant,
                ) {
                    Text(if (task.enabled) activeLabel else pausedLabel)
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onToggle, modifier = Modifier.size(32.dp).testTag("scheduler_toggle_${task.id}")) {
                    Icon(
                        imageVector = if (task.enabled) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (task.enabled) pauseDesc else resumeDesc,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp).testTag("scheduler_delete_${task.id}")) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = deleteDesc,
                        tint = colors.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // ── Status chips row ──
            Spacer(Modifier.height(10.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Last run status badge
                val statusSuccessLabel = stringResource(Res.string.scheduler_status_success)
                val statusErrorLabel = stringResource(Res.string.scheduler_status_error)
                val statusRunningLabel = stringResource(Res.string.scheduler_status_running)
                if (task.lastRunStatus != null) {
                    val (statusLabel, statusColor) = when (task.lastRunStatus) {
                        "success", "completed" -> statusSuccessLabel to Color(0xFF22C55E)
                        "error" -> statusErrorLabel to Color(0xFFEF4444)
                        "running" -> statusRunningLabel to Color(0xFF3B82F6)
                        else -> "● ${task.lastRunStatus}" to colors.onSurfaceVariant
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusColor.copy(alpha = 0.12f),
                    ) {
                        Text(
                            statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                // Cron expression chip
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("⏱", style = MaterialTheme.typography.labelSmall)
                        Text(
                            task.cronExpression.ifBlank { stringResource(Res.string.scheduler_one_shot) },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurface,
                        )
                    }
                }

                // Human-readable explanation chip
                if (humanLabel != task.cronExpression) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.primary.copy(alpha = 0.08f),
                    ) {
                        Text(
                            humanLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                // Next run countdown chip
                val nextRunAt = task.nextRunAt
                if (task.enabled && nextRunAt != null && nextRunAt > 0) {
                    val countdownLabels = rememberCountdownLabels()
                    val countdown = remember(nextRunAt, countdownLabels) { formatCountdown(nextRunAt, countdownLabels) }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF59E0B).copy(alpha = 0.1f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("⏳", style = MaterialTheme.typography.labelSmall)
                            Text(
                                countdown,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFF59E0B),
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
private fun CreateTaskDialog(
    profiles: List<dev.promethe.api.AgentProfile>,
    onDismiss: () -> Unit,
    onCreate: (name: String, cron: String, message: String, profileId: String?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    var profileDropdownExpanded by remember { mutableStateOf(false) }

    // ── Hour selector ──
    var selectedHour by remember { mutableStateOf(9) }
    var hourDropdownExpanded by remember { mutableStateOf(false) }

    // ── Recurring toggle ──
    var isRecurring by remember { mutableStateOf(true) }

    // ── Interval + Period ──
    var interval by remember { mutableStateOf("1") }
    var selectedPeriodIndex by remember { mutableStateOf(2) } // default: "jours"
    val periods = listOf("minutes", "heures", "jours", "mois")
    val periodMinutesLabel = stringResource(Res.string.scheduler_period_minutes)
    val periodHoursLabel = stringResource(Res.string.scheduler_period_hours)
    val periodDaysLabel = stringResource(Res.string.scheduler_period_days)
    val periodMonthsLabel = stringResource(Res.string.scheduler_period_months)
    val periodLabels = listOf(periodMinutesLabel, periodHoursLabel, periodDaysLabel, periodMonthsLabel)
    var periodDropdownExpanded by remember { mutableStateOf(false) }

    // Build cron from selections
    val generatedCron = remember(isRecurring, selectedHour, interval, selectedPeriodIndex) {
        if (!isRecurring) {
            // One-shot: use the selected hour, will run today/tomorrow
            "0 $selectedHour * * *"
        } else {
            buildCronExpression(selectedHour, interval.toIntOrNull() ?: 1, periods[selectedPeriodIndex])
        }
    }

    // Human-readable summary — stringResource is @Composable so compute outside remember
    val humanLabel = if (!isRecurring) {
        stringResource(Res.string.scheduler_one_shot_at, selectedHour)
    } else {
        val n = interval.toIntOrNull() ?: 1
        val period = periodLabels[selectedPeriodIndex]
        if (n == 1) {
            when (periods[selectedPeriodIndex]) {
                "minutes" -> stringResource(Res.string.scheduler_every_minute_from, selectedHour)
                "heures" -> stringResource(Res.string.scheduler_every_hour_label)
                "jours" -> stringResource(Res.string.scheduler_every_day_at, selectedHour)
                "mois" -> stringResource(Res.string.scheduler_every_month_at, selectedHour)
                else -> stringResource(Res.string.scheduler_every_n_period, n, period)
            }
        } else {
            when (periods[selectedPeriodIndex]) {
                "minutes" -> stringResource(Res.string.scheduler_every_n_minutes, n)
                "heures" -> stringResource(Res.string.scheduler_every_n_hours, n)
                "jours" -> stringResource(Res.string.scheduler_every_n_days_at, n, selectedHour)
                "mois" -> stringResource(Res.string.scheduler_every_n_months_at, n, selectedHour)
                else -> stringResource(Res.string.scheduler_every_n_period, n, period)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.85f).widthIn(max = 520.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.scheduler_new_task_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // ── Name ──
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(Res.string.label_name)) },
                        placeholder = { Text(stringResource(Res.string.scheduler_name_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("scheduler_edit_name"),
                        shape = RoundedCornerShape(12.dp),
                    )

                    // ── Agent selection ──
                    ExposedDropdownMenuBox(
                        expanded = profileDropdownExpanded,
                        onExpandedChange = { profileDropdownExpanded = it },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val selectedProfile = profiles.find { it.id == selectedProfileId }
                        val labelText = if (selectedProfile != null) {
                            "${selectedProfile.name} (${selectedProfile.provider}/${selectedProfile.model})"
                        } else {
                            stringResource(Res.string.agents_default)
                        }
                        OutlinedTextField(
                            value = labelText,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(Res.string.scheduler_agent_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileDropdownExpanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )
                        ExposedDropdownMenu(
                            expanded = profileDropdownExpanded,
                            onDismissRequest = { profileDropdownExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.agents_default)) },
                                onClick = {
                                    selectedProfileId = null
                                    profileDropdownExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                            profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                text = "${profile.provider} / ${profile.model}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedProfileId = profile.id
                                        profileDropdownExpanded = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                )
                            }
                        }
                    }

                    // ── Message ──
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text(if (selectedProfileId != null) stringResource(Res.string.scheduler_agent_instructions_label) else stringResource(Res.string.scheduler_message_label)) },
                        placeholder = { Text(if (selectedProfileId != null) stringResource(Res.string.scheduler_agent_instructions_placeholder) else stringResource(Res.string.scheduler_message_placeholder)) },
                        modifier = Modifier.fillMaxWidth().testTag("scheduler_edit_prompt"),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 2,
                        maxLines = 4,
                    )

                    // ── Quick templates ──
                    Text(stringResource(Res.string.scheduler_quick_templates), style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        data class CronTemplate(
                            val label: String,
                            val emoji: String,
                            val periodIdx: Int,
                            val intervalVal: String,
                            val hour: Int,
                            val recurring: Boolean,
                        )
                        val templates = listOf(
                            CronTemplate(stringResource(Res.string.scheduler_template_every_hour), "🔄", 1, "1", 0, true),
                            CronTemplate(stringResource(Res.string.scheduler_template_every_morning), "🌅", 2, "1", 8, true),
                            CronTemplate(stringResource(Res.string.scheduler_template_every_evening), "🌙", 2, "1", 20, true),
                            CronTemplate(stringResource(Res.string.scheduler_template_every_30min), "⚡", 0, "30", 0, true),
                            CronTemplate(stringResource(Res.string.scheduler_template_every_monday), "📅", 2, "7", 9, true),
                            CronTemplate(stringResource(Res.string.scheduler_template_first_of_month), "📆", 3, "1", 9, true),
                        )
                        templates.forEach { t ->
                            val isSelected = isRecurring == t.recurring &&
                                selectedPeriodIndex == t.periodIdx &&
                                interval == t.intervalVal &&
                                selectedHour == t.hour
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    isRecurring = t.recurring
                                    selectedPeriodIndex = t.periodIdx
                                    interval = t.intervalVal
                                    selectedHour = t.hour
                                },
                                label = { Text("${t.emoji} ${t.label}", style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.primary.copy(alpha = 0.15f),
                                    selectedLabelColor = colors.primary,
                                ),
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                    }

                    // ── Hour selector ──
                    Text(stringResource(Res.string.scheduler_execution_hour), style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                    ExposedDropdownMenuBox(
                        expanded = hourDropdownExpanded,
                        onExpandedChange = { hourDropdownExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = "${selectedHour}h",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = hourDropdownExpanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                        )
                        ExposedDropdownMenu(
                            expanded = hourDropdownExpanded,
                            onDismissRequest = { hourDropdownExpanded = false },
                        ) {
                            (0..23).forEach { hour ->
                                DropdownMenuItem(
                                    text = { Text("${hour}h") },
                                    onClick = {
                                        selectedHour = hour
                                        hourDropdownExpanded = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                )
                            }
                        }
                    }

                    // ── Recurring toggle ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(stringResource(Res.string.scheduler_recurring), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                            Text(
                                stringResource(Res.string.scheduler_repeat_auto),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        val enabledStateDesc = stringResource(Res.string.label_enabled)
                        val disabledStateDesc = stringResource(Res.string.label_disabled)
                        Switch(
                            checked = isRecurring,
                            onCheckedChange = { isRecurring = it },
                            modifier = Modifier
                                .testTag("scheduler_toggle_recurring")
                                .semantics {
                                    role = Role.Switch
                                    stateDescription = if (isRecurring) enabledStateDesc else disabledStateDesc
                                },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.primary,
                                checkedTrackColor = colors.primaryContainer,
                            ),
                        )
                    }

                    // ── Interval + Period (if recurring) ──
                    androidx.compose.animation.AnimatedVisibility(visible = isRecurring) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(Res.string.scheduler_periodicity), style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(Res.string.scheduler_every), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)

                                // Interval number
                                OutlinedTextField(
                                    value = interval,
                                    onValueChange = { v -> interval = v.filter { it.isDigit() }.take(3) },
                                    singleLine = true,
                                    modifier = Modifier.width(72.dp),
                                    shape = RoundedCornerShape(12.dp),
                                )

                                // Period dropdown
                                ExposedDropdownMenuBox(
                                    expanded = periodDropdownExpanded,
                                    onExpandedChange = { periodDropdownExpanded = it },
                                ) {
                                    OutlinedTextField(
                                        value = periodLabels[selectedPeriodIndex],
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = periodDropdownExpanded) },
                                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true,
                                    )
                                    ExposedDropdownMenu(
                                        expanded = periodDropdownExpanded,
                                        onDismissRequest = { periodDropdownExpanded = false },
                                    ) {
                                        periodLabels.forEachIndexed { index, label ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                onClick = {
                                                    selectedPeriodIndex = index
                                                    periodDropdownExpanded = false
                                                },
                                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── Human-readable summary ──
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.primaryContainer.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                humanLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.primary,
                            )
                        }
                    }

                    // ── Next runs preview ──
                    val nextRuns = remember(generatedCron) {
                        try {
                            CronUtils.computeNextRuns(generatedCron, count = 3)
                        } catch (e: Exception) {
                            logger.debug(e) { "Cron preview error" }
                            emptyList()
                        }
                    }
                    if (nextRuns.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    stringResource(Res.string.scheduler_next_runs),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(6.dp))
                                nextRuns.forEach { run ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = colors.primary.copy(alpha = 0.6f),
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            run,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("scheduler_cancel")) { Text(stringResource(Res.string.action_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onCreate(name.trim(), generatedCron, message.trim(), selectedProfileId) },
                        enabled = name.isNotBlank() && (interval.toIntOrNull() ?: 0) > 0,
                        modifier = Modifier.testTag("scheduler_save"),
                    ) {
                        Text(stringResource(Res.string.action_create))
                    }
                }
            }
        }
    }
}

/**
 * Translates user-friendly interval/period into a 5-field cron expression.
 */
private fun buildCronExpression(
    hour: Int,
    interval: Int,
    period: String,
): String =
    when (period) {
        "minutes" -> {
            if (interval <= 1) {
                "* * * * *"
            } else {
                "*/$interval * * * *"
            }
        }

        "heures" -> {
            if (interval <= 1) {
                "0 * * * *"
            } else {
                "0 */$interval * * *"
            }
        }

        "jours" -> {
            if (interval <= 1) {
                "0 $hour * * *"
            } else {
                "0 $hour */$interval * *"
            }
        }

        "mois" -> {
            if (interval <= 1) {
                "0 $hour 1 * *"
            } else {
                "0 $hour 1 */$interval *"
            }
        }

        else -> {
            "0 $hour * * *"
        }
    }

/**
 * Localized labels needed by [cronToHumanLabel]. Built once per composition via
 * [rememberCronLabels] since [cronToHumanLabel] itself is a pure, non-composable function.
 */
private data class CronLabels(
    val everyMinute: String,
    val everyNMinutes: String,
    val everyHour: String,
    val everyNHours: String,
    val dailyAt: String,
    val everyNDaysAt: String,
    val weeklyAt: String,
    val dayNames: List<String>,
    val dayFallback: String,
    val monthlyAt: String,
    val everyNMonthsAt: String,
)

/**
 * Localized labels needed by [formatCountdown].
 */
private data class CountdownLabels(
    val elapsed: String,
    val lessThanOneMin: String,
    val minutesFormat: String,
    val hoursMinutesFormat: String,
    val daysFormat: String,
)

@Composable
private fun rememberCronLabels(): CronLabels {
    val everyMinute = stringResource(Res.string.scheduler_cron_every_minute)
    val everyNMinutes = stringResource(Res.string.scheduler_cron_every_n_minutes, "%1\$s")
    val everyHour = stringResource(Res.string.scheduler_cron_every_hour)
    val everyNHours = stringResource(Res.string.scheduler_cron_every_n_hours, "%1\$s")
    val dailyAt = stringResource(Res.string.scheduler_cron_daily_at, "%1\$s")
    val everyNDaysAt = stringResource(Res.string.scheduler_cron_every_n_days_at, "%1\$s", "%2\$s")
    val weeklyAt = stringResource(Res.string.scheduler_cron_weekly_at, "%1\$s", "%2\$s")
    val daySunday = stringResource(Res.string.scheduler_cron_day_sunday)
    val dayMonday = stringResource(Res.string.scheduler_cron_day_monday)
    val dayTuesday = stringResource(Res.string.scheduler_cron_day_tuesday)
    val dayWednesday = stringResource(Res.string.scheduler_cron_day_wednesday)
    val dayThursday = stringResource(Res.string.scheduler_cron_day_thursday)
    val dayFriday = stringResource(Res.string.scheduler_cron_day_friday)
    val daySaturday = stringResource(Res.string.scheduler_cron_day_saturday)
    val dayFallback = stringResource(Res.string.scheduler_cron_day_fallback, "%1\$s")
    val monthlyAt = stringResource(Res.string.scheduler_cron_monthly_at, "%1\$s")
    val everyNMonthsAt = stringResource(Res.string.scheduler_cron_every_n_months_at, "%1\$s", "%2\$s")
    return remember(
        everyMinute,
        everyNMinutes,
        everyHour,
        everyNHours,
        dailyAt,
        everyNDaysAt,
        weeklyAt,
        daySunday,
        dayMonday,
        dayTuesday,
        dayWednesday,
        dayThursday,
        dayFriday,
        daySaturday,
        dayFallback,
        monthlyAt,
        everyNMonthsAt,
    ) {
        CronLabels(
            everyMinute = everyMinute,
            everyNMinutes = everyNMinutes,
            everyHour = everyHour,
            everyNHours = everyNHours,
            dailyAt = dailyAt,
            everyNDaysAt = everyNDaysAt,
            weeklyAt = weeklyAt,
            dayNames = listOf(daySunday, dayMonday, dayTuesday, dayWednesday, dayThursday, dayFriday, daySaturday),
            dayFallback = dayFallback,
            monthlyAt = monthlyAt,
            everyNMonthsAt = everyNMonthsAt,
        )
    }
}

@Composable
private fun rememberCountdownLabels(): CountdownLabels {
    val elapsed = stringResource(Res.string.scheduler_countdown_elapsed)
    val lessThanOneMin = stringResource(Res.string.scheduler_countdown_less_than_min)
    val minutesFormat = stringResource(Res.string.scheduler_countdown_minutes, "%1\$s")
    val hoursMinutesFormat = stringResource(Res.string.scheduler_countdown_hours_minutes, "%1\$s", "%2\$s")
    val daysFormat = stringResource(Res.string.scheduler_countdown_days, "%1\$s")
    return remember(elapsed, lessThanOneMin, minutesFormat, hoursMinutesFormat, daysFormat) {
        CountdownLabels(
            elapsed = elapsed,
            lessThanOneMin = lessThanOneMin,
            minutesFormat = minutesFormat,
            hoursMinutesFormat = hoursMinutesFormat,
            daysFormat = daysFormat,
        )
    }
}

/**
 * Converts a cron expression into a human-readable label.
 * Handles common patterns; falls back to the raw cron for exotic expressions.
 */
private fun cronToHumanLabel(
    cron: String,
    labels: CronLabels,
): String {
    val parts = cron.trim().split(Regex("\\s+"))
    if (parts.size != 5) return cron

    val (minute, hour, dayOfMonth, month, dayOfWeek) = parts

    return when {
        // Every minute: * * * * *
        minute == "*" && hour == "*" && dayOfMonth == "*" && month == "*" && dayOfWeek == "*" -> {
            labels.everyMinute
        }

        // Every N minutes: */N * * * *
        minute.startsWith("*/") && hour == "*" && dayOfMonth == "*" && month == "*" && dayOfWeek == "*" -> {
            labels.everyNMinutes.replace("%1\$s", minute.removePrefix("*/"))
        }

        // Every hour: 0 * * * *
        minute == "0" && hour == "*" && dayOfMonth == "*" && month == "*" && dayOfWeek == "*" -> {
            labels.everyHour
        }

        // Every N hours: 0 */N * * *
        minute == "0" && hour.startsWith("*/") && dayOfMonth == "*" && month == "*" && dayOfWeek == "*" -> {
            labels.everyNHours.replace("%1\$s", hour.removePrefix("*/"))
        }

        // Daily at Hh: 0 H * * *
        minute == "0" && hour.toIntOrNull() != null && dayOfMonth == "*" && month == "*" && dayOfWeek == "*" -> {
            labels.dailyAt.replace("%1\$s", hour)
        }

        // Every N days at Hh: 0 H */N * *
        minute == "0" && hour.toIntOrNull() != null && dayOfMonth.startsWith("*/") && month == "*" && dayOfWeek == "*" -> {
            labels.everyNDaysAt.replace("%1\$s", dayOfMonth.removePrefix("*/")).replace("%2\$s", hour)
        }

        // Weekly on day D at Hh: 0 H * * D
        minute == "0" && hour.toIntOrNull() != null && dayOfMonth == "*" && month == "*" && dayOfWeek.toIntOrNull() != null -> {
            val d = dayOfWeek.toInt()
            val dayName = labels.dayNames.getOrElse(d) { labels.dayFallback.replace("%1\$s", d.toString()) }
            labels.weeklyAt.replace("%1\$s", dayName).replace("%2\$s", hour)
        }

        // Monthly: 0 H 1 * *
        minute == "0" && hour.toIntOrNull() != null && dayOfMonth == "1" && month == "*" && dayOfWeek == "*" -> {
            labels.monthlyAt.replace("%1\$s", hour)
        }

        // Every N months: 0 H 1 */N *
        minute == "0" && hour.toIntOrNull() != null && dayOfMonth == "1" && month.startsWith("*/") && dayOfWeek == "*" -> {
            labels.everyNMonthsAt.replace("%1\$s", month.removePrefix("*/")).replace("%2\$s", hour)
        }

        // Fallback
        else -> {
            cron
        }
    }
}

// Note: using dev.promethe.api.ScheduledTaskResponse directly — no local UI model needed.

/**
 * Format an epoch-ms timestamp as a human-readable countdown from now.
 */
private fun formatCountdown(
    epochMs: Long,
    labels: CountdownLabels,
): String {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val diff = epochMs - now
    return when {
        diff < 0 -> {
            labels.elapsed
        }

        diff < 60_000 -> {
            labels.lessThanOneMin
        }

        diff < 3_600_000 -> {
            labels.minutesFormat.replace("%1\$s", (diff / 60_000).toString())
        }

        diff < 86_400_000 -> {
            val hours = diff / 3_600_000
            val minutes = (diff % 3_600_000) / 60_000
            labels.hoursMinutesFormat.replace("%1\$s", hours.toString()).replace("%2\$s", minutes.toString())
        }

        else -> {
            labels.daysFormat.replace("%1\$s", (diff / 86_400_000).toString())
        }
    }
}
