@file:Suppress("DEPRECATION")

package dev.promethe.app.screens.setup.steps

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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.promethe.app.screens.setup.ProviderOption
import dev.promethe.app.screens.setup.SetupViewModel
import dev.promethe.app.screens.setup.SetupUiState
import dev.promethe.app.screens.setup.components.ModelDropdown
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

/**
 * Step 1: Credentials configuration.
 * Shows either [ApiKeyContent] for cloud providers or [LocalProviderContent]
 * for local/proxy providers.
 */
@Composable
fun CredentialsStep(
    state: SetupUiState,
    viewModel: SetupViewModel,
) {
    val provider = state.selectedProvider ?: return

    if (provider.needsKey) {
        ApiKeyContent(
            provider = provider,
            apiKey = state.apiKey,
            onApiKeyChange = viewModel::updateApiKey,
            model = state.model,
            onModelChange = viewModel::updateModel,
        )
    } else {
        LocalProviderContent(
            provider = provider,
            localUrl = state.localUrl,
            onUrlChange = viewModel::updateLocalUrl,
            apiKey = state.apiKey,
            onApiKeyChange = viewModel::updateApiKey,
            model = state.model,
            onModelChange = viewModel::updateModel,
        )
    }
}

// ── API Key (cloud providers) ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyContent(
    provider: ProviderOption,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var keyVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Dynamic models: fetched from API
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchedEntries by remember { mutableStateOf<List<dev.promethe.app.network.ModelFetcher.ModelEntry>>(emptyList()) }
    var isFetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var hasFetched by remember { mutableStateOf(false) }

    // Auto-fetch for OpenRouter (no key needed)
    LaunchedEffect(provider.key) {
        if (provider.key == "openrouter" && !hasFetched) {
            isFetching = true
            val result =
                dev.promethe.app.network.ModelFetcher
                    .fetch(provider.key)
            fetchedModels = result.models
            fetchedEntries = result.entries
            fetchError = result.error
            isFetching = false
            hasFetched = true
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Configuration ${provider.name}",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )

        if (provider.needsKey) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { newKey ->
                    onApiKeyChange(newKey)
                    // Auto-fetch models when key looks valid (>10 chars)
                    if (newKey.length > 10 && !hasFetched) {
                        scope.launch {
                            isFetching = true
                            val result =
                                dev.promethe.app.network.ModelFetcher
                                    .fetch(provider.key, apiKey = newKey)
                            fetchedModels = result.models
                            fetchedEntries = result.entries
                            fetchError = result.error
                            isFetching = false
                            hasFetched = true
                        }
                    }
                },
                label = { Text(stringResource(Res.string.setup_credentials_api_key_format, provider.name)) },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row {
                        if (apiKey.length > 10 && !hasFetched) {
                            IconButton(onClick = {
                                scope.launch {
                                    isFetching = true
                                    val result =
                                        dev.promethe.app.network.ModelFetcher
                                            .fetch(provider.key, apiKey = apiKey)
                                    fetchedModels = result.models
                                    fetchedEntries = result.entries
                                    fetchError = result.error
                                    isFetching = false
                                    hasFetched = true
                                }
                            }) {
                                Icon(Icons.Default.Refresh, stringResource(Res.string.setup_credentials_load_models_a11y), tint = colors.primary)
                            }
                        }
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (keyVisible) stringResource(Res.string.settings_llm_hide_api_key) else stringResource(Res.string.settings_llm_show_api_key),
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("setup_api_key"),
                shape = RoundedCornerShape(12.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outline,
                        cursorColor = colors.primary,
                    ),
            )
        }

        // Model selector
        val displayModels = fetchedModels.ifEmpty { provider.models }
        val displayEntries =
            fetchedEntries.ifEmpty {
                provider.models.map { id ->
                    val p =
                        dev.promethe.app.network.ModelFetcher
                            .knownPricing(id)
                    dev.promethe.app.network.ModelFetcher
                        .ModelEntry(id, p?.first, p?.second)
                }
            }
        ModelDropdown(
            models = displayModels,
            entries = displayEntries,
            selectedModel = model,
            defaultModel = provider.defaultModel,
            onModelChange = onModelChange,
            isFetching = isFetching,
            fetchError = fetchError,
            sourceLabel = if (fetchedModels.isNotEmpty()) stringResource(Res.string.setup_credentials_source_api) else stringResource(Res.string.setup_credentials_source_default),
        )
    }
}

