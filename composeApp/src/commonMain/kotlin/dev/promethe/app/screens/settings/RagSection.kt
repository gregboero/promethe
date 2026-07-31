package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.promethe.api.EmbeddingModelInfo
import dev.promethe.api.EmbeddingProvider
import dev.promethe.api.VectorStoreType
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagSection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
    client: PrometheClient?,
    scope: CoroutineScope,
) {
    val colors = MaterialTheme.colorScheme

    SectionHeader(
        stringResource(Res.string.settings_rag_title),
        state.ragExpanded,
        stringResource(Res.string.settings_rag_description),
        statusColor = if (state.ragEnabled) Color(0xFF22C55E) else Color(0xFF9E9E9E),
    ) { onUpdate { copy(ragExpanded = !ragExpanded) } }

    AnimatedVisibility(state.ragExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Activation du RAG
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(Res.string.settings_rag_enable), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    Text(
                        stringResource(Res.string.settings_rag_enable_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                val ragA11yLabel = stringResource(Res.string.settings_rag_enable)
                val ragA11yEnabled = stringResource(Res.string.settings_rag_a11y_enabled)
                val ragA11yDisabled = stringResource(Res.string.settings_rag_a11y_disabled)
                Switch(
                    checked = state.ragEnabled,
                    onCheckedChange = { onUpdate { copy(ragEnabled = it) } },
                    modifier = Modifier
                        .testTag("settings_rag_toggle")
                        .semantics {
                            role = Role.Switch
                            contentDescription = ragA11yLabel
                            stateDescription = if (state.ragEnabled) ragA11yEnabled else ragA11yDisabled
                        },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.primary,
                        checkedTrackColor = colors.primaryContainer,
                    ),
                )
            }

            if (state.ragEnabled) {
                HorizontalDivider(color = colors.outlineVariant)

                // --- SECTION EMBEDDING PROVIDER ---
                Text(
                    stringResource(Res.string.settings_rag_embedding_engine),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.primary,
                    fontWeight = FontWeight.Bold,
                )

                Text(stringResource(Res.string.settings_rag_provider), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EmbeddingProvider.values().forEach { provider ->
                        FilterChip(
                            selected = state.ragEmbeddingProvider == provider.name,
                            onClick = {
                                val firstModel = EmbeddingModelInfo.KNOWN_MODELS.firstOrNull { it.provider == provider }
                                onUpdate {
                                    copy(
                                        ragEmbeddingProvider = provider.name,
                                        ragEmbeddingModel = firstModel?.id ?: ragEmbeddingModel,
                                        ragEmbeddingDimensions = firstModel?.dimensions?.toString() ?: ragEmbeddingDimensions,
                                    )
                                }
                            },
                            label = { Text(provider.name) },
                        )
                    }
                }

                // Model selection dropdown
                Column {
                    Text(stringResource(Res.string.settings_rag_embedding_model), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    Spacer(Modifier.height(4.dp))

                    val providerEnum = try {
                        EmbeddingProvider.valueOf(state.ragEmbeddingProvider)
                    } catch (_: Exception) {
                        EmbeddingProvider.OLLAMA
                    }
                    val filteredModels = EmbeddingModelInfo.KNOWN_MODELS.filter { it.provider == providerEnum }

                    var dropdownExpanded by remember { mutableStateOf(false) }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { dropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth().testTag("settings_rag_model_dropdown"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(state.ragEmbeddingModel.ifBlank { stringResource(Res.string.settings_rag_select_model) })
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        }

                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.9f),
                        ) {
                            filteredModels.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(model.id, fontWeight = FontWeight.Bold)
                                            Text(
                                                stringResource(Res.string.settings_rag_dim_format, model.description, model.dimensions),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        onUpdate {
                                            copy(
                                                ragEmbeddingModel = model.id,
                                                ragEmbeddingDimensions = model.dimensions.toString(),
                                            )
                                        }
                                        dropdownExpanded = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.settings_rag_other_model)) },
                                onClick = {
                                    onUpdate { copy(ragEmbeddingModel = "") }
                                    dropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                val providerEnumCurrent = try {
                    EmbeddingProvider.valueOf(state.ragEmbeddingProvider)
                } catch (_: Exception) {
                    EmbeddingProvider.OLLAMA
                }
                val modelIsKnown = EmbeddingModelInfo.KNOWN_MODELS.any {
                    it.id == state.ragEmbeddingModel && it.provider == providerEnumCurrent
                }
                if (!modelIsKnown || state.ragEmbeddingModel.isBlank()) {
                    OutlinedTextField(
                        value = state.ragEmbeddingModel,
                        onValueChange = { onUpdate { copy(ragEmbeddingModel = it) } },
                        label = { Text(stringResource(Res.string.settings_rag_custom_model_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_rag_custom_model"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                    )
                }

                if (state.ragEmbeddingProvider == "OLLAMA" || state.ragEmbeddingProvider == "LITELLM") {
                    OutlinedTextField(
                        value = state.ragEmbeddingBaseUrl,
                        onValueChange = { onUpdate { copy(ragEmbeddingBaseUrl = it) } },
                        label = { Text(stringResource(Res.string.settings_rag_api_url_label, state.ragEmbeddingProvider)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_rag_embedding_url"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                    )
                }

                if (state.ragEmbeddingProvider != "OLLAMA") {
                    OutlinedTextField(
                        value = state.ragEmbeddingApiKey,
                        onValueChange = { onUpdate { copy(ragEmbeddingApiKey = it) } },
                        label = { Text(stringResource(Res.string.settings_rag_api_key_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("settings_rag_embedding_api_key"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                    )
                }

                OutlinedTextField(
                    value = state.ragEmbeddingDimensions,
                    onValueChange = { onUpdate { copy(ragEmbeddingDimensions = it.filter { c -> c.isDigit() }) } },
                    label = { Text(stringResource(Res.string.settings_rag_dimensions_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_rag_dimensions"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                )

                // Test embedding button
                var testingEmbedding by remember { mutableStateOf(false) }
                var testResultMsg by remember { mutableStateOf<String?>(null) }
                var testSuccess by remember { mutableStateOf(false) }

                Button(
                    onClick = {
                        testingEmbedding = true
                        testResultMsg = null
                        scope.launch {
                            try {
                                val tempConfig = dev.promethe.api.RagConfig(
                                    enabled = state.ragEnabled,
                                    embeddingProvider = dev.promethe.api.EmbeddingProvider.valueOf(state.ragEmbeddingProvider),
                                    embeddingModel = state.ragEmbeddingModel,
                                    embeddingBaseUrl = state.ragEmbeddingBaseUrl,
                                    embeddingApiKey = state.ragEmbeddingApiKey,
                                    embeddingDimensions = state.ragEmbeddingDimensions.toIntOrNull() ?: 768,
                                    vectorStoreType = dev.promethe.api.VectorStoreType.valueOf(state.ragVectorStoreType),
                                    vectorStoreUrl = state.ragVectorStoreUrl,
                                    vectorStoreApiKey = state.ragVectorStoreApiKey,
                                    vectorStorePath = state.ragVectorStorePath,
                                    chunkSize = state.ragChunkSize.toInt(),
                                    chunkOverlap = state.ragChunkOverlap.toInt(),
                                )
                                client?.updateRagConfig(tempConfig)
                                val res = client?.testEmbedding()
                                if (res?.success == true) {
                                    testSuccess = true
                                    testResultMsg = getString(Res.string.settings_rag_test_success, res.latencyMs, res.dimensions)
                                } else {
                                    testSuccess = false
                                    val errorText = res?.error ?: getString(Res.string.settings_rag_test_error_default)
                                    testResultMsg = getString(Res.string.settings_rag_test_failure, errorText)
                                }
                            } catch (e: Exception) {
                                testSuccess = false
                                testResultMsg = getString(Res.string.settings_rag_test_exception, e.message ?: "")
                            } finally {
                                testingEmbedding = false
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.End).testTag("settings_rag_test_embedding"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (testResultMsg != null && testSuccess) Color(0xFF22C55E) else colors.secondary,
                    ),
                ) {
                    if (testingEmbedding) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = colors.onSecondary)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.settings_rag_test_connection))
                    }
                }

                testResultMsg?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testSuccess) Color(0xFF22C55E) else colors.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                HorizontalDivider(color = colors.outlineVariant)

                // --- SECTION VECTOR STORE ---
                Text(
                    stringResource(Res.string.settings_rag_vector_store_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.primary,
                    fontWeight = FontWeight.Bold,
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(Res.string.settings_rag_dev_local),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold,
                    )

                    IntegrationCard(
                        title = "SQLite-Vec",
                        icon = Icons.Default.Storage,
                        configured = state.ragVectorStoreType == "SQLITE_VEC",
                        colors = colors,
                        description = stringResource(Res.string.settings_rag_sqlite_desc),
                        accentColor = colors.primary,
                    ) {
                        OutlinedTextField(
                            value = state.ragVectorStorePath,
                            onValueChange = { onUpdate { copy(ragVectorStorePath = it) } },
                            label = { Text(stringResource(Res.string.settings_rag_sqlite_path_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("settings_rag_sqlite_path"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                            supportingText = { Text(stringResource(Res.string.settings_rag_sqlite_path_hint)) },
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onUpdate { copy(ragVectorStoreType = "SQLITE_VEC") } },
                            modifier = Modifier.fillMaxWidth().testTag("settings_rag_select_sqlite"),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                        ) {
                            Text(stringResource(Res.string.settings_rag_select_sqlite))
                        }
                    }

                    Text(
                        stringResource(Res.string.settings_rag_prod_cloud),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold,
                    )

                    VectorStoreType.values().filter { it != VectorStoreType.SQLITE_VEC }.forEach { vType ->
                        IntegrationCard(
                            title = vType.name,
                            icon = Icons.Default.Cloud,
                            configured = state.ragVectorStoreType == vType.name,
                            colors = colors,
                            description = stringResource(Res.string.settings_rag_external_desc, vType.name),
                            accentColor = colors.primary,
                        ) {
                            OutlinedTextField(
                                value = state.ragVectorStoreUrl,
                                onValueChange = { onUpdate { copy(ragVectorStoreUrl = it) } },
                                label = { Text(stringResource(Res.string.settings_rag_vector_url_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("settings_rag_vector_url"),
                                shape = RoundedCornerShape(12.dp),
                                colors = settingsFieldColors(colors),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = state.ragVectorStoreApiKey,
                                onValueChange = { onUpdate { copy(ragVectorStoreApiKey = it) } },
                                label = { Text(stringResource(Res.string.settings_rag_api_key_label)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = settingsFieldColors(colors),
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { onUpdate { copy(ragVectorStoreType = vType.name) } },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                            ) {
                                Text(stringResource(Res.string.settings_rag_select_store, vType.name))
                            }
                        }
                    }
                }

                HorizontalDivider(color = colors.outlineVariant)

                // --- SECTION CHUNKING ---
                Text(
                    stringResource(Res.string.settings_rag_chunking_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.primary,
                    fontWeight = FontWeight.Bold,
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(Res.string.settings_rag_chunk_size), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                        Text(
                            stringResource(Res.string.settings_rag_chunk_size_value, state.ragChunkSize.toInt()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.primary,
                        )
                    }
                    Slider(
                        value = state.ragChunkSize,
                        onValueChange = { onUpdate { copy(ragChunkSize = it) } },
                        valueRange = 128f..2048f,
                        steps = 15,
                        modifier = Modifier.testTag("settings_rag_chunk_size"),
                        colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(Res.string.settings_rag_chunk_overlap), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                        Text(
                            stringResource(Res.string.settings_rag_chunk_overlap_value, state.ragChunkOverlap.toInt()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.primary,
                        )
                    }
                    Slider(
                        value = state.ragChunkOverlap,
                        onValueChange = { onUpdate { copy(ragChunkOverlap = it) } },
                        valueRange = 0f..200f,
                        steps = 20,
                        modifier = Modifier.testTag("settings_rag_chunk_overlap"),
                        colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
                    )
                }
            }
        }
    }
}
