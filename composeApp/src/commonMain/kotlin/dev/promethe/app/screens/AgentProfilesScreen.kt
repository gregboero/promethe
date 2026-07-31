package dev.promethe.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.promethe.api.AgentProfile
import dev.promethe.api.AgentProfileRequest
import dev.promethe.api.ProviderRegistry
import dev.promethe.api.ReasoningEffort
import dev.promethe.api.providers.ProviderAvailability
import dev.promethe.app.network.ModelFetcher
import dev.promethe.app.network.PrometheClient
import dev.promethe.app.screens.setup.components.ModelDropdown
import dev.promethe.app.screens.viewmodel.AgentProfilesViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.promethe.app.util.fmt
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@Composable
fun AgentProfilesScreen(client: PrometheClient) {
    val viewModel = remember { AgentProfilesViewModel(client) }
    val state by viewModel.state.collectAsState()
    val colors = MaterialTheme.colorScheme

    var providerDescriptors by remember { mutableStateOf(emptyList<dev.promethe.api.providers.ProviderDescriptor>()) }
    LaunchedEffect(client) {
        providerDescriptors = runCatching { client.getProviders() }.getOrDefault(emptyList())
    }
    val configuredProviderKeys =
        providerDescriptors
            .filter { it.availability == ProviderAvailability.AVAILABLE || it.availability == ProviderAvailability.STALE }
            .map { it.id }
            .ifEmpty { ProviderRegistry.providers.filter { !it.needsKey }.map { it.key } }

    if (state.showEditor) {
        ProfileEditorDialog(
            profile = state.editingProfile,
            client = client,
            configuredProviderKeys =
                (configuredProviderKeys + listOfNotNull(state.editingProfile?.provider)).distinct(),
            availableSkills = state.skills,
            availableTools = state.tools,
            onDismiss = { viewModel.dismissEditor() },
            onSave = { request -> viewModel.saveProfile(request) },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(colors.background).padding(24.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    stringResource(Res.string.agents_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(Res.string.agents_profiles_count, state.profiles.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.loadProfiles() }, modifier = Modifier.testTag("agents_refresh")) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.agents_refresh_button))
                }
                Button(
                    onClick = { viewModel.showCreateDialog() },
                    modifier = Modifier.testTag("agents_create_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.agents_create_a11y), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.agents_new_profile))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        state.error?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = colors.errorContainer),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = colors.error)
                    Spacer(Modifier.width(8.dp))
                    Text(it, color = colors.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.primary)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(360.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("agents_list"),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        onEdit = { viewModel.showEditDialog(profile) },
                        onDelete = { viewModel.deleteProfile(profile.id) },
                        onDuplicate = { viewModel.duplicateProfile(profile) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: AgentProfile,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (profile.isSystem) colors.primary.copy(alpha = 0.5f) else colors.outlineVariant.copy(alpha = 0.3f)

    val cardA11y = stringResource(Res.string.agents_card_a11y, profile.name)
    val duplicateA11y = stringResource(Res.string.agents_duplicate_a11y)
    val editA11y = stringResource(Res.string.agents_edit_agent_a11y, profile.name)
    val deleteA11y = stringResource(Res.string.agents_delete_agent_a11y, profile.name)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agents_card_${profile.id}")
            .semantics {
                role = Role.Button
                contentDescription = cardA11y
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant.copy(alpha = 0.4f)),
        border =
            androidx.compose.foundation.BorderStroke(
                width = if (profile.isSystem) 2.dp else 1.dp,
                color = borderColor,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Provider icon dot
                    val providerColor =
                        when (profile.provider) {
                            "openai" -> Color(0xFF10A37F)
                            "anthropic" -> Color(0xFFD4A27F)
                            "google" -> Color(0xFF4285F4)
                            "ollama" -> Color(0xFF0EA5E9)
                            "openrouter" -> Color(0xFF6366F1)
                            else -> colors.primary
                        }
                    Box(
                        modifier = Modifier.size(12.dp).clip(CircleShape).background(providerColor),
                    )
                    Spacer(Modifier.width(10.dp))

                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )

                    if (profile.isSystem) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = colors.primary.copy(alpha = 0.15f),
                        ) {
                            Text(
                                stringResource(Res.string.agents_system_badge),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    if (profile.ephemeral) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = colors.tertiary.copy(alpha = 0.15f),
                        ) {
                            Text(
                                stringResource(Res.string.agents_ephemeral_badge),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.tertiary,
                            )
                        }
                    }
                }

                // Actions
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Set-default removed — agents are system or custom
                    IconButton(onClick = onDuplicate, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = duplicateA11y,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = editA11y,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (!profile.isSystem) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp).testTag("agents_delete_${profile.id}")) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = deleteA11y,
                                tint = colors.error.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Info row
            val defaultValue = stringResource(Res.string.agents_default_value)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoChip(label = stringResource(Res.string.agents_provider_chip), value = profile.provider.ifBlank { defaultValue })
                InfoChip(label = stringResource(Res.string.agents_model_chip), value = profile.model.ifBlank { defaultValue })
                InfoChip(label = stringResource(Res.string.agents_temp_chip), value = profile.temperature.fmt(1))
                InfoChip(label = stringResource(Res.string.agents_max_iter_chip), value = profile.maxIterations.toString())
            }

            if (profile.tools.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(Res.string.agents_tools_label, profile.tools.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }

            if (profile.skills.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(Res.string.agents_skills_label, profile.skills.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = colors.primary.copy(alpha = 0.7f),
                    maxLines = 2,
                )
            }

            if (profile.systemPrompt.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    profile.systemPrompt.take(150) + if (profile.systemPrompt.length > 150) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun InfoChip(
    label: String,
    value: String,
) {
    val colors = MaterialTheme.colorScheme
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.5f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditorDialog(
    profile: AgentProfile?,
    client: PrometheClient,
    configuredProviderKeys: List<String>,
    availableSkills: List<String>,
    availableTools: List<String>,
    onDismiss: () -> Unit,
    onSave: (AgentProfileRequest) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val isNew = profile == null

    // Pour un profil existant, toujours utiliser son provider stocké.
    // Pour un nouveau profil, utiliser le premier provider configuré.
    val defaultProvider = if (!isNew && profile.provider.isNotBlank()) {
        ProviderRegistry.canonicalKey(profile.provider)
    } else {
        configuredProviderKeys.firstOrNull() ?: "openai"
    }

    var id by remember { mutableStateOf(profile?.id ?: "") }
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var provider by remember { mutableStateOf(defaultProvider) }
    var model by remember { mutableStateOf(profile?.model ?: "") }
    var systemPrompt by remember { mutableStateOf(profile?.systemPrompt ?: "") }

    var selectedTools by remember { mutableStateOf(profile?.tools ?: emptyList()) }
    var selectedSkills by remember { mutableStateOf(profile?.skills ?: emptyList()) }

    var maxIterations by remember { mutableStateOf(profile?.maxIterations?.toString() ?: "10") }
    var temperature by remember { mutableStateOf(profile?.temperature?.toFloat() ?: 0.2f) }
    var reasoningEffort by remember { mutableStateOf(profile?.reasoningEffort ?: ReasoningEffort.AUTO) }
    var reasoningEffortExpanded by remember { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }
    val availableReasoningEfforts =
        if (ProviderRegistry.canonicalKey(provider) == "kimi") {
            listOf(ReasoningEffort.AUTO, ReasoningEffort.LOW, ReasoningEffort.HIGH)
        } else {
            ReasoningEffort.entries
        }

    LaunchedEffect(provider) {
        if (reasoningEffort !in availableReasoningEfforts) reasoningEffort = ReasoningEffort.AUTO
    }

    // Fetch des modèles disponibles pour le provider sélectionné
    var fetchedModels by remember(provider) { mutableStateOf<List<String>>(emptyList()) }
    var fetchedEntries by remember(provider) { mutableStateOf<List<ModelFetcher.ModelEntry>>(emptyList()) }
    var isFetching by remember(provider) { mutableStateOf(false) }
    var fetchError by remember(provider) { mutableStateOf<String?>(null) }

    LaunchedEffect(provider) {
        isFetching = true
        fetchError = null
        try {
            val models = client.getProviderModels(provider)
                .filter { it.availability == ProviderAvailability.AVAILABLE || it.availability == ProviderAvailability.STALE }
            fetchedModels = models.map { it.id }
            fetchedEntries = models.map { ModelFetcher.ModelEntry(it.id) }
            // Auto-sélectionne le premier modèle si le champ est vide
            if (model.isBlank() && models.isNotEmpty()) {
                model = models.first().id
            }
        } catch (e: Exception) {
            fetchError = e.message
        } finally {
            isFetching = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.9f),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // ── Title ────────────────────────────────────────────────
                Text(
                    if (isNew) stringResource(Res.string.agents_new_profile_title) else stringResource(Res.string.agents_edit_profile_title, profile.name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(16.dp))

                // ── Scrollable body ──────────────────────────────────────
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(min = 400.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.S) {
                                onSave(
                                    AgentProfileRequest(
                                        id = if (isNew) id.ifBlank { null } else null,
                                        name = name,
                                        provider = provider,
                                        model = model,
                                        systemPrompt = systemPrompt,
                                        tools = selectedTools,
                                        skills = selectedSkills,
                                        maxIterations = maxIterations.toIntOrNull() ?: 10,
                                        temperature = temperature.toDouble(),
                                        reasoningEffort = reasoningEffort,
                                        isSystem = profile?.isSystem ?: false,
                                    ),
                                )
                                true
                            } else {
                                false
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (isNew) {
                        OutlinedTextField(
                            value = id,
                            onValueChange = { id = it.lowercase().replace(" ", "-") },
                            label = { Text(stringResource(Res.string.agents_id_label)) },
                            placeholder = { Text(stringResource(Res.string.agents_id_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("agents_edit_id"),
                        )
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(Res.string.agents_name_label)) },
                        placeholder = { Text(stringResource(Res.string.agents_name_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("agents_edit_name"),
                    )

                    // Provider dropdown — providers configurés uniquement
                    ExposedDropdownMenuBox(
                        expanded = providerExpanded,
                        onExpandedChange = { providerExpanded = !providerExpanded },
                    ) {
                        OutlinedTextField(
                            value = ProviderRegistry.get(provider)?.name ?: provider,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(Res.string.agents_provider_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                                .testTag("agents_edit_provider"),
                        )
                        ExposedDropdownMenu(
                            expanded = providerExpanded,
                            onDismissRequest = { providerExpanded = false },
                        ) {
                            configuredProviderKeys.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(ProviderRegistry.get(p)?.name ?: p) },
                                    onClick = {
                                        provider = p
                                        model = "" // reset modèle quand le provider change
                                        providerExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    // Modèle — dropdown dynamique (ModelDropdown du Setup)
                    val providerInfo = ProviderRegistry.get(provider)
                    val displayModels = fetchedModels.ifEmpty { providerInfo?.fallbackModels ?: emptyList() }
                    val displayEntries = fetchedEntries.ifEmpty {
                        (providerInfo?.fallbackModels ?: emptyList()).map { id ->
                            val p = ModelFetcher.knownPricing(id)
                            ModelFetcher.ModelEntry(id, p?.first, p?.second)
                        }
                    }

                    ModelDropdown(
                        models = displayModels,
                        entries = displayEntries,
                        selectedModel = model,
                        defaultModel = providerInfo?.defaultModel ?: "",
                        onModelChange = { model = it },
                        isFetching = isFetching,
                        fetchError = fetchError,
                        sourceLabel = if (fetchedModels.isNotEmpty()) stringResource(Res.string.agents_from_api) else stringResource(Res.string.agents_default_list),
                    )

                    // Bouton rafraîchir les modèles
                    TextButton(
                        onClick = {
                            scope.launch {
                                isFetching = true
                                fetchError = null
                                runCatching {
                                    client.reloadSettings()
                                    client.getProviderModels(provider)
                                }.onSuccess { models ->
                                    val available = models.filter {
                                        it.availability == ProviderAvailability.AVAILABLE ||
                                            it.availability == ProviderAvailability.STALE
                                    }
                                    fetchedModels = available.map { it.id }
                                    fetchedEntries = available.map { ModelFetcher.ModelEntry(it.id) }
                                }.onFailure { fetchError = it.message }
                                isFetching = false
                            }
                        },
                        enabled = !isFetching,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        if (isFetching) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(Res.string.agents_loading), style = MaterialTheme.typography.labelSmall)
                        } else {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(Res.string.agents_refresh_models), style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // WYSIWYG System Prompt
                    var promptTabSelected by remember { mutableStateOf(0) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(Res.string.agents_system_prompt),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                            )
                            TabRow(
                                selectedTabIndex = promptTabSelected,
                                modifier = Modifier.width(200.dp).height(32.dp),
                                indicator = {},
                                divider = {},
                            ) {
                                Tab(
                                    selected = promptTabSelected == 0,
                                    onClick = { promptTabSelected = 0 },
                                    text = { Text(stringResource(Res.string.agents_prompt_edit_tab), style = MaterialTheme.typography.labelSmall) },
                                )
                                Tab(
                                    selected = promptTabSelected == 1,
                                    onClick = { promptTabSelected = 1 },
                                    text = { Text(stringResource(Res.string.agents_prompt_preview_tab), style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (promptTabSelected == 0) {
                            OutlinedTextField(
                                value = systemPrompt,
                                onValueChange = { systemPrompt = it },
                                placeholder = { Text(stringResource(Res.string.agents_prompt_placeholder)) },
                                minLines = 5,
                                maxLines = 10,
                                modifier = Modifier.fillMaxWidth().testTag("agents_edit_prompt"),
                            )
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp, max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                            ) {
                                if (systemPrompt.isNotBlank()) {
                                    dev.promethe.app.ui.SimpleMarkdownText(markdown = systemPrompt)
                                } else {
                                    Text(
                                        stringResource(Res.string.agents_no_instructions),
                                        color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }

                    // Multi-select for tools
                    MultiSelectChipField(
                        label = stringResource(Res.string.agents_tools_allowed),
                        selectedItems = selectedTools,
                        availableItems = availableTools,
                        onItemsChange = { selectedTools = it },
                        modifier = Modifier.fillMaxWidth().testTag("agents_edit_tools"),
                    )

                    // Multi-select for skills
                    MultiSelectChipField(
                        label = stringResource(Res.string.agents_assigned_skills),
                        selectedItems = selectedSkills,
                        availableItems = availableSkills,
                        onItemsChange = { selectedSkills = it },
                        modifier = Modifier.fillMaxWidth().testTag("agents_edit_skills"),
                    )

                    // Temperature slider
                    Column {
                        Text(
                            stringResource(Res.string.agents_temperature_label, temperature.fmt(2)),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurface,
                        )
                        Slider(
                            value = temperature,
                            onValueChange = { temperature = it },
                            valueRange = 0f..2f,
                            steps = 19,
                        )
                    }

                    // Reasoning effort dropdown
                    ExposedDropdownMenuBox(
                        expanded = reasoningEffortExpanded,
                        onExpandedChange = { reasoningEffortExpanded = !reasoningEffortExpanded },
                    ) {
                        OutlinedTextField(
                            value = reasoningEffort.displayName(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(Res.string.agents_reasoning_effort_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = reasoningEffortExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = reasoningEffortExpanded,
                            onDismissRequest = { reasoningEffortExpanded = false },
                        ) {
                            availableReasoningEfforts.forEach { effort ->
                                DropdownMenuItem(
                                    text = { Text(effort.displayName()) },
                                    onClick = {
                                        reasoningEffort = effort
                                        reasoningEffortExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = maxIterations,
                        onValueChange = { maxIterations = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(Res.string.agents_max_iterations_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ── Bottom action buttons ────────────────────────────────
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("agents_cancel"),
                    ) { Text(stringResource(Res.string.action_cancel)) }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        modifier = Modifier.testTag("agents_save"),
                        onClick = {
                            onSave(
                                AgentProfileRequest(
                                    id = if (isNew) id.ifBlank { null } else null,
                                    name = name,
                                    provider = provider,
                                    model = model,
                                    systemPrompt = systemPrompt,
                                    tools = selectedTools,
                                    skills = selectedSkills,
                                    maxIterations = maxIterations.toIntOrNull() ?: 10,
                                    temperature = temperature.toDouble(),
                                    reasoningEffort = reasoningEffort,
                                    isSystem = profile?.isSystem ?: false,
                                ),
                            )
                        },
                        enabled = name.isNotBlank() && ((isNew && id.isNotBlank()) || !isNew),
                    ) {
                        Text(if (isNew) stringResource(Res.string.action_create) else stringResource(Res.string.action_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningEffort.displayName(): String =
    when (this) {
        ReasoningEffort.AUTO -> stringResource(Res.string.agents_reasoning_effort_auto)
        ReasoningEffort.LOW -> stringResource(Res.string.agents_reasoning_effort_low)
        ReasoningEffort.MEDIUM -> stringResource(Res.string.agents_reasoning_effort_medium)
        ReasoningEffort.HIGH -> stringResource(Res.string.agents_reasoning_effort_high)
    }

// ── MultiSelectChipField Dropdown ───────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MultiSelectChipField(
    label: String,
    selectedItems: List<String>,
    availableItems: List<String>,
    onItemsChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = if (selectedItems.isEmpty()) "" else stringResource(Res.string.agents_selected_count, selectedItems.size),
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                placeholder = {
                    Text(stringResource(Res.string.agents_no_items_selected))
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 300.dp),
            ) {
                availableItems.forEach { item ->
                    val isSelected = selectedItems.contains(item)
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = isSelected, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(item)
                            }
                        },
                        onClick = {
                            if (isSelected) {
                                onItemsChange(selectedItems - item)
                            } else {
                                onItemsChange(selectedItems + item)
                            }
                        },
                    )
                }
            }
        }

        if (selectedItems.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            ) {
                selectedItems.forEach { item ->
                    InputChip(
                        selected = true,
                        onClick = { onItemsChange(selectedItems - item) },
                        label = { Text(item, style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(Res.string.agents_remove_a11y),
                                modifier = Modifier.size(12.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}
