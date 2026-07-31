package dev.promethe.core

import dev.promethe.core.Log

import kotlinx.coroutines.withContext

/**
 * ContextCompressor — manages conversation context to fit within model token limits.
 *
 * When the conversation history exceeds [compressionThreshold] of the model's context
 * window, the compressor:
 *   1. Keeps the first [keepFirstN] messages (initial context / instructions)
 *   2. Keeps the last [keepLastN] messages (recent context)
 *   3. Summarizes everything in between into a single "[Context Summary]" message
 *
 * The summary is cached by hash to avoid re-summarizing the same block.
 *
 * Modeled after Hermes Agent's ContextCompressor with the same sliding-window strategy.
 */
class ContextCompressor(
    private val llmAdapter: KoogLlmAdapter,
    private val config: AgentConfig,
) {
    private val logger = Log.create("ContextCompressor")

    /** Number of messages to preserve from the start of conversation. */
    private val keepFirstN: Int = 2

    /** Number of messages to preserve from the end of conversation (most recent). */
    private val keepLastN: Int = 6

    /** Cache: hash of compressed block → summary text */
    private val summaryCache = mutableMapOf<Int, String>()

    /**
     * Compresses the message history if it exceeds the token budget.
     *
     * @param systemPrompt The system prompt (counted against the budget)
     * @param messages The full conversation history (role, content pairs from DB)
     * @return Possibly compressed message list that fits within context window
     */
    suspend fun compress(
        systemPrompt: String,
        messages: List<Pair<String, String>>,
    ): List<Pair<String, String>> {
        val maxTokens = config.maxContextTokens
        val threshold = (maxTokens * config.compressionThreshold).toInt()

        val systemTokens = TokenCounter.estimateSystemPrompt(systemPrompt)
        val messageTokens = TokenCounter.estimateMessages(messages)
        val totalTokens = systemTokens + messageTokens

        // Under threshold — no compression needed
        if (totalTokens <= threshold) return messages

        // Too few messages to compress meaningfully
        if (messages.size <= keepFirstN + keepLastN + 1) return messages

        logger.info { "History ($totalTokens tokens) exceeds threshold ($threshold). Compressing..." }

        // Phase 1: Prune verbose tool outputs before summarization
        val pruned = ToolOutputPruner.prune(messages)
        val prunedTokens = TokenCounter.estimateMessages(pruned) + systemTokens
        if (prunedTokens <= threshold) {
            logger.info { "Pruning alone reduced to $prunedTokens tokens — no summarization needed" }
            return pruned
        }

        val head = messages.take(keepFirstN)
        val tail = messages.takeLast(keepLastN)
        val middle = messages.drop(keepFirstN).dropLast(keepLastN)

        if (middle.isEmpty()) return messages // Nothing to compress

        val summary = summarize(middle)

        val compressed =
            head +
                listOf("system" to "[Context Summary — ${middle.size} messages compressed]\n$summary") +
                tail

        val newTokens = TokenCounter.estimateMessages(compressed) + systemTokens
        logger.info { "Compressed ${messages.size} → ${compressed.size} messages ($totalTokens → $newTokens tokens)" }

        return compressed
    }

    /**
     * Summarizes a block of messages into a concise paragraph.
     * Results are cached by content hash to avoid redundant LLM calls.
     */
    private suspend fun summarize(messages: List<Pair<String, String>>): String =
        withContext(ioDispatcher) {
            val contentHash = messages.hashCode()

            // Cache hit — return cached summary
            summaryCache[contentHash]?.let {
                logger.debug { "Cache hit for summary (hash=$contentHash)" }
                return@withContext it
            }

            val conversation =
                messages.joinToString("\n") { (role, content) ->
                    val truncated = if (content.length > 500) content.take(500) + "..." else content
                    "[$role]: $truncated"
                }

            val prompt =
                """
                Summarize this conversation excerpt into a concise paragraph.
                Focus on: key decisions, important facts, tool results, and action items.
                Omit pleasantries, repetition, and verbose tool output.
                Keep the summary under 300 words.

                Conversation:
                $conversation
                """.trimIndent()

            val response =
                llmAdapter.complete(
                    systemPrompt = "You are a conversation summarizer. Be concise and factual.",
                    messages = listOf("user" to prompt),
                    model = config.modelName,
                    temperature = 0.1,
                )

            val summary = response.content.trim()
            summaryCache[contentHash] = summary

            // Evict old cache entries if too many
            if (summaryCache.size > 50) {
                val oldest = summaryCache.keys.first()
                summaryCache.remove(oldest)
            }

            summary
        }

    /** Clear the summary cache (e.g., on session reset). */
    fun clearCache() {
        summaryCache.clear()
    }
}
