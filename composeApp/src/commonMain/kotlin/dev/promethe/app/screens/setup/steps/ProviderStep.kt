package dev.promethe.app.screens.setup.steps

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import dev.promethe.app.screens.setup.PROVIDERS
import dev.promethe.app.screens.setup.ProviderOption
import dev.promethe.app.screens.setup.components.ProviderCard
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

/**
 * Step 0: LLM provider selection.
 * Groups providers by category (LOCAL / PROXY / CLOUD).
 */
@Composable
fun ProviderStep(
    selected: ProviderOption?,
    onSelect: (ProviderOption) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(Res.string.setup_provider_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(4.dp))

        // Local
        Text(stringResource(Res.string.setup_provider_local), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        PROVIDERS.filter { it.category == "LOCAL" }.forEach { provider ->
            ProviderCard(provider, selected?.key == provider.key) { onSelect(provider) }
        }

        Spacer(Modifier.height(8.dp))

        // Proxy — self-hosted gateway
        Text(stringResource(Res.string.setup_provider_proxy), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        PROVIDERS.filter { it.category == "PROXY" }.forEach { provider ->
            ProviderCard(provider, selected?.key == provider.key, isRecommended = true) { onSelect(provider) }
        }

        Spacer(Modifier.height(8.dp))

        // Cloud — direct API
        Text(stringResource(Res.string.setup_provider_cloud), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        PROVIDERS.filter { it.category == "CLOUD" }.forEach { provider ->
            ProviderCard(provider, selected?.key == provider.key) { onSelect(provider) }
        }
    }
}
