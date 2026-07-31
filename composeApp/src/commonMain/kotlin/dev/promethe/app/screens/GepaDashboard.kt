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
import dev.promethe.app.screens.viewmodel.GepaDashboardViewModel
import dev.promethe.app.screens.viewmodel.GepaRun
import dev.promethe.app.screens.viewmodel.GepaTargetType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.promethe.api.GepaResultDto
import dev.promethe.app.network.PrometheClient
import dev.promethe.app.util.fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GepaDashboard(client: PrometheClient) {
    val viewModel = remember { GepaDashboardViewModel(client) }
    val state by viewModel.state.collectAsState()
    val colors = MaterialTheme.colorScheme

    // Progress is now driven by server polling (via the ViewModel),
    // so we don't need fake animation anymore.

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.gepa_title), style = MaterialTheme.typography.headlineSmall)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleConfig() }) {
                        Icon(
                            if (state.showConfig) Icons.Default.Close else Icons.Default.Tune,
                            contentDescription = if (state.showConfig) stringResource(Res.string.gepa_close_config) else stringResource(Res.string.gepa_open_config),
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            // ── Config Panel ─────────────────────────────────────────
            if (state.showConfig) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                stringResource(Res.string.gepa_configuration),
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.primary,
                            )
                            Spacer(Modifier.height(16.dp))

                            // Max Generations
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(Res.string.gepa_generations_label),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface,
                                    modifier = Modifier.width(120.dp),
                                )
                                Slider(
                                    value = state.maxGenerationsSetting,
                                    onValueChange = viewModel::updateMaxGenerations,
                                    valueRange = 1f..20f,
                                    steps = 18,
                                    modifier = Modifier.weight(1f),
                                    colors =
                                        SliderDefaults.colors(
                                            thumbColor = colors.primary,
                                            activeTrackColor = colors.primary,
                                        ),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${state.maxGenerationsSetting.toInt()}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.onSurface,
                                    modifier = Modifier.width(30.dp),
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            // Population Size
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(Res.string.gepa_population_label),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface,
                                    modifier = Modifier.width(120.dp),
                                )
                                Slider(
                                    value = state.populationSizeSetting,
                                    onValueChange = viewModel::updatePopulationSize,
                                    valueRange = 2f..16f,
                                    steps = 13,
                                    modifier = Modifier.weight(1f),
                                    colors =
                                        SliderDefaults.colors(
                                            thumbColor = colors.secondary,
                                            activeTrackColor = colors.secondary,
                                        ),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${state.populationSizeSetting.toInt()}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.onSurface,
                                    modifier = Modifier.width(30.dp),
                                )
                            }

                            // ── Target Selection ──
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                stringResource(Res.string.gepa_optimization_target),
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.primary,
                            )
                            Spacer(Modifier.height(8.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                GepaTargetType.entries.forEach { targetType ->
                                    FilterChip(
                                        selected = state.selectedTargetType == targetType,
                                        onClick = { viewModel.selectTargetType(targetType) },
                                        label = {
                                            Text(
                                                when (targetType) {
                                                    GepaTargetType.SYSTEM_PROMPT -> stringResource(Res.string.gepa_target_system_prompt)
                                                    GepaTargetType.SKILL -> stringResource(Res.string.gepa_target_skill)
                                                    GepaTargetType.PROFILE -> stringResource(Res.string.gepa_target_profile)
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = colors.primaryContainer,
                                            selectedLabelColor = colors.onPrimaryContainer,
                                        ),
                                    )
                                }
                            }

                            // ── Skill dropdown ──
                            if (state.selectedTargetType == GepaTargetType.SKILL) {
                                Spacer(Modifier.height(8.dp))
                                var expanded by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(
                                    expanded = expanded,
                                    onExpandedChange = { expanded = it },
                                ) {
                                    OutlinedTextField(
                                        value = state.selectedSkillName ?: stringResource(Res.string.gepa_select_skill),
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = colors.primary,
                                        ),
                                    )
                                    ExposedDropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false },
                                    ) {
                                        state.availableSkills.forEach { skill ->
                                            DropdownMenuItem(
                                                text = { Text(skill) },
                                                onClick = {
                                                    viewModel.selectSkill(skill)
                                                    expanded = false
                                                },
                                            )
                                        }
                                        if (state.availableSkills.isEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(Res.string.gepa_no_skills_available), color = colors.onSurfaceVariant) },
                                                onClick = { expanded = false },
                                                enabled = false,
                                            )
                                        }
                                    }
                                }
                            }

                            // ── Profile dropdown ──
                            if (state.selectedTargetType == GepaTargetType.PROFILE) {
                                Spacer(Modifier.height(8.dp))
                                var expanded by remember { mutableStateOf(false) }
                                val selectedName = state.availableProfiles.find { it.first == state.selectedProfileId }?.second
                                ExposedDropdownMenuBox(
                                    expanded = expanded,
                                    onExpandedChange = { expanded = it },
                                ) {
                                    OutlinedTextField(
                                        value = selectedName ?: stringResource(Res.string.gepa_select_profile),
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = colors.primary,
                                        ),
                                    )
                                    ExposedDropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false },
                                    ) {
                                        state.availableProfiles.forEach { (id, name) ->
                                            DropdownMenuItem(
                                                text = { Text(name) },
                                                onClick = {
                                                    viewModel.selectProfile(id)
                                                    expanded = false
                                                },
                                            )
                                        }
                                        if (state.availableProfiles.isEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(Res.string.gepa_no_profiles_available), color = colors.onSurfaceVariant) },
                                                onClick = { expanded = false },
                                                enabled = false,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Launch Button ─────────────────────────────────────────
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            Color(0xFF6366F1).copy(alpha = 0.15f),
                                            Color(0xFFA855F7).copy(alpha = 0.08f),
                                        ),
                                    ),
                                ).padding(24.dp),
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(Res.string.gepa_prompt_evolution),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colors.onSurface,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${state.maxGenerationsSetting.toInt()} generations × ${state.populationSizeSetting.toInt()} candidates", // TODO: i18n — complex interpolation
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                    if (state.selectedTargetType != GepaTargetType.SYSTEM_PROMPT) {
                                        val label = when (state.selectedTargetType) {
                                            GepaTargetType.SKILL -> "🔧 ${state.selectedSkillName ?: "…"}"
                                            GepaTargetType.PROFILE -> "👤 ${state.availableProfiles.find { it.first == state.selectedProfileId }?.second ?: "…"}"
                                            else -> ""
                                        }
                                        Text(
                                            "Target: $label", // TODO: i18n — dynamic interpolation
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.primary,
                                        )
                                    }
                                }

                                FilledTonalButton(
                                    onClick = { viewModel.runOptimization() },
                                    enabled = !state.isOptimizing,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.testTag("gepa_run_btn"),
                                    colors =
                                        ButtonDefaults.filledTonalButtonColors(
                                            containerColor = colors.primary,
                                            contentColor = colors.onPrimary,
                                        ),
                                ) {
                                    if (state.isOptimizing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = colors.onPrimary,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(Res.string.gepa_evolving))
                                    } else {
                                        Icon(
                                            Icons.Default.Rocket,
                                            contentDescription = stringResource(Res.string.gepa_launch_a11y),
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(Res.string.gepa_launch))
                                    }
                                }
                            }

                            // Progress bar
                            if (state.isOptimizing) {
                                Spacer(Modifier.height(16.dp))
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = colors.primary,
                                    trackColor = colors.surfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (state.currentGeneration > 0) {
                                        "${state.activeTargetLabel} — Gen ${state.currentGeneration}/${state.maxGenerations} — ${(state.progress * 100).toInt()}%"
                                    } else {
                                        "Starting optimization… ${(state.progress * 100).toInt()}%" // TODO: i18n — complex interpolation
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // ── Error ────────────────────────────────────────────────
            state.errorMessage?.let { msg ->
                item {
                    Surface(
                        color = colors.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.ErrorOutline, null, tint = colors.onErrorContainer)
                            Spacer(Modifier.width(8.dp))
                            Text(msg, color = colors.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // ── Best Result Summary ──────────────────────────────────
            if (state.runs.isNotEmpty()) {
                item {
                    val best = state.runs.maxBy { it.result.accuracy }
                    BestResultCard(result = best.result, colors = colors)
                }
            }

            // ── Run History ──────────────────────────────────────────
            if (state.runs.isNotEmpty()) {
                item {
                    Text(
                        stringResource(Res.string.gepa_run_history),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                    )
                }

                items(state.runs) { run ->
                    RunHistoryItem(run = run, colors = colors)
                }
            }

            // ── Empty state ──────────────────────────────────────────
            if (state.runs.isEmpty() && !state.isOptimizing) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Science,
                                contentDescription = null,
                                tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.size(72.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(Res.string.gepa_no_optimizations),
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurfaceVariant,
                            )
                            Text(
                                stringResource(Res.string.gepa_launch_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BestResultCard(
    result: GepaResultDto,
    colors: ColorScheme,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF22C55E).copy(alpha = 0.12f),
                                Color(0xFF86EFAC).copy(alpha = 0.05f),
                            ),
                        ),
                    ).padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.EmojiEvents,
                        contentDescription = null,
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(Res.string.gepa_best_result),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Metrics row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    MetricPill(stringResource(Res.string.gepa_accuracy), "${(result.accuracy * 100).fmt(1)}%", Color(0xFF22C55E))
                    MetricPill(stringResource(Res.string.gepa_improvement), "+${(result.improvement * 100).fmt(1)}%", Color(0xFF6366F1))
                    MetricPill(stringResource(Res.string.gepa_generation), "${result.generations}", Color(0xFFA855F7))
                    MetricPill(stringResource(Res.string.gepa_candidates), "${result.totalCandidatesEvaluated}", Color(0xFF22D3EE))
                }

                Spacer(Modifier.height(16.dp))

                // Prompt preview
                Text(
                    stringResource(Res.string.gepa_optimized_prompt),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = colors.surfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column {
                        Text(
                            text = result.bestPromptPreview,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(result.bestPromptPreview))
                                    copied = true
                                    scope.launch {
                                        delay(2000)
                                        copied = false
                                    }
                                },
                            ) {
                                Icon(
                                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = if (copied) stringResource(Res.string.gepa_copied_a11y) else stringResource(Res.string.gepa_copy_prompt_a11y),
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (copied) stringResource(Res.string.gepa_copied) else stringResource(Res.string.gepa_copy), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    color: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = color.copy(alpha = 0.15f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = color,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RunHistoryItem(
    run: GepaRun,
    colors: ColorScheme,
) {
    // Hoist contentDescription (stringResource is @Composable)
    val runDesc = "Suggestion: Run #${run.id} — ${(run.result.accuracy * 100).toInt()}% accuracy" // TODO: i18n — complex interpolation

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = Modifier.semantics {
            role = Role.Button
            contentDescription = runDesc
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Run number badge
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(colors.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "#${run.id}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onPrimaryContainer,
                )
            }

            Spacer(Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    run.targetLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                )
                Text(
                    "${run.config.maxGenerations}gen × ${run.config.populationSize}pop — ${run.result.totalCandidatesEvaluated} evaluated", // TODO: i18n — complex interpolation
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            // Accuracy
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${(run.result.accuracy * 100).fmt(1)}%",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (run.result.improvement > 0) Color(0xFF22C55E) else colors.onSurface,
                )
                if (run.result.improvement > 0) {
                    Text(
                        "+${(run.result.improvement * 100).fmt(1)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF22C55E),
                    )
                }
            }
        }
    }
}
