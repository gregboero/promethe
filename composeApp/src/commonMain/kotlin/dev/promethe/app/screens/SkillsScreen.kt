package dev.promethe.app.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.promethe.app.screens.viewmodel.SkillsViewModel
import dev.promethe.app.screens.viewmodel.SkillsUiState
import dev.promethe.app.screens.viewmodel.ArchitectMessage
import dev.promethe.app.screens.viewmodel.ArchitectPhase
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.promethe.api.SkillDto
import dev.promethe.api.SkillLifecycle
import dev.promethe.app.network.PrometheClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import dev.promethe.app.ui.SimpleMarkdownText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(client: PrometheClient) {
    val viewModel = remember { SkillsViewModel(client) }
    val state by viewModel.state.collectAsState()

    var skillToDelete by remember { mutableStateOf<String?>(null) }

    Row(modifier = Modifier.fillMaxSize()) {
        // ── Left panel: Skill list ──────────────────────────────────
        Surface(
            modifier = Modifier.weight(0.35f).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(Res.string.skills_title), fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ) {
                                    Text("${state.skills.size}")
                                }
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { viewModel.load() },
                                modifier = Modifier.testTag("skills_refresh"),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.action_refresh))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    )
                },
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = { viewModel.openArchitectDialog() },
                        modifier = Modifier.testTag("skills_create_btn"),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.skills_add_skill_a11y))
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) { padding ->
                if (state.isLoading && state.skills.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .testTag("skills_list"),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.skills, key = { it.name }) { skill ->
                            SkillCard(
                                skill = skill,
                                isSelected = state.selectedSkill?.name == skill.name,
                                onSelect = {
                                    viewModel.selectSkill(skill)
                                },
                                onDelete = { skillToDelete = skill.name },
                            )
                        }
                    }
                }
            }
        }

        // ── Right panel: Detail / Editor ────────────────────────────
        Surface(
            modifier = Modifier.weight(0.65f).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            val selected = state.selectedSkill
            if (selected == null) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(Res.string.skills_select_to_view),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .testTag("skills_editor"),
                ) {
                    // Title row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = selected.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        if (!state.isEditing) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.openArchitectDialog(selected) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary,
                                        contentColor = MaterialTheme.colorScheme.onTertiary,
                                    ),
                                ) {
                                    Icon(
                                        Icons.Filled.AutoAwesome,
                                        contentDescription = stringResource(Res.string.skills_improve_architect_a11y),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(Res.string.skills_architect_button))
                                }

                                FilledTonalButton(
                                    onClick = {
                                        viewModel.startEditing()
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = stringResource(Res.string.skills_edit_a11y),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(Res.string.action_edit))
                                }
                            }
                        }
                    }

                    // Description from frontmatter
                    if (selected.description.isNotBlank()) {
                        Text(
                            text = selected.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    val nextLifecycle =
                        when (selected.contract.lifecycle) {
                            SkillLifecycle.DRAFT -> SkillLifecycle.QUARANTINED
                            SkillLifecycle.QUARANTINED -> SkillLifecycle.CANDIDATE
                            SkillLifecycle.CANDIDATE -> SkillLifecycle.ACTIVE
                            SkillLifecycle.ACTIVE -> SkillLifecycle.DEPRECATED
                            SkillLifecycle.DEPRECATED -> SkillLifecycle.DRAFT
                        }
                    val requiresReview = nextLifecycle in setOf(SkillLifecycle.CANDIDATE, SkillLifecycle.ACTIVE)
                    var reviewNote by remember(selected.name, selected.contract.contentHash, selected.contract.lifecycle) { mutableStateOf("") }
                    if (requiresReview && !state.isEditing) {
                        OutlinedTextField(
                            value = reviewNote,
                            onValueChange = { reviewNote = it.take(2_000) },
                            label = { Text(stringResource(Res.string.skills_review_note)) },
                            supportingText = { Text(stringResource(Res.string.skills_review_scope)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.skills_lifecycle, selected.contract.lifecycle.name),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if ((!selected.isSystem || selected.contract.validationRequired) && !state.isEditing) {
                            FilledTonalButton(
                                onClick = { viewModel.updateLifecycle(nextLifecycle, reviewNote.takeIf { requiresReview }) },
                                enabled = !state.isSaving && (!requiresReview || (reviewNote.isNotBlank() && state.validation?.canPromote == true)),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PublishedWithChanges,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(Res.string.skills_move_to_lifecycle, nextLifecycle.name))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    if (!state.isEditing) SkillValidationPanel(state, viewModel)

                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    if (state.isEditing) {
                        // Editing mode
                        OutlinedTextField(
                            value = state.editContent,
                            onValueChange = { viewModel.updateEditContent(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            shape = RoundedCornerShape(12.dp),
                        )

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(16.dp))
                            }
                            OutlinedButton(
                                onClick = { viewModel.cancelEditing() },
                                enabled = !state.isSaving,
                            ) {
                                Text(stringResource(Res.string.action_cancel))
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    viewModel.saveSkill()
                                },
                                enabled = !state.isSaving,
                                modifier = Modifier.testTag("skills_save_btn"),
                            ) {
                                Icon(
                                    Icons.Filled.Save,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(Res.string.action_save))
                            }
                        }
                    } else {
                        // Read-only mode
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            SimpleMarkdownText(
                                markdown = selected.content,
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Architect mini-chat dialog ────────────────────────────────────
    if (state.showArchitectDialog) {
        SkillArchitectDialog(
            state = state,
            onDismiss = { viewModel.closeArchitectDialog() },
            onSubmit = { viewModel.submitArchitectInput(it) },
            onValidate = { viewModel.validateArchitectSkill() },
        )
    }

    // ── Deletion confirmation dialog ──────────────────────────────────
    skillToDelete?.let { skillName ->
        Dialog(onDismissRequest = { skillToDelete = null }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.width(360.dp).padding(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = stringResource(Res.string.skills_delete_skill_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.skills_delete_skill_confirm, skillName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { skillToDelete = null }) {
                            Text(stringResource(Res.string.action_cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                viewModel.deleteSkill(skillName)
                                skillToDelete = null
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Text(stringResource(Res.string.action_delete))
                        }
                    }
                }
            }
        }
    }
}

// ── Skill card ──────────────────────────────────────────────────────
@Composable
private fun SkillCard(
    skill: SkillDto,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 4.dp else 1.dp,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (skill.isSystem) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                            contentColor = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                        ) {
                            Text(
                                text = stringResource(Res.string.skills_system_badge),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = skill.description.ifBlank { skill.preview.ifBlank { skill.content.lines().take(2).joinToString(" ") } },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!skill.isSystem) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(Res.string.skills_delete_skill_a11y),
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ── Architect mini-chat dialog ──────────────────────────────────────
@Composable
private fun SkillArchitectDialog(
    state: SkillsUiState,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onValidate: () -> Unit,
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(state.architectMessages.size) {
        if (state.architectMessages.isNotEmpty()) {
            listState.animateScrollToItem(state.architectMessages.lastIndex)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(min = 400.dp, max = 700.dp)
                .heightIn(min = 400.dp, max = 600.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                // ── Header ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.skills_architect_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("architect_close"),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_close))
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // ── Messages ──
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("architect_messages"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.architectMessages.size) { index ->
                        val msg = state.architectMessages[index]
                        ArchitectMessageBubble(msg)
                    }

                    // Streaming indicator
                    if (state.isArchitectStreaming) {
                        item {
                            Row(
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(Res.string.skills_architect_thinking),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── Validate button (when ready) ──
                if (state.architectPhase == ArchitectPhase.ReadyToValidate) {
                    if (state.isEditFlow) {
                        DiffViewer(
                            oldContent = state.originalContentForDiff,
                            newContent = state.architectGeneratedContent,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = onValidate,
                        enabled = !state.isSaving,
                        modifier = Modifier.fillMaxWidth().testTag("architect_validate"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                        ),
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onTertiary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (state.isSaving) stringResource(Res.string.skills_architect_saving) else stringResource(Res.string.skills_architect_validate))
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // ── Input ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                when (state.architectPhase) {
                                    ArchitectPhase.CollectName -> stringResource(Res.string.skills_architect_placeholder_name)
                                    ArchitectPhase.CollectDescription -> stringResource(Res.string.skills_architect_placeholder_desc)
                                    else -> stringResource(Res.string.skills_architect_placeholder_modify)
                                },
                            )
                        },
                        modifier = Modifier.weight(1f).testTag("architect_input"),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        enabled = !state.isArchitectStreaming,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                onSubmit(inputText)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank() && !state.isArchitectStreaming,
                        modifier = Modifier.testTag("architect_send"),
                    ) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = stringResource(Res.string.action_send),
                            tint = if (inputText.isNotBlank() && !state.isArchitectStreaming) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                        )
                    }
                }
            }
        }
    }
}

// ── Single message bubble ───────────────────────────────────────────
@Composable
private fun ArchitectMessageBubble(msg: ArchitectMessage) {
    val bgColor = when {
        msg.isUser -> MaterialTheme.colorScheme.primaryContainer
        msg.isSystem -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = when {
        msg.isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        msg.isSystem -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val alignment = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (msg.isUser) 16.dp else 4.dp,
                bottomEnd = if (msg.isUser) 4.dp else 16.dp,
            ),
            color = bgColor,
            contentColor = textColor,
            modifier = Modifier
                .widthIn(max = 480.dp)
                .align(alignment),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
            ) {
                SimpleMarkdownText(
                    markdown = msg.text,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val clipboardManager = LocalClipboardManager.current
                    var copied by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(msg.text))
                            copied = true
                            scope.launch {
                                delay(2000)
                                copied = false
                            }
                        },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = stringResource(Res.string.skills_copy_message_a11y),
                            tint = textColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Line-by-line Diff Viewer ─────────────────────────────────────────
@Composable
private fun DiffViewer(
    oldContent: String,
    newContent: String,
) {
    val diffs = remember(oldContent, newContent) {
        dev.promethe.app.screens.viewmodel.diffLines(oldContent, newContent)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(Res.string.skills_diff_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        diffs.forEach { line ->
            val bgColor = when (line.type) {
                dev.promethe.app.screens.viewmodel.DiffType.ADD -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                dev.promethe.app.screens.viewmodel.DiffType.REMOVE -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                dev.promethe.app.screens.viewmodel.DiffType.EQUAL -> androidx.compose.ui.graphics.Color.Transparent
            }
            val textColor = when (line.type) {
                dev.promethe.app.screens.viewmodel.DiffType.ADD -> MaterialTheme.colorScheme.primary
                dev.promethe.app.screens.viewmodel.DiffType.REMOVE -> MaterialTheme.colorScheme.error
                dev.promethe.app.screens.viewmodel.DiffType.EQUAL -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val prefix = when (line.type) {
                dev.promethe.app.screens.viewmodel.DiffType.ADD -> "+ "
                dev.promethe.app.screens.viewmodel.DiffType.REMOVE -> "- "
                dev.promethe.app.screens.viewmodel.DiffType.EQUAL -> "  "
            }
            Text(
                text = prefix + line.text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = textColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor)
                    .padding(vertical = 1.dp, horizontal = 4.dp),
            )
        }
    }
}
