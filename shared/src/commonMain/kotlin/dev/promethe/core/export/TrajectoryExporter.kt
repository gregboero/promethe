package dev.promethe.core.export

import dev.promethe.core.PrometheJson
import dev.promethe.core.PromethePrettyJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * TrajectoryExporter — multi-format export engine for agent reasoning traces.
 *
 * Supports:
 *   - ShareGPT format (conversations array)
 *   - JSONL (one message per line)
 *   - OpenAI fine-tuning format (messages array)
 *
 * Input: List of TrajectoryEntry (session + messages)
 * Output: String in the selected format
 */
class TrajectoryExporter {
    private val json = PromethePrettyJson
    private val jsonCompact = PrometheJson

    /**
     * Export trajectories in the specified format.
     */
    fun export(
        entries: List<TrajectoryEntry>,
        format: ExportFormat,
        filter: TrajectoryFilter = TrajectoryFilter(),
    ): String {
        val filtered = applyFilter(entries, filter)
        return when (format) {
            ExportFormat.SHAREGPT -> exportShareGpt(filtered)
            ExportFormat.JSONL -> exportJsonl(filtered)
            ExportFormat.OPENAI_FINETUNE -> exportOpenAiFineTune(filtered)
        }
    }

    // ── ShareGPT Format ──────────────────────────────────────────────

    private fun exportShareGpt(entries: List<TrajectoryEntry>): String {
        val conversations = entries.map { entry ->
            ShareGptConversation(
                id = entry.sessionId,
                conversations = entry.messages.map { msg ->
                    ShareGptMessage(
                        from = when (msg.role) {
                            "system" -> "system"
                            "user" -> "human"
                            "assistant" -> "gpt"
                            "tool" -> "tool"
                            else -> msg.role
                        },
                        value = msg.content,
                    )
                },
            )
        }
        return json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ShareGptConversation.serializer()),
            conversations,
        )
    }

    // ── JSONL Format ─────────────────────────────────────────────────

    private fun exportJsonl(entries: List<TrajectoryEntry>): String =
        entries.flatMap { entry ->
            entry.messages.map { msg ->
                jsonCompact.encodeToString(
                    JsonlLine.serializer(),
                    JsonlLine(
                        sessionId = entry.sessionId,
                        timestamp = msg.timestamp,
                        role = msg.role,
                        content = msg.content,
                        toolName = msg.toolName,
                        metadata = msg.metadata,
                    ),
                )
            }
        }.joinToString("\n")

    // ── OpenAI Fine-Tuning Format ────────────────────────────────────

    private fun exportOpenAiFineTune(entries: List<TrajectoryEntry>): String =
        entries.joinToString("\n") { entry ->
            val messages = entry.messages
                .filter { it.role in setOf("system", "user", "assistant") }
                .map { msg ->
                    OpenAiMessage(role = msg.role, content = msg.content)
                }
            jsonCompact.encodeToString(
                OpenAiFineTuneEntry.serializer(),
                OpenAiFineTuneEntry(messages = messages),
            )
        }

    // ── Filter ───────────────────────────────────────────────────────

    private fun applyFilter(
        entries: List<TrajectoryEntry>,
        filter: TrajectoryFilter,
    ): List<TrajectoryEntry> {
        var result = entries

        if (filter.minSteps > 0) {
            result = result.filter { it.messages.size >= filter.minSteps }
        }
        if (filter.successOnly) {
            result = result.filter { entry ->
                entry.messages.none { it.content.startsWith("[ERROR]") }
            }
        }
        if (filter.fromTimestamp != null) {
            result = result.filter { entry ->
                entry.messages.any { it.timestamp >= filter.fromTimestamp }
            }
        }
        if (filter.toTimestamp != null) {
            result = result.filter { entry ->
                entry.messages.any { it.timestamp <= filter.toTimestamp }
            }
        }
        if (filter.tags.isNotEmpty()) {
            result = result.filter { entry ->
                filter.tags.any { tag -> entry.tags.contains(tag) }
            }
        }
        return result
    }
}

// ── Data Classes ─────────────────────────────────────────────────────

enum class ExportFormat {
    SHAREGPT,
    JSONL,
    OPENAI_FINETUNE,
}

@Serializable
data class TrajectoryEntry(
    val sessionId: String,
    val messages: List<TrajectoryMessage>,
    val tags: List<String> = emptyList(),
)

@Serializable
data class TrajectoryMessage(
    val role: String,
    val content: String,
    val timestamp: Long = 0,
    val toolName: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class TrajectoryFilter(
    val minSteps: Int = 0,
    val successOnly: Boolean = false,
    val fromTimestamp: Long? = null,
    val toTimestamp: Long? = null,
    val tags: List<String> = emptyList(),
)

// ── Format-specific models ───────────────────────────────────────────

@Serializable
data class ShareGptConversation(
    val id: String,
    val conversations: List<ShareGptMessage>,
)

@Serializable
data class ShareGptMessage(
    val from: String,
    val value: String,
)

@Serializable
data class JsonlLine(
    val sessionId: String,
    val timestamp: Long,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class OpenAiFineTuneEntry(
    val messages: List<OpenAiMessage>,
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: String,
)
