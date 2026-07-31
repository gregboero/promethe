package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@Composable
fun HomeAssistantSection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    val configured = state.haUrl.isNotBlank()

    SectionHeader(
        stringResource(Res.string.settings_ha_title),
        state.haExpanded,
        stringResource(Res.string.settings_ha_description),
        statusColor = if (configured) Color(0xFF22C55E) else Color(0xFF9E9E9E),
    ) { onUpdate { copy(haExpanded = !haExpanded) } }

    AnimatedVisibility(state.haExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IntegrationCard(
                title = stringResource(Res.string.settings_ha_card_title),
                icon = Icons.Default.Home,
                configured = configured,
                colors = colors,
                description = stringResource(Res.string.settings_ha_card_description),
                accentColor = Color(0xFF18BCF2),
            ) {
                OutlinedTextField(
                    value = state.haUrl,
                    onValueChange = { onUpdate { copy(haUrl = it) } },
                    label = { Text(stringResource(Res.string.settings_ha_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_ha_url"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_ha_url_hint)) },
                )
                OutlinedTextField(
                    value = state.haToken,
                    onValueChange = { onUpdate { copy(haToken = it) } },
                    label = { Text(stringResource(Res.string.settings_ha_token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_ha_token"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_ha_token_hint)) },
                )
            }
        }
    }
}
