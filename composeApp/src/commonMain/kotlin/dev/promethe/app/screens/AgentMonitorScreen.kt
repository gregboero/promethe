package dev.promethe.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.promethe.app.screens.viewmodel.AgentMonitorViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.promethe.api.AgentExecutionEvent
import dev.promethe.api.AgentStatusDto
import dev.promethe.app.network.PrometheClient
import dev.promethe.app.util.fmtGrouped
import dev.promethe.app.util.formatTimeWithSeconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

@Composable
fun AgentMonitorScreen(client: PrometheClient) {
    val viewModel = remember { AgentMonitorViewModel(client) }
    val state by viewModel.state.collectAsState()

    val colors = MaterialTheme.colorScheme

    val selectedAgent = state.agents.find { it.id == state.selectedAgentId }
    val selectedEvents = state.events.filter { it.agentId == state.selectedAgentId }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.primary)
        }
        return
    }

    Row(modifier = Modifier.fillMaxSize().background(colors.background)) {
        // ── Left panel: agent list ──────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxHeight().weight(0.25f),
            color = colors.surface,
            tonalElevation = 1.dp,
        ) {
            Column {
                Text(
                    text = stringResource(Res.string.monitor_agents_panel_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider(color = colors.outlineVariant)

                if (state.agents.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(Res.string.monitor_no_agents), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.testTag("monitor_list")) {
                        items(state.agents, key = { it.id }) { agent ->
                            AgentListItem(
                                agent = agent,
                                isSelected = agent.id == state.selectedAgentId,
                                onClick = { viewModel.selectAgent(agent.id) },
                            )
                        }
                    }
                }
            }
        }

        // ── Center panel: execution timeline ────────────────────────────
        Surface(
            modifier = Modifier.fillMaxHeight().weight(0.5f),
            color = colors.background,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(Res.string.monitor_execution_timeline),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(12.dp))

                if (selectedEvents.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(Res.string.monitor_no_events),
                            color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(selectedEvents, key = { "${it.timestamp}-${it.type}" }) { event ->
                            TimelineNode(event = event)
                        }
                    }
                }
            }
        }

        // ── Right panel: agent details ──────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxHeight().weight(0.25f),
            color = colors.surface,
            tonalElevation = 1.dp,
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (state.pendingMcpElicitations.isNotEmpty()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Build, null, tint = colors.tertiary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(Res.string.monitor_mcp_input_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface,
                            )
                            Spacer(Modifier.width(6.dp))
                            Badge(containerColor = colors.tertiary, contentColor = colors.onTertiary) {
                                Text("${state.mcpElicitationCount}")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        state.pendingMcpElicitations.forEach { request ->
                            val requestId = request["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            McpElicitationCard(
                                request = request,
                                draft = state.mcpElicitationDrafts[requestId].orEmpty(),
                                error = state.mcpElicitationErrors[requestId],
                                isSubmitting = requestId in state.mcpElicitationSubmitting,
                                onFieldChanged = { field, value ->
                                    viewModel.updateMcpElicitationField(requestId, field, value)
                                },
                                onSubmit = { viewModel.submitMcpElicitation(requestId) },
                                onDecline = { viewModel.declineMcpElicitation(requestId) },
                            )
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                }

                // ── Provider Choice Panel ──
                if (state.pendingProviderChoices.isNotEmpty()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Build, null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(Res.string.monitor_provider_choice), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                            Spacer(Modifier.width(6.dp))
                            Badge(containerColor = Color(0xFF8B5CF6), contentColor = Color.White) {
                                Text("${state.providerChoiceCount}")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        state.pendingProviderChoices.forEach { choice ->
                            val requestId = choice["id"]?.jsonPrimitive?.content ?: ""
                            val capLabel = choice["capabilityLabel"]?.jsonPrimitive?.content ?: ""
                            val suggestedId = choice["suggestedProviderId"]?.jsonPrimitive?.content ?: ""
                            val suggestedName = choice["suggestedProviderName"]?.jsonPrimitive?.content ?: ""
                            val alternatives = choice["alternatives"]?.jsonArray?.mapNotNull { alt ->
                                val obj = alt.jsonObject
                                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                val name = obj["name"]?.jsonPrimitive?.content ?: id
                                id to name
                            } ?: emptyList()

                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF8B5CF6).copy(alpha = 0.08f)),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(capLabel, style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                                    Spacer(Modifier.height(4.dp))

                                    // Suggested provider — primary action
                                    FilledTonalButton(
                                        onClick = { viewModel.selectProvider(requestId, suggestedId) },
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Color(0xFF8B5CF6).copy(alpha = 0.15f),
                                            contentColor = Color(0xFF8B5CF6),
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(suggestedName, style = MaterialTheme.typography.labelSmall)
                                    }

                                    // Alternatives
                                    alternatives.filter { it.first != suggestedId }.forEach { (altId, altName) ->
                                        Spacer(Modifier.height(2.dp))
                                        OutlinedButton(
                                            onClick = { viewModel.selectProvider(requestId, altId) },
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        ) {
                                            Text(altName, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                        }
                                    }

                                    Spacer(Modifier.height(4.dp))
                                    // Cancel
                                    OutlinedButton(
                                        onClick = { viewModel.rejectProviderChoice(requestId) },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    ) {
                                        Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(Res.string.action_cancel), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                }

                // ── Agent Details ──
                if (selectedAgent != null) {
                    AgentDetailPanel(agent = selectedAgent)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(Res.string.monitor_select_agent), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun McpElicitationCard(
    request: JsonObject,
    draft: Map<String, String>,
    error: String?,
    isSubmitting: Boolean,
    onFieldChanged: (String, String) -> Unit,
    onSubmit: () -> Unit,
    onDecline: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val serverName = request["serverName"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val message = request["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val schema = request["requestedSchema"] as? JsonObject ?: JsonObject(emptyMap())
    val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required =
        (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            .orEmpty()

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = colors.tertiaryContainer.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("monitor_mcp_input"),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(serverName, style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(
                stringResource(Res.string.monitor_mcp_input_warning),
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
            )
            Spacer(Modifier.height(8.dp))
            properties.forEach { (name, definitionValue) ->
                val definition = definitionValue as? JsonObject ?: return@forEach
                val title = definition["title"]?.jsonPrimitive?.contentOrNull ?: name
                val label = if (name in required) "$title *" else title
                val description = definition["description"]?.jsonPrimitive?.contentOrNull
                if (definition["type"]?.jsonPrimitive?.contentOrNull == "boolean") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                        Switch(
                            checked = draft[name] == "true",
                            onCheckedChange = { onFieldChanged(name, it.toString()) },
                            enabled = !isSubmitting,
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = draft[name].orEmpty(),
                        onValueChange = { onFieldChanged(name, it) },
                        label = { Text(label) },
                        supportingText = description?.let { text -> ({ Text(text) }) },
                        singleLine = true,
                        enabled = !isSubmitting,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                }
            }
            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                Spacer(Modifier.height(4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onSubmit,
                    enabled = !isSubmitting,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(Res.string.monitor_mcp_input_submit), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onDecline,
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(Res.string.monitor_reject), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun AgentListItem(
    agent: AgentStatusDto,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val bgColor = if (isSelected) colors.primaryContainer.copy(alpha = 0.3f) else Color.Transparent

    // Hoist contentDescription before semantics (stringResource is @Composable)
    val agentDesc = "Agent ${agent.name} - ${agent.status}" // TODO: i18n — complex interpolation with runtime data

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(bgColor)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = agentDesc
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status dot
        Box(
            modifier =
                Modifier.size(10.dp).clip(CircleShape).background(
                    when (agent.status) {
                        "running" -> Color(0xFF22C55E)
                        "idle" -> Color(0xFFEAB308)
                        else -> Color(0xFFEF4444)
                    },
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = agent.name,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
            )
            Text(
                text = agent.status,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimelineNode(event: AgentExecutionEvent) {
    val colors = MaterialTheme.colorScheme
    val (icon, iconTint) =
        when (event.type) {
            "tool_call" -> Icons.Default.Build to colors.secondary
            "observation" -> Icons.Default.Visibility to colors.tertiary
            "error" -> Icons.Default.Error to colors.error
            else -> Icons.Default.PlayArrow to colors.primary
        }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Timeline rail
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(32.dp)) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            Box(
                modifier = Modifier.width(2.dp).height(24.dp).background(colors.outlineVariant),
            )
        }

        Spacer(Modifier.width(8.dp))

        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.5f)),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = event.type.replace("_", " ").replaceFirstChar { it.uppercaseChar() },
                        style = MaterialTheme.typography.labelMedium,
                        color = iconTint,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatTimestamp(event.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                event.tool?.let {
                    Text(
                        text = "tool: $it", // TODO: i18n — dynamic interpolation
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                event.content?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 4,
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentDetailPanel(agent: AgentStatusDto) {
    val colors = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(Res.string.monitor_details), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
        Spacer(Modifier.height(16.dp))

        DetailRow(stringResource(Res.string.monitor_detail_agent), agent.name)
        DetailRow(stringResource(Res.string.monitor_detail_id), agent.id)
        DetailRow(stringResource(Res.string.label_status), agent.status)
        DetailRow(stringResource(Res.string.monitor_tokens_used), agent.tokensUsed.fmtGrouped())
        agent.currentStep?.let { DetailRow(stringResource(Res.string.monitor_current_step), it) }
        agent.startedAt?.let { DetailRow(stringResource(Res.string.monitor_started), formatTimestamp(it)) }
        agent.lastActivityAt?.let { DetailRow(stringResource(Res.string.monitor_last_activity), formatTimestamp(it)) }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
    }
}

private fun formatTimestamp(millis: Long): String = formatTimeWithSeconds(millis)