// ── Local / Proxy provider ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalProviderContent(
    provider: ProviderOption,
    localUrl: String,
    onUrlChange: (String) -> Unit,
    apiKey: String = "",
    onApiKeyChange: (String) -> Unit = {},
    model: String,
    onModelChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val isProxy = provider.category == "PROXY"

    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchedEntries by remember { mutableStateOf<List<dev.promethe.app.network.ModelFetcher.ModelEntry>>(emptyList()) }
    var isFetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var connectionStatus by remember { mutableStateOf<Boolean?>(null) } // null=untested
    var keyVisible by remember { mutableStateOf(false) }

    // Auto-fetch on first render
    LaunchedEffect(provider.key, localUrl) {
        if (localUrl.isNotBlank()) {
            isFetching = true
            connectionStatus = null
            val result =
                dev.promethe.app.network.ModelFetcher
                    .fetch(provider.key, localUrl = localUrl)
            fetchedModels = result.models
            fetchedEntries = result.entries
            fetchError = result.error
            connectionStatus = result.isReachable && result.models.isNotEmpty()
            isFetching = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(Res.string.setup_credentials_provider_config_title, provider.name),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )

        if (isProxy) {
            Text(
                stringResource(Res.string.setup_credentials_litellm_description),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }

        // URL field with connection indicator
        OutlinedTextField(
            value = localUrl,
            onValueChange = onUrlChange,
            label = { Text(if (isProxy) stringResource(Res.string.setup_credentials_proxy_url_label) else stringResource(Res.string.setup_credentials_provider_url_label, provider.name)) },
            singleLine = true,
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        isFetching -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = colors.primary,
                                strokeWidth = 2.dp,
                            )
                        }

                        connectionStatus == true -> {
                            Icon(
                                Icons.Default.CheckCircle,
                                stringResource(Res.string.setup_credentials_connected_a11y),
                                tint = Color(0xFF22C55E),
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        connectionStatus == false -> {
                            Icon(
                                Icons.Default.Error,
                                stringResource(Res.string.setup_credentials_unreachable_a11y),
                                tint = colors.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = {
                        scope.launch {
                            isFetching = true
                            connectionStatus = null
                            val result =
                                dev.promethe.app.network.ModelFetcher
                                    .fetch(provider.key, localUrl = localUrl)
                            fetchedModels = result.models
                            fetchedEntries = result.entries
                            fetchError = result.error
                            connectionStatus = result.isReachable && result.models.isNotEmpty()
                            isFetching = false
                        }
                    }) {
                        Icon(Icons.Default.Refresh, stringResource(Res.string.setup_credentials_test_a11y), tint = colors.primary)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.outline,
                    cursorColor = colors.primary,
                ),
        )

        // Optional API key for PROXY (LITELLM_MASTER_KEY)
        if (isProxy) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text(stringResource(Res.string.setup_credentials_optional_key_hint)) },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (keyVisible) stringResource(Res.string.settings_llm_hide_api_key) else stringResource(Res.string.settings_llm_show_api_key),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outline,
                        cursorColor = colors.primary,
                    ),
            )
        }

        // Connection status message
        if (connectionStatus == true && fetchedModels.isNotEmpty()) {
            Text(
                stringResource(Res.string.setup_credentials_models_detected_format, fetchedModels.size),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF22C55E),
            )
        } else if (connectionStatus == false) {
            Text(
                fetchError ?: stringResource(Res.string.setup_credentials_provider_unreachable_format, provider.name),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
            if (provider.key == "ollama") {
                Text(
                    stringResource(Res.string.setup_credentials_install_ollama_hint, provider.defaultModel),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            } else if (provider.key == "litellm") {
                Text(
                    stringResource(Res.string.setup_credentials_run_litellm_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        // Model selector
        val displayModels = fetchedModels.ifEmpty { provider.models }
        val displayEntries =
            fetchedEntries.ifEmpty {
                provider.models.map { id ->
                    val p =
                        dev.promethe.app.network.ModelFetcher
                            .knownPricing(id)
                    dev.promethe.app.network.ModelFetcher
                        .ModelEntry(id, p?.first, p?.second)
                }
            }
        ModelDropdown(
            models = displayModels,
            entries = displayEntries,
            selectedModel = model,
            defaultModel = provider.defaultModel,
            onModelChange = onModelChange,
            isFetching = isFetching,
            fetchError = fetchError,
            sourceLabel = if (fetchedModels.isNotEmpty()) stringResource(Res.string.setup_credentials_source_installed) else stringResource(Res.string.setup_credentials_source_default),
        )
    }
}
