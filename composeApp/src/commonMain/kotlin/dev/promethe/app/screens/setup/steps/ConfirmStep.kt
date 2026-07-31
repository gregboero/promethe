package dev.promethe.app.screens.setup.steps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.promethe.app.screens.setup.SetupUiState
import dev.promethe.app.screens.setup.components.ConfirmRow
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

/**
 * Step 5: Confirmation summary before launching Promethe.
 * Displays provider, model, gateway URL, and active integrations.
 */
@Composable
fun ConfirmStep(state: SetupUiState) {
    val colors = MaterialTheme.colorScheme
    val provider = state.selectedProvider ?: return

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(Res.string.setup_confirm_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )

        Surface(
            color = colors.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfirmRow(stringResource(Res.string.setup_confirm_provider_label), provider.name)
                ConfirmRow(stringResource(Res.string.setup_confirm_model_label), state.model)
                ConfirmRow(stringResource(Res.string.setup_confirm_gateway_label), "http://localhost:8080")
                ConfirmRow(stringResource(Res.string.setup_confirm_auth_label), stringResource(Res.string.setup_confirm_auth_auto_gen))
                if (state.activeIntegrations.isNotEmpty()) {
                    ConfirmRow(stringResource(Res.string.setup_confirm_services_label), state.activeIntegrations.joinToString(", "))
                }
            }
        }

        Text(
            stringResource(Res.string.setup_confirm_config_files_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}
