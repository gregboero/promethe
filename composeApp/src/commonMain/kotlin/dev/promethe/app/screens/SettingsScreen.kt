package dev.promethe.app.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*
import dev.promethe.app.config.AppCredentials
import dev.promethe.app.network.PrometheClient
import dev.promethe.app.screens.settings.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings screen — modular orchestrator.
 *
 * Each settings category is a standalone composable in the
 * `dev.promethe.app.screens.settings` package. This screen
 * holds the [SettingsViewModel] and wires each section to
 * the shared [SettingsState] via `onUpdate { copy(…) }`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    client: PrometheClient? = null,
    onCredentialsChanged: ((AppCredentials) -> Unit)? = null,
    onNavigateToAgents: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    // ── ViewModel (manual instantiation until Koin is wired) ──
    val viewModel = remember { SettingsViewModel(client) }
    val state by viewModel.state.collectAsState()

    // Local save feedback
    var saved by remember { mutableStateOf(false) }

    // Extracted save action for reuse (button + Ctrl+S)
    val saveAction: () -> Unit = {
        if (!state.gatewayUrlError && !state.isSaving) {
            viewModel.save(onCredentialsChanged)
            saved = true
            scope.launch {
                delay(3000)
                saved = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.settings_title), style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.onBackground,
                ),
            )
        },
        containerColor = colors.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.S) {
                        saveAction()
                        true
                    } else {
                        false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Server ──
            ServerSection(
                state = state,
                onUpdate = viewModel::update,
            )

            // ── LLM Provider ──
            LlmSection(
                state = state,
                onUpdate = viewModel::update,
                client = client,
                scope = scope,
                onFetchVoice = viewModel::fetchVoiceModelsAndVoices,
                onPreviewVoice = viewModel::previewVoice,
            )
            // ── Memory ──
            MemorySection(
                state = state,
                onUpdate = viewModel::update,
            )

            // ── RAG ──
            RagSection(
                state = state,
                onUpdate = viewModel::update,
                client = client,
                scope = scope,
            )

            // ── Execution ──
            ExecutionSection(
                state = state,
                onUpdate = viewModel::update,
            )

            // ── Context Window ──
            ContextWindowSection(
                state = state,
                onUpdate = viewModel::update,
            )

            // ── Observability ──
            ObservabilitySection(
                state = state,
                onUpdate = viewModel::update,
            )

            // ── Security ──
            SecuritySection(
                state = state,
                onUpdate = viewModel::update,
                onSaveRemoteAccess = viewModel::saveRemoteAccess,
            )

            // ── Sandbox ──
            SandboxSection(
                state = state,
                onToggle = { viewModel.update { copy(sandboxExpanded = !sandboxExpanded) } },
                onSelectMode = viewModel::updateSandboxMode,
                onSelfTest = viewModel::runSandboxSelfTest,
                onRefresh = viewModel::loadSandbox,
            )

            // ── GEPA ──
            GepaSection(
                state = state,
                onUpdate = viewModel::update,
            )

            // ── Integrations & Channels ──
            IntegrationsSection(
                state = state,
                onUpdate = viewModel::update,
            )

            // ── Web Search ──
            WebSearchSection(
                state = state,
                onUpdate = viewModel::update,
            )

            // ── Browser Automation ──
            BrowserSection(
                state = state,
                onUpdate = viewModel::update,
            )

            // ── Home Assistant ──
            HomeAssistantSection(
                state = state,
                onUpdate = viewModel::update,
            )

            // ── Personality ──
            PersonalitySection(
                state = state,
                onUpdate = viewModel::update,
            )

            // ── Context Files ──
            ContextFilesSection(
                state = state,
                onUpdate = viewModel::update,
                client = client,
                scope = scope,
            )

            // ── Advanced ──
            AdvancedSection(
                state = state,
                onUpdate = viewModel::update,
            )

            // ══════════════════════════════════════════════════════════════
            // Save Button
            // ══════════════════════════════════════════════════════════════
            Spacer(Modifier.height(8.dp))
            val settingsSaveA11y = stringResource(Res.string.settings_save_a11y)
            Button(
                onClick = saveAction,
                enabled = !state.gatewayUrlError && !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("settings_save")
                    .semantics { contentDescription = settingsSaveA11y },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                ),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = colors.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.settings_saving))
                } else {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (saved) stringResource(Res.string.settings_saved) else stringResource(Res.string.settings_save_button))
                }
            }

            if (saved) {
                Text(
                    stringResource(Res.string.settings_credentials_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primary,
                )
            }

            state.saveError?.let { err ->
                Text(
                    stringResource(Res.string.settings_error_format, err),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
