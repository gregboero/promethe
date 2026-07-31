package dev.promethe.core

/**
 * ToolOutputPruner — reduces verbose tool outputs before context compression.
 *
 * Applied as a pre-processing step before [ContextCompressor] to remove noise:
 * - Truncates overly long tool outputs (keep head + tail)
 * - Collapses stack traces to root cause only
 * - Replaces raw HTML dumps with a placeholder
 *
 * This improves compression quality by feeding cleaner data to the LLM summarizer.
 */
object ToolOutputPruner {
    /** Maximum chars for a tool output before truncation kicks in. */
    private const val MAX_TOOL_OUTPUT = 2000

    /** How many chars to keep from the start of a truncated output. */
    private const val KEEP_HEAD = 500

    /** How many chars to keep from the end of a truncated output. */
    private const val KEEP_TAIL = 500

    /** Max stack trace lines to keep. */
    private const val MAX_STACK_LINES = 5

    private val STACK_TRACE_PATTERN = Regex("""^\s+at .+\(.+:\d+\)""", RegexOption.MULTILINE)
    private val HTML_PATTERN = Regex("""<(!DOCTYPE|html|head|body)\b""", RegexOption.IGNORE_CASE)
    private val JSON_ARRAY_PATTERN = Regex("""^\s*\[\s*\{""")

    /**
     * Prunes a list of messages in-place (returns new list).
     * Only modifies messages with role "tool" or "assistant" containing tool output blocks.
     */
    fun prune(messages: List<Pair<String, String>>): List<Pair<String, String>> =
        messages.map { (role, content) ->
            if (role == "tool" || (role == "assistant" && content.length > MAX_TOOL_OUTPUT)) {
                role to pruneContent(content)
            } else {
                role to content
            }
        }

    /**
     * Prune a single content string.
     */
    fun pruneContent(content: String): String {
        if (content.length <= MAX_TOOL_OUTPUT) return content

        // 1. Raw HTML → placeholder
        if (HTML_PATTERN.containsMatchIn(content.take(200))) {
            return "[HTML output — ${content.length} chars, not shown]"
        }

        // 2. Stack traces → collapse
        val stackPruned = collapseStackTraces(content)
        if (stackPruned.length <= MAX_TOOL_OUTPUT) return stackPruned

        // 3. Large JSON arrays → truncate
        if (JSON_ARRAY_PATTERN.containsMatchIn(content.take(50))) {
            return truncateWithMarker(content, "JSON array")
        }

        // 4. Generic truncation: keep head + tail
        return truncateWithMarker(stackPruned, "output")
    }

    /**
     * Collapses Java/Kotlin/Python stack traces to first N lines + root cause.
     */
    private fun collapseStackTraces(content: String): String {
        val lines = content.lines()
        val result = StringBuilder()
        var stackCount = 0
        var totalPruned = 0

        for (line in lines) {
            if (STACK_TRACE_PATTERN.matches(line)) {
                stackCount++
                if (stackCount <= MAX_STACK_LINES) {
                    result.appendLine(line)
                } else {
                    totalPruned++
                }
            } else {
                if (totalPruned > 0) {
                    result.appendLine("    ... $totalPruned more stack frames pruned ...")
                    totalPruned = 0
                }
                stackCount = 0
                result.appendLine(line)
            }
        }

        if (totalPruned > 0) {
            result.appendLine("    ... $totalPruned more stack frames pruned ...")
        }

        return result.toString().trimEnd()
    }

    /**
     * Truncates content keeping head and tail, with a marker in the middle.
     */
    private fun truncateWithMarker(
        content: String,
        kind: String,
    ): String {
        val prunedChars = content.length - KEEP_HEAD - KEEP_TAIL
        return buildString {
            append(content.take(KEEP_HEAD))
            append("\n\n[...$kind pruned: $prunedChars chars removed...]\n\n")
            append(content.takeLast(KEEP_TAIL))
        }
    }
}
