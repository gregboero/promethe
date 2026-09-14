package dev.promethe.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.promethe.app.screens.viewmodel.StatsViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.promethe.api.*
import dev.promethe.app.network.PrometheClient
import dev.promethe.app.util.fmt
import dev.promethe.app.util.fmtGrouped
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(client: PrometheClient) {
    val viewModel = remember { StatsViewModel(client) }
    val state by viewModel.state.collectAsState()

    val colors = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(Res.string.stats_dashboard), style = MaterialTheme.typography.headlineSmall)
                        // Health indicator dot
                        val healthOk = state.systemStatus != null
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (healthOk) Color(0xFF22C55E).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (healthOk) "●" else "○",
                                    color = if (healthOk) Color(0xFF22C55E) else Color(0xFFEF4444),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Text(
                                    if (healthOk) stringResource(Res.string.stats_online) else stringResource(Res.string.stats_offline),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (healthOk) Color(0xFF22C55E) else Color(0xFFEF4444),
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadAll() }, modifier = Modifier.testTag("stats_refresh")) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.stats_refresh_a11y))
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
        if (state.isLoadingStats) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.primary)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("stats_screen"),
        ) {
            // ── KPI Grid ────────────────────────────────────────────────
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().height(140.dp),
            ) {
                item {
                    KpiCard(
                        icon = Icons.Default.Token,
                        label = stringResource(Res.string.stats_total_tokens),
                        value = state.stats.totalTokens.fmtGrouped(),
                        gradientStart = Color(0xFF6366F1),
                        gradientEnd = Color(0xFF818CF8),
                    )
                }
                item {
                    KpiCard(
                        icon = Icons.Default.Http,
                        label = stringResource(Res.string.stats_requests),
                        value = state.stats.totalRequests.fmtGrouped(),
                        gradientStart = Color(0xFFA855F7),
                        gradientEnd = Color(0xFFD8B4FE),
                    )
                }
                item {
                    KpiCard(
                        icon = Icons.Default.AttachMoney,
                        label = stringResource(Res.string.stats_estimated_cost),
                        value = "$${state.stats.estimatedCost.fmt(2)}",
                        gradientStart = Color(0xFF22D3EE),
                        gradientEnd = Color(0xFF67E8F9),
                    )
                }
                item {
                    KpiCard(
                        icon = Icons.Default.ThumbUp,
                        label = stringResource(Res.string.stats_avg_feedback),
                        value = "${state.stats.avgFeedback.fmt(1)} / 1.0",
                        gradientStart = Color(0xFF22C55E),
                        gradientEnd = Color(0xFF86EFAC),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── System Status ────────────────────────────────────────────
            state.systemStatus?.let { status ->
                SectionTitle(stringResource(Res.string.stats_system))
                Spacer(Modifier.height(8.dp))

                // Runtime info card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val defaultVal = stringResource(Res.string.stats_default_value)
                        StatusRow("⏱ " + stringResource(Res.string.stats_uptime), status.runtime.uptimeHuman)
                        StatusRow("🤖 " + stringResource(Res.string.stats_model_label), status.runtime.model ?: defaultVal)
                        StatusRow("🔌 " + stringResource(Res.string.stats_provider_label), status.runtime.provider ?: defaultVal)
                        StatusRow("⚙ " + stringResource(Res.string.stats_backend), status.runtime.executionBackend ?: defaultVal)
                        StatusRow("📦 " + stringResource(Res.string.stats_version), status.runtime.version)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // LLM + Cache row
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(Res.string.stats_llm), style = MaterialTheme.typography.titleSmall, color = colors.primary)
                            Spacer(Modifier.height(4.dp))
                            StatusRow(stringResource(Res.string.stats_requests), status.llm.totalRequests.fmtGrouped())
                            StatusRow(stringResource(Res.string.stats_prompt_tokens), status.llm.totalPromptTokens.fmtGrouped())
                            StatusRow(stringResource(Res.string.stats_completion_tokens), status.llm.totalCompletionTokens.fmtGrouped())
                            StatusRow(stringResource(Res.string.stats_cost_usd), "$${status.llm.totalCostUsd.fmt(4)}")
                        }
                    }
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(Res.string.stats_cache), style = MaterialTheme.typography.titleSmall, color = Color(0xFF22C55E))
                            Spacer(Modifier.height(4.dp))
                            StatusRow(stringResource(Res.string.stats_hits), status.llm.cache.hits.toString())
                            StatusRow(stringResource(Res.string.stats_misses), status.llm.cache.misses.toString())
                            StatusRow(stringResource(Res.string.stats_size), status.llm.cache.size.toString())
                            StatusRow(stringResource(Res.string.stats_rate), "${(status.llm.cache.hitRate * 100).fmt(1)}%")
                            StatusRow(stringResource(Res.string.stats_cache_read_tokens), status.llm.cache.readTokens.fmtGrouped())
                            StatusRow(stringResource(Res.string.stats_cache_write_tokens), status.llm.cache.writeTokens.fmtGrouped())
                            StatusRow(
                                stringResource(Res.string.stats_prefix_reuse),
                                "${status.llm.cache.prefixReuseHits}/${status.llm.cache.prefixReuseHits + status.llm.cache.prefixReuseMisses}",
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Scheduler + Plugins + Memory row
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    status.scheduler?.let { sched ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.surface),
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(Res.string.stats_scheduler), style = MaterialTheme.typography.titleSmall, color = Color(0xFFF59E0B))
                                Spacer(Modifier.height(4.dp))
                                StatusRow(stringResource(Res.string.stats_state), if (sched.running) stringResource(Res.string.stats_scheduler_active) else stringResource(Res.string.stats_scheduler_stopped))
                                StatusRow(stringResource(Res.string.stats_tasks), sched.taskCount.toString())
                            }
                        }
                    }
                    status.plugins?.let { plug ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.surface),
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(Res.string.stats_plugins), style = MaterialTheme.typography.titleSmall, color = Color(0xFFA855F7))
                                Spacer(Modifier.height(4.dp))
                                StatusRow(stringResource(Res.string.stats_total), plug.total.toString())
                                StatusRow(stringResource(Res.string.stats_active_plugins), plug.enabled.toString())
                                StatusRow(stringResource(Res.string.stats_tools), plug.tools.toString())
                            }
                        }
                    }
                    status.memory?.let { mem ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.surface),
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(Res.string.stats_memory), style = MaterialTheme.typography.titleSmall, color = Color(0xFF3B82F6))
                                Spacer(Modifier.height(4.dp))
                                StatusRow(stringResource(Res.string.stats_provider_label), mem.provider)
                                StatusRow(stringResource(Res.string.stats_facts), mem.factCount.toString())
                            }
                        }
                    }
                }

                // Providers section
                state.providers?.let { prov ->
                    if (prov.providers.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        SectionTitle(stringResource(Res.string.stats_llm_providers))
                        Spacer(Modifier.height(8.dp))
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            prov.providers.forEach { p ->
                                val statusColor = when (p.status) {
                                    "configured" -> Color(0xFF22C55E)
                                    "no_keys" -> Color(0xFFEF4444)
                                    else -> colors.onSurfaceVariant
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = statusColor.copy(alpha = 0.1f),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            if (p.status == "configured") "●" else "○",
                                            color = statusColor,
                                        )
                                        Column {
                                            Text(p.name, style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                                            Text(
                                                stringResource(Res.string.stats_key_pool_size, p.keyPoolSize),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Hooks section
                if (status.hooks.registeredCount > 0) {
                    SectionTitle("Hooks (${status.hooks.registeredCount})")
                    Spacer(Modifier.height(8.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        status.hooks.hooks.forEach { hook ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFF59E0B).copy(alpha = 0.1f),
                            ) {
                                Text(
                                    "⚡ $hook",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFF59E0B),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            // ── GEPA Section ──────────────────────────────────────────────────
            SectionTitle(stringResource(Res.string.stats_gepa_title))
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { viewModel.runGepaOptimization() },
                enabled = !state.isOptimizing,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = colors.secondary,
                        contentColor = colors.onSecondary,
                    ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("stats_gepa_optimize"),
            ) {
                if (state.isOptimizing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.onSecondary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.stats_optimizing))
                } else {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.stats_optimize_prompt))
                }
            }

            state.gepaResult?.let { result ->
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(Res.string.stats_optimization_results), style = MaterialTheme.typography.titleSmall, color = colors.primary)
                        Spacer(Modifier.height(12.dp))
                        GepaDetailRow(stringResource(Res.string.stats_accuracy), "${(result.accuracy * 100).fmt(1)}%")
                        GepaDetailRow(stringResource(Res.string.stats_improvement), "+${(result.improvement * 100).fmt(1)}%")
                        GepaDetailRow(stringResource(Res.string.stats_generations), "${result.generations}")
                        GepaDetailRow(stringResource(Res.string.stats_candidates_evaluated), "${result.totalCandidatesEvaluated}")
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = colors.outlineVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(Res.string.stats_best_prompt_preview), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = colors.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = result.bestPromptPreview,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KpiCard(
    icon: ImageVector,
    label: String,
    value: String,
    gradientStart: Color,
    gradientEnd: Color,
) {
    val kpiA11y = stringResource(Res.string.stats_kpi_a11y, label, value)
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics { contentDescription = kpiA11y },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(gradientStart.copy(alpha = 0.15f), gradientEnd.copy(alpha = 0.06f)),
                        ),
                    ).padding(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = gradientStart,
                    modifier = Modifier.size(24.dp),
                )
                Column {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GepaDetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
