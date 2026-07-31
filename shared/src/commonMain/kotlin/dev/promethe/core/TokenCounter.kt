package dev.promethe.core

/**
 * TokenCounter — lightweight token estimation.
 *
 * Uses the industry-standard heuristic of ~4 characters per token (for English/code).
 * This avoids pulling in tiktoken as a dependency while being accurate enough
 * for context window management (typically within ±10% of actual count).
 */
object TokenCounter {
    /** Average characters per token for most LLMs (GPT, Llama, Mistral). */
    private const val CHARS_PER_TOKEN = 4.0

    /** Estimate token count for a single string. */
    fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        return (text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
    }

    /** Estimate total tokens for a list of role+content message pairs. */
    fun estimateMessages(messages: List<Pair<String, String>>): Int {
        // Each message has ~4 tokens of overhead (role, delimiters)
        return messages.sumOf { (role, content) -> 4 + estimate(role) + estimate(content) }
    }

    /** Estimate tokens for a system prompt. */
    fun estimateSystemPrompt(systemPrompt: String): Int {
        return 4 + estimate(systemPrompt) // 4 tokens overhead for system role
    }
}
