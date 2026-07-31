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
import dev.promethe.app.screens.viewmodel.MemoryViewModel
import dev.promethe.app.screens.viewmodel.MemoryFactUiModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.promethe.app.network.PrometheClient
import dev.promethe.app.util.fmt
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

/**
 * MemoryScreen — browse, search, and manage agent memory facts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(client: PrometheClient) {
    val viewModel = remember { MemoryViewModel(client) }
    val state by viewModel.state.collectAsState()

    val colors = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.memory_title), style = MaterialTheme.typography.headlineSmall) },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.background,
                        titleContentColor = colors.onBackground,
                    ),
            )
        },
        containerColor = colors.background,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            // ── Provider Status ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Memory,
                        contentDescription = null,
                        tint = Color(0xFF22C55E),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(Res.string.memory_provider_label, state.providerName),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(Res.string.memory_fact_count, state.factCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Search Bar ──
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                label = { Text(stringResource(Res.string.memory_search_label)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(Res.string.memory_search_memories_desc)) },
                trailingIcon = {
                    if (state.searchQuery.isNotBlank()) {
                        IconButton(onClick = {
                            viewModel.updateSearchQuery("")
                            viewModel.search()
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(Res.string.memory_clear_search))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("memory_search"),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                    ),
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { viewModel.search() },
                modifier = Modifier.testTag("memory_search_btn"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.memory_search_btn))
            }

            Spacer(Modifier.height(16.dp))

            // ── Error ──
            state.error?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = colors.errorContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(Res.string.memory_error_format, it),
                        modifier = Modifier.padding(12.dp),
                        color = colors.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Loading ──
            if (state.isLoading) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.primary)
                }
                return@Scaffold
            }

            // ── Fact List ──
            if (state.facts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(Res.string.memory_no_memories),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                        Text(
                            stringResource(Res.string.memory_chat_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("memory_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.facts, key = { it.id }) { fact ->
                        MemoryFactCard(fact, colors, onDelete = { viewModel.deleteFact(fact.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryFactCard(
    fact: MemoryFactUiModel,
    colors: ColorScheme,
    onDelete: () -> Unit,
) {
    val categoryColor =
        when (fact.category) {
            "preference" -> Color(0xFF6366F1)
            "project" -> Color(0xFFA855F7)
            "personal" -> Color(0xFF22D3EE)
            "technical" -> Color(0xFFF59E0B)
            else -> Color(0xFF64748B)
        }

    val factDescA11y = stringResource(Res.string.memory_fact_desc, fact.content.take(60))
    val deleteFactDescA11y = stringResource(Res.string.memory_delete_fact_desc, fact.content.take(40))

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("memory_fact_${fact.id}")
            .semantics {
                role = Role.Button
                contentDescription = factDescA11y
            },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(categoryColor.copy(alpha = 0.08f), Color.Transparent),
                        ),
                    ).padding(12.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Category chip
                    Surface(
                        color = categoryColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            fact.category.uppercase(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = categoryColor,
                        )
                    }

                    // Confidence + Delete
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${(fact.confidence * 100).fmt(0)}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(24.dp).testTag("memory_delete_${fact.id}"),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = deleteFactDescA11y,
                                tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                Text(
                    fact.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    fact.tier,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}
