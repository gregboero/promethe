package dev.promethe.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Lightweight Markdown renderer for Compose.
 *
 * Supports: headings (#-###), bold (**), italic (*), inline code (`),
 * bullet lists (- / *), numbered lists, code blocks (```), and --- dividers.
 */
@Composable
fun SimpleMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val lines = markdown.lines()
    var i = 0

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        while (i < lines.size) {
            val line = lines[i]

            // ── Code block ──
            if (line.trimStart().startsWith("```")) {
                i++
                val codeLines = mutableListOf<String>()
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++ // skip closing ```
                CodeBlock(codeLines.joinToString("\n"))
                continue
            }

            // ── Horizontal rule ──
            if (line.trim().matches(Regex("^-{3,}$|^\\*{3,}$|^_{3,}$"))) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(4.dp))
                i++
                continue
            }

            // ── Headings ──
            val headingMatch = Regex("^(#{1,3})\\s+(.*)").find(line.trimStart())
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val text = headingMatch.groupValues[2]
                Spacer(Modifier.height(if (level == 1) 12.dp else 8.dp))
                Text(
                    text = parseInline(text),
                    style = when (level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (level <= 2) {
                    Spacer(Modifier.height(2.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
                i++
                continue
            }

            // ── Bullet list ──
            val bulletMatch = Regex("^(\\s*)[-*]\\s+(.*)").find(line)
            if (bulletMatch != null) {
                val indent = bulletMatch.groupValues[1].length / 2
                val text = bulletMatch.groupValues[2]
                Row(modifier = Modifier.padding(start = (16 * indent + 8).dp)) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(16.dp),
                    )
                    Text(
                        text = parseInline(text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                i++
                continue
            }

            // ── Numbered list ──
            val numMatch = Regex("^(\\s*)(\\d+)\\.\\s+(.*)").find(line)
            if (numMatch != null) {
                val indent = numMatch.groupValues[1].length / 2
                val num = numMatch.groupValues[2]
                val text = numMatch.groupValues[3]
                Row(modifier = Modifier.padding(start = (16 * indent + 8).dp)) {
                    Text(
                        text = "$num.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        text = parseInline(text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                i++
                continue
            }

            // ── Empty line ──
            if (line.isBlank()) {
                Spacer(Modifier.height(6.dp))
                i++
                continue
            }

            // ── Regular paragraph ──
            Text(
                text = parseInline(line),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            i++
        }
    }
}

/**
 * Parse inline markdown: **bold**, *italic*, `code`, and ~~strikethrough~~.
 */
private fun parseInline(text: String): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // Bold: **text**
                i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }

                // Italic: *text*
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }

                // Inline code: `text`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                background = androidx.compose.ui.graphics.Color(0xFF2D2D3D),
                                color = androidx.compose.ui.graphics.Color(0xFFE0E0F0),
                            ),
                        ) {
                            append(" ${text.substring(i + 1, end)} ")
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }

                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }

/**
 * Render a fenced code block with monospace font and a subtle background.
 */
@Composable
private fun CodeBlock(code: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp),
        )
    }
}
