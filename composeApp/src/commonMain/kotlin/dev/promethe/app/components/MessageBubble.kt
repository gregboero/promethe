package dev.promethe.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.promethe.api.ChatEvent
import dev.promethe.api.a2ui.UiNode
import dev.promethe.app.a2ui.A2UIRegistry
import dev.promethe.app.a2ui.DynamicContent
import dev.promethe.app.util.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*
import kotlin.time.Clock

@Composable
fun MessageBubble(
    event: ChatEvent,
    isUser: Boolean,
    timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    isVoice: Boolean = false,
    modifier: Modifier = Modifier,
    dynamicContent: DynamicContent? = null,
    onA2UIAction: ((action: String, params: Map<String, String>) -> Unit)? = null,
    ttsEnabled: Boolean = false,
    onTtsSpeak: ((text: String) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val bubbleShape =
        if (isUser) {
            RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        } else {
            RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
        }
    val bubbleColor = if (isUser) colors.primary else colors.surface
    val textColor = if (isUser) colors.onPrimary else colors.onSurface

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 480.dp)
                    .clip(bubbleShape)
                    .background(bubbleColor)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (isUser) {
                Text(
                    text = event.content ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                )
            } else {
                // Check if this is an A2UI event with a UI tree
                val uiTree = event.metadata?.get("a2ui_tree")?.let { rawElement ->
                    try {
                        val jsonStr = if (rawElement is kotlinx.serialization.json.JsonPrimitive) {
                            rawElement.content
                        } else {
                            Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), rawElement)
                        }
                        Json.decodeFromString<UiNode>(jsonStr)
                    } catch (_: Exception) {
                        null
                    }
                }

                if (uiTree != null && dynamicContent != null) {
                    // Render A2UI widget tree
                    A2UIRegistry.Render(
                        node = uiTree,
                        data = dynamicContent,
                        onAction = onA2UIAction ?: { _, _ -> },
                    )
                } else {
                    val content = event.content ?: ""

                    // Render markdown for agent responses
                    Markdown(
                        content = content,
                        colors =
                            markdownColor(
                                text = textColor,
                                codeBackground = colors.surfaceVariant,
                            ),
                        typography =
                            markdownTypography(
                                text = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                                code =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.onSurfaceVariant,
                                    ),
                            ),
                    )

                    // Show copy buttons for code blocks
                    val codeBlocks = extractCodeBlocks(content)
                    if (codeBlocks.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        codeBlocks.forEach { block ->
                            CopyCodeButton(code = block.code, language = block.language)
                        }
                    }
                }
            }
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
                        clipboardManager.setText(AnnotatedString(event.content ?: ""))
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
                        contentDescription = stringResource(Res.string.component_message_bubble_copy_a11y),
                        tint = textColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(12.dp),
                    )
                }

                // TTS speak button — only on agent messages when TTS is enabled
                if (!isUser && ttsEnabled && onTtsSpeak != null) {
                    IconButton(
                        onClick = { onTtsSpeak(event.content ?: "") },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(Res.string.component_message_bubble_read_aloud_a11y),
                            tint = textColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }

                if (isVoice) {
                    Text(
                        text = "\uD83C\uDFA4",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    text = formatTimestamp(timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun CopyCodeButton(
    code: String,
    language: String?,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Surface(
        color = colors.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (language != null) {
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(code))
                    copied = true
                    scope.launch {
                        delay(2000)
                        copied = false
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = if (copied) colors.primary else colors.onSurfaceVariant,
                    ),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = if (copied) {
                        stringResource(Res.string.component_message_bubble_copied_a11y)
                    } else {
                        stringResource(Res.string.component_message_bubble_copy_code_a11y)
                    },
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (copied) {
                        stringResource(Res.string.component_message_bubble_copied_label)
                    } else {
                        stringResource(Res.string.component_message_bubble_copy_label)
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private data class CodeBlock(
    val language: String?,
    val code: String,
)

private fun extractCodeBlocks(markdown: String): List<CodeBlock> {
    val regex = Regex("```(\\w+)?\\n([\\s\\S]*?)```")
    return regex
        .findAll(markdown)
        .map { match ->
            CodeBlock(
                language = match.groupValues[1].ifBlank { null },
                code = match.groupValues[2].trimEnd(),
            )
        }.toList()
}

private fun formatTimestamp(millis: Long): String = formatTime(millis)
