package dev.promethe.core.memory

/**
 * MemoryNudge — periodic prompt injection that encourages the agent
 * to save important facts to persistent memory.
 *
 * Inspired by Hermes Agent's memory system where the agent autonomously
 * decides what to remember after conversations.
 *
 * The nudge fires every N user messages. It injects a hidden system-level
 * instruction that asks the agent to call memory_save if it learned anything.
 */
class MemoryNudge(
    private val intervalMessages: Int = 5,
) {
    private var messageCount = 0

    /**
     * Call on every user message. Returns a nudge prompt if it's time to remind
     * the agent, or null otherwise.
     */
    fun onUserMessage(): String? {
        messageCount++
        if (messageCount % intervalMessages == 0) {
            return NUDGE_PROMPT
        }
        return null
    }

    /**
     * Reset the counter (e.g., on session start).
     */
    fun reset() {
        messageCount = 0
    }

    companion object {
        val NUDGE_PROMPT =
            """
            |[SYSTEM NOTE - Memory Check]
            |Review this conversation so far. If the user has shared any of the following,
            |use the 'memory_save' tool to persist them:
            |  - Preferences (language, tools, frameworks, coding style, communication style)
            |  - Project context (project names, tech stack, architecture decisions)
            |  - Personal info (name, role, timezone, company)
            |  - Technical decisions or patterns they prefer
            |  - Corrections to previous assumptions
            |
            |Only save genuinely useful, atomic facts. Do NOT save trivial or obvious things.
            |If nothing new was learned, skip this step silently.
            """.trimMargin()
    }
}
