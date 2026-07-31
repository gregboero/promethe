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
fun WebSearchSection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    val configured = state.tavilyApiKey.isNotBlank() || state.searxngUrl.isNotBlank()

    SectionHeader(
        stringResource(Res.string.settings_web_search_title),
        state.webSearchExpanded,
        stringResource(Res.string.settings_web_search_description),
        statusColor = if (configured) Color(0xFF22C55E) else Color(0xFF9E9E9E),
    ) { onUpdate { copy(webSearchExpanded = !webSearchExpanded) } }

    AnimatedVisibility(state.webSearchExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IntegrationCard(
                title = "Tavily",
                icon = Icons.Default.Search,
                configured = state.tavilyApiKey.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_web_search_tavily_desc),
                accentColor = Color(0xFF6366F1),
            ) {
                OutlinedTextField(
                    value = state.tavilyApiKey,
                    onValueChange = { onUpdate { copy(tavilyApiKey = it) } },
                    label = { Text(stringResource(Res.string.settings_web_search_tavily_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_tavily_api_key"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_web_search_tavily_hint)) },
                )
            }

            IntegrationCard(
                title = "SearXNG",
                icon = Icons.Default.TravelExplore,
                configured = state.searxngUrl.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_web_search_searxng_desc),
                accentColor = Color(0xFF0EA5E9),
            ) {
                OutlinedTextField(
                    value = state.searxngUrl,
                    onValueChange = { onUpdate { copy(searxngUrl = it) } },
                    label = { Text(stringResource(Res.string.settings_web_search_searxng_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_searxng_url"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_web_search_searxng_hint)) },
                )
            }

            IntegrationCard(
                title = "Twitter / X",
                icon = Icons.Default.Tag,
                configured = state.twitterBearerToken.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_web_search_twitter_desc),
                accentColor = Color(0xFF1D9BF0),
            ) {
                OutlinedTextField(
                    value = state.twitterBearerToken,
                    onValueChange = { onUpdate { copy(twitterBearerToken = it) } },
                    label = { Text(stringResource(Res.string.settings_web_search_twitter_token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_twitter_bearer_token"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_web_search_twitter_hint)) },
                )
            }
        }
    }
}
