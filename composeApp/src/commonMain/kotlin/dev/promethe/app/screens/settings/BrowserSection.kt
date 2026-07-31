package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

private val BROWSER_BACKEND_KEYS = listOf("", "cdp", "browserbase")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    val configured = state.browserBackend.isNotBlank()

    val browserBackends = listOf(
        "" to stringResource(Res.string.settings_browser_disabled),
        "cdp" to stringResource(Res.string.settings_browser_chrome_cdp),
        "browserbase" to stringResource(Res.string.settings_browser_browserbase),
    )

    SectionHeader(
        stringResource(Res.string.settings_browser_title),
        state.browserExpanded,
        stringResource(Res.string.settings_browser_description),
        statusColor = if (configured) Color(0xFF22C55E) else Color(0xFF9E9E9E),
    ) { onUpdate { copy(browserExpanded = !browserExpanded) } }

    AnimatedVisibility(state.browserExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ── Backend selector ─────────────────────────────────────────────
            Text(stringResource(Res.string.settings_browser_backend), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().testTag("settings_browser_backend")) {
                browserBackends.forEachIndexed { index, (key, label) ->
                    SegmentedButton(
                        selected = state.browserBackend == key,
                        onClick = { onUpdate { copy(browserBackend = key) } },
                        shape = SegmentedButtonDefaults.itemShape(index, browserBackends.size),
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // ── CDP fields ───────────────────────────────────────────────────
            AnimatedVisibility(state.browserBackend == "cdp") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.browserCdpHost,
                            onValueChange = { onUpdate { copy(browserCdpHost = it) } },
                            label = { Text(stringResource(Res.string.settings_browser_cdp_host)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("settings_browser_cdp_host"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                            supportingText = { Text("localhost") },
                        )
                        OutlinedTextField(
                            value = state.browserCdpPort,
                            onValueChange = { onUpdate { copy(browserCdpPort = it) } },
                            label = { Text(stringResource(Res.string.settings_browser_cdp_port)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.5f).testTag("settings_browser_cdp_port"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                            supportingText = { Text("9222") },
                        )
                    }
                }
            }

            // ── Browserbase fields ───────────────────────────────────────────
            AnimatedVisibility(state.browserBackend == "browserbase") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.browserbasApiKey,
                        onValueChange = { onUpdate { copy(browserbasApiKey = it) } },
                        label = { Text(stringResource(Res.string.settings_browser_api_key)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("settings_browserbase_api_key"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                    )
                    OutlinedTextField(
                        value = state.browserbaseProjectId,
                        onValueChange = { onUpdate { copy(browserbaseProjectId = it) } },
                        label = { Text(stringResource(Res.string.settings_browser_project_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_browserbase_project_id"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                    )
                }
            }
        }
    }
}
