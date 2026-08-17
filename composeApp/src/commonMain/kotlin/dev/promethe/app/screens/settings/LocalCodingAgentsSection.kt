package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.promethe.api.CapabilityAuthentication
import dev.promethe.api.CapabilityAvailability
import dev.promethe.api.CapabilityDescriptor
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.Res
import promethe.composeapp.generated.resources.settings_local_agents_auth
import promethe.composeapp.generated.resources.settings_local_agents_description
import promethe.composeapp.generated.resources.settings_local_agents_empty
import promethe.composeapp.generated.resources.settings_local_agents_refresh
import promethe.composeapp.generated.resources.settings_local_agents_title
import promethe.composeapp.generated.resources.settings_local_agents_version

@Composable
fun LocalCodingAgentsSection(
    state: SettingsState,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val statusColor =
        when {
            state.localCodingAgents.any { it.availability == CapabilityAvailability.AVAILABLE } -> Color(0xFF43A047)
            state.localCodingAgents.any { it.availability == CapabilityAvailability.MISSING_CONFIGURATION } -> colors.tertiary
            else -> colors.outline
        }
    SectionHeader(
        title = stringResource(Res.string.settings_local_agents_title),
        expanded = state.localCodingAgentsExpanded,
        description = stringResource(Res.string.settings_local_agents_description),
        statusColor = statusColor,
        onClick = onToggle,
    )
    AnimatedVisibility(state.localCodingAgentsExpanded) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.localCodingAgentsLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.settings_local_agents_refresh))
                    }
                }
            }
            state.localCodingAgentsError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
            if (!state.localCodingAgentsLoading && state.localCodingAgents.isEmpty()) {
                Text(
                    stringResource(Res.string.settings_local_agents_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            state.localCodingAgents.forEachIndexed { index, capability ->
                if (index > 0) HorizontalDivider()
                LocalCodingAgentStatusRow(capability)
            }
        }
    }
}

@Composable
private fun LocalCodingAgentStatusRow(capability: CapabilityDescriptor) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(capability.name, style = MaterialTheme.typography.titleSmall)
        capability.runtimeVersion?.let { version ->
            Text(
                "${stringResource(Res.string.settings_local_agents_version)}: $version",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Text(
            "${stringResource(Res.string.settings_local_agents_auth)}: ${capability.authentication.displayName()}",
            style = MaterialTheme.typography.bodySmall,
            color =
                if (capability.authentication == CapabilityAuthentication.AUTHENTICATED) {
                    Color(0xFF2E7D32)
                } else {
                    colors.onSurfaceVariant
                },
        )
        capability.limitations.forEach { limitation ->
            Text(limitation, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

private fun CapabilityAuthentication.displayName(): String = name.lowercase().replace('_', ' ')
