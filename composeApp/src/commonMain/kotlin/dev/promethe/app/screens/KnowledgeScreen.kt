package dev.promethe.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.promethe.app.screens.viewmodel.DocumentUi
import dev.promethe.app.screens.viewmodel.KnowledgeViewModel
import dev.promethe.app.screens.viewmodel.SearchResultUi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.promethe.app.network.PrometheClient
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * KnowledgeScreen — base de connaissances RAG.
 *
 * Fonctionnalités :
 * - Recherche sémantique dans les documents ingérés
 * - Liste des documents avec métadonnées (chunks, tokens, date)
 * - Suppression de documents
 * - Indicateur de connectivité embedding + vector store
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(client: PrometheClient) {
    val viewModel = remember { KnowledgeViewModel(client) }
    val state by viewModel.state.collectAsState()
    val colors = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(Res.string.knowledge_header_title), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(Res.string.knowledge_documents_chunks_summary, state.documents.size, state.totalChunks),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    // Indicateurs de connectivité
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (state.embeddingOk) {
                            Color(0xFF1B5E20).copy(alpha = 0.15f)
                        } else {
                            colors.errorContainer.copy(alpha = 0.3f)
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                if (state.embeddingOk) Icons.Filled.Check else Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (state.embeddingOk) Color(0xFF4CAF50) else colors.error,
                            )
                            Text(
                                stringResource(Res.string.knowledge_embedding_label),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (state.vectorStoreOk) {
                            Color(0xFF1B5E20).copy(alpha = 0.15f)
                        } else {
                            colors.errorContainer.copy(alpha = 0.3f)
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                if (state.vectorStoreOk) Icons.Filled.Check else Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (state.vectorStoreOk) Color(0xFF4CAF50) else colors.error,
                            )
                            Text(
                                stringResource(Res.string.knowledge_storage_label),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                ),
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("rag_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Barre de recherche sémantique ──
            item {
                SearchBar(
                    searchQuery = state.searchQuery,
                    onQueryChange = viewModel::updateSearchQuery,
                    onSearch = { viewModel.performSearch() },
                    isSearching = state.isSearching,
                    colors = colors,
                )
            }

            // ── Résultats de recherche ──
            if (state.showSearchResults) {
                item {
                    Text(
                        stringResource(Res.string.knowledge_search_results),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                if (state.searchResults.isEmpty() && !state.isSearching) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.SearchOff,
                            title = stringResource(Res.string.knowledge_no_results),
                            subtitle = stringResource(Res.string.knowledge_no_results_for_query, state.searchQuery),
                            colors = colors,
                        )
                    }
                }

                items(state.searchResults) { result ->
                    SearchResultCard(result, colors)
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }

            // ── Infos provider ──
            item {
                ProviderInfoCard(
                    embeddingProvider = state.embeddingProvider,
                    embeddingModel = state.embeddingModel,
                    vectorStoreType = state.vectorStoreType,
                    totalChunks = state.totalChunks,
                    colors = colors,
                )
            }

            // ── Liste des documents ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.knowledge_ingested_documents),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(Res.string.knowledge_document_count, state.documents.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            if (state.documents.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Description,
                        title = stringResource(Res.string.knowledge_no_documents_title),
                        subtitle = stringResource(Res.string.knowledge_no_documents_hint),
                        colors = colors,
                    )
                }
            }

            items(state.documents) { doc ->
                DocumentCard(doc, colors, onDelete = {
                    viewModel.deleteDocument(doc.id)
                })
            }

            // ── Erreur ──
            state.error?.let { errorMsg ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.errorContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Warning, null, tint = colors.error)
                            Text(errorMsg, color = colors.onErrorContainer)
                        }
                    }
                }
            }
        }
    }
}

// ── Composants internes ──────────────────────────────────────

@Composable
private fun SearchBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    colors: ColorScheme,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(Res.string.knowledge_search_placeholder)) },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("rag_search"),
                shape = RoundedCornerShape(12.dp),
                leadingIcon = {
                    Icon(Icons.Filled.Search, stringResource(Res.string.knowledge_search_icon_a11y), tint = colors.primary)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                ),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onSearch,
                enabled = searchQuery.isNotBlank() && !isSearching,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("rag_upload_btn"),
            ) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(Res.string.knowledge_search_button))
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    result: SearchResultUi,
    colors: ColorScheme,
) {
    val scoreColor = when {
        result.score >= 0.8 -> Color(0xFF4CAF50)
        result.score >= 0.6 -> Color(0xFFFFC107)
        else -> colors.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // En-tête: source + score
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Description,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = colors.primary,
                    )
                    Text(
                        result.source.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = scoreColor.copy(alpha = 0.15f),
                ) {
                    Text(
                        "${(result.score * 100).toInt()}%",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor,
                    )
                }
            }

            if (result.heading.isNotBlank()) {
                Text(
                    result.heading,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                result.content,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DocumentCard(
    doc: DocumentUi,
    colors: ColorScheme,
    onDelete: () -> Unit,
) {
    // Hoist contentDescription (stringResource is @Composable)
    val docDesc = stringResource(Res.string.knowledge_document_desc, doc.filename)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("rag_document_${doc.id}")
            .semantics {
                role = Role.Button
                contentDescription = docDesc
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icône fichier
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(colors.primaryContainer, colors.primary.copy(alpha = 0.3f)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Description,
                    null,
                    tint = colors.primary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            // Infos
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    doc.filename,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChipInfo(stringResource(Res.string.knowledge_chunk_count, doc.chunkCount), colors)
                    ChipInfo(stringResource(Res.string.knowledge_token_count, doc.totalTokens), colors)
                }
            }

            // Bouton supprimer
            val deleteDesc = stringResource(Res.string.knowledge_delete_document_a11y, doc.filename)
            IconButton(onClick = onDelete, modifier = Modifier.testTag("rag_delete_${doc.id}")) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = deleteDesc,
                    tint = colors.error.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun ChipInfo(
    text: String,
    colors: ColorScheme,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.surfaceContainerHighest,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderInfoCard(
    embeddingProvider: String,
    embeddingModel: String,
    vectorStoreType: String,
    totalChunks: Int,
    colors: ColorScheme,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("rag_config"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(Res.string.knowledge_configuration),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                InfoItem(stringResource(Res.string.knowledge_embedding_label), embeddingProvider, colors)
                InfoItem(stringResource(Res.string.knowledge_model_label), embeddingModel, colors)
                InfoItem(stringResource(Res.string.knowledge_storage_label), vectorStoreType, colors)
                InfoItem(stringResource(Res.string.knowledge_chunks), totalChunks.toString(), colors)
            }
        }
    }
}

@Composable
private fun InfoItem(
    label: String,
    value: String,
    colors: ColorScheme,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    colors: ColorScheme,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                null,
                modifier = Modifier.size(48.dp),
                tint = colors.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
