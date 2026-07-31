package dev.promethe.app.screens.setup.steps

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.promethe.app.screens.setup.SetupViewModel
import dev.promethe.app.screens.setup.SetupUiState
import dev.promethe.app.screens.setup.components.ContextFileSection
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

/**
 * Step 3: Project context files.
 * Allows editing SOUL.md, AGENTS.md, and .promethe.md.
 */
@Composable
fun ProjectContextStep(
    state: SetupUiState,
    viewModel: SetupViewModel,
) {
    val colors = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(Res.string.setup_context_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
        Text(
            stringResource(Res.string.setup_context_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))

        ContextFileSection(
            icon = Icons.Default.Psychology,
            title = "SOUL.md",
            subtitle = stringResource(Res.string.setup_context_soul_subtitle),
            description = stringResource(Res.string.setup_context_soul_desc),
            value = state.soulMd,
            onValueChange = viewModel::updateSoulMd,
            placeholder = stringResource(Res.string.setup_context_soul_placeholder),
        )

        ContextFileSection(
            icon = Icons.Default.Groups,
            title = "AGENTS.md",
            subtitle = stringResource(Res.string.setup_context_agents_subtitle),
            description = stringResource(Res.string.setup_context_agents_desc),
            value = state.agentsMd,
            onValueChange = viewModel::updateAgentsMd,
            placeholder = stringResource(Res.string.setup_context_agents_placeholder),
        )

        ContextFileSection(
            icon = Icons.Default.Settings,
            title = ".promethe.md",
            subtitle = stringResource(Res.string.setup_context_promethe_subtitle),
            description = stringResource(Res.string.setup_context_promethe_desc),
            value = state.prometheMd,
            onValueChange = viewModel::updatePrometheMd,
            placeholder = stringResource(Res.string.setup_context_promethe_placeholder),
        )
    }
}
