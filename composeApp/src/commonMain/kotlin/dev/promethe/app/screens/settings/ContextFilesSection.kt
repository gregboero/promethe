package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@Composable
fun ContextFilesSection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
    client: PrometheClient?,
    scope: CoroutineScope,
) {
    val colors = MaterialTheme.colorScheme

    // Local saving state (not in SettingsState because it's transient UI)
    var contextFilesSaving by remember { mutableStateOf(false) }
    var contextFilesSaved by remember { mutableStateOf(false) }
    var contextFilesError by remember { mutableStateOf<String?>(null) }

    SectionHeader(
        stringResource(Res.string.settings_context_files_title),
        state.contextFilesExpanded,
        stringResource(Res.string.settings_context_files_description),
    ) {
        onUpdate { copy(contextFilesExpanded = !contextFilesExpanded) }
    }

    // Lazy-load context files on first expand
    LaunchedEffect(state.contextFilesExpanded) {
        if (state.contextFilesExpanded && !state.contextFilesLoaded && client != null) {
            contextFilesError = null
            val files = listOf(".promethe.md", "SOUL.md", "AGENTS.md", "CONTEXT.md")
            for (name in files) {
                try {
                    val resp = client.getJson("/api/v1/context/files/$name")
                    val content = (resp["content"] as? JsonPrimitive)?.contentOrNull ?: ""
                    val exists = (resp["exists"] as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: content.isNotBlank()
                    onUpdate {
                        when (name) {
                            ".promethe.md" -> copy(prometheContent = content, prometheExists = exists)
                            "SOUL.md" -> copy(soulContent = content, soulExists = exists)
                            "AGENTS.md" -> copy(agentsContent = content, agentsExists = exists)
                            "CONTEXT.md" -> copy(contextContent = content, contextExists = exists)
                            else -> this
                        }
                    }
                } catch (e: Exception) {
                    contextFilesError = "Failed to load $name: ${e.message}"
                }
            }
            onUpdate { copy(contextFilesLoaded = true) }
        }
    }

    AnimatedVisibility(state.contextFilesExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(Res.string.settings_context_files_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            if (contextFilesError != null) {
                Text(
                    contextFilesError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }

            if (!state.contextFilesLoaded && client != null) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = colors.primary,
                    )
                }
            }

            if (client == null) {
                Text(
                    stringResource(Res.string.settings_context_files_connect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }

            if (state.contextFilesLoaded) {
                ContextFileEditor(
                    filename = ".promethe.md",
                    exists = state.prometheExists,
                    content = state.prometheContent,
                    onContentChange = { onUpdate { copy(prometheContent = it) } },
                    colors = colors,
                )
                ContextFileEditor(
                    filename = "SOUL.md",
                    exists = state.soulExists,
                    content = state.soulContent,
                    onContentChange = { onUpdate { copy(soulContent = it) } },
                    colors = colors,
                )
                ContextFileEditor(
                    filename = "AGENTS.md",
                    exists = state.agentsExists,
                    content = state.agentsContent,
                    onContentChange = { onUpdate { copy(agentsContent = it) } },
                    colors = colors,
                )
                ContextFileEditor(
                    filename = "CONTEXT.md",
                    exists = state.contextExists,
                    content = state.contextContent,
                    onContentChange = { onUpdate { copy(contextContent = it) } },
                    colors = colors,
                )

                val savedLabel = stringResource(Res.string.settings_context_files_saved)
                val saveLabel = stringResource(Res.string.settings_context_files_save)

                Button(
                    onClick = {
                        if (client == null) return@Button
                        contextFilesSaving = true
                        contextFilesError = null
                        scope.launch {
                            try {
                                val filesToSave = mapOf(
                                    ".promethe.md" to state.prometheContent,
                                    "SOUL.md" to state.soulContent,
                                    "AGENTS.md" to state.agentsContent,
                                    "CONTEXT.md" to state.contextContent,
                                )
                                for ((name, content) in filesToSave) {
                                    val body = buildJsonObject {
                                        put("content", kotlinx.serialization.json.JsonPrimitive(content))
                                    }.toString()
                                    client.putJson("/api/v1/context/files/$name", body)
                                }
                                contextFilesSaved = true
                                onUpdate {
                                    copy(
                                        prometheExists = prometheContent.isNotBlank(),
                                        soulExists = soulContent.isNotBlank(),
                                        agentsExists = agentsContent.isNotBlank(),
                                        contextExists = contextContent.isNotBlank(),
                                    )
                                }
                                delay(3000)
                                contextFilesSaved = false
                            } catch (e: Exception) {
                                contextFilesError = "Failed to save: ${e.message}"
                            } finally {
                                contextFilesSaving = false
                            }
                        }
                    },
                    enabled = !contextFilesSaving && client != null,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("settings_save_context_files"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary,
                    ),
                ) {
                    if (contextFilesSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.onPrimary,
                        )
                    } else {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (contextFilesSaved) savedLabel else saveLabel)
                }
            }
        }
    }
}
