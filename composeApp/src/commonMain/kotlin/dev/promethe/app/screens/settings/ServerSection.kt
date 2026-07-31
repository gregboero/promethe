package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@Composable
fun ServerSection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    SectionHeader(stringResource(Res.string.settings_server_title), state.serverExpanded, stringResource(Res.string.settings_server_description)) {
        onUpdate { copy(serverExpanded = !serverExpanded) }
    }
    AnimatedVisibility(state.serverExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.gatewayUrl,
                onValueChange = { newUrl ->
                    onUpdate {
                        copy(
                            gatewayUrl = newUrl,
                            gatewayUrlError = !newUrl.startsWith("http://") && !newUrl.startsWith("https://"),
                        )
                    }
                },
                label = { Text(stringResource(Res.string.settings_server_gateway_url_label)) },
                isError = state.gatewayUrlError,
                supportingText = if (state.gatewayUrlError) {
                    { Text(stringResource(Res.string.settings_server_gateway_url_error)) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("settings_gateway_url"),
                shape = RoundedCornerShape(12.dp),
                colors = settingsFieldColors(colors),
            )
        }
    }
}
