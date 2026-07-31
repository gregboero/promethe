package dev.promethe.core.tools.builtin

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

// ══════════════════════════════════════════════════════════════
// Agent-level tools for Hermes parity:
//   - ClarifyTool       → ask user for clarification
//   - CronjobTool       → schedule recurring tasks
//   - SendMessageTool   → send message to a messaging channel
// ══════════════════════════════════════════════════════════════

// ── ClarifyTool ─────────────────────────────────────────────

@Serializable
data class ClarifyArgs(
    @property:LLMDescription("The question to ask the user for clarification.")
    val question: String,
    @property:LLMDescription("Optional list of suggested answers to present as choices.")
    val suggestions: List<String> = emptyList(),
)

/**
 * ClarifyTool — allows the agent to pause and ask the user a clarifying question.
 *
 * When the agent is uncertain about the user's intent, it can use this tool
 * instead of guessing. The tool returns a special marker that the agent loop
 * intercepts to request user input.
 */
class ClarifyTool :
    SimpleTool<ClarifyArgs>(
        argsType = typeToken<ClarifyArgs>(),
        name = "clarify",
        description = "Ask the user a clarifying question when the request is ambiguous or underspecified. Use this instead of guessing.",
    ) {
    override suspend fun execute(args: ClarifyArgs): String {
        // The agent loop intercepts the CLARIFY marker and prompts the user.
        // The tool itself just returns a structured marker.
        val sb = StringBuilder()
        sb.appendLine("[CLARIFY]")
        sb.appendLine("Question: ${args.question}")
        if (args.suggestions.isNotEmpty()) {
            sb.appendLine("Suggestions:")
            args.suggestions.forEachIndexed { i, s ->
                sb.appendLine("  ${i + 1}. $s")
            }
        }
        return sb.toString()
    }
}

// ── CronjobTool ─────────────────────────────────────────────

@Serializable
data class CronjobArgs(
    @property:LLMDescription("Cron expression (5 fields: min hour dom mon dow). Example: '*/30 * * * *' for every 30 minutes.")
    val cronExpression: String,
    @property:LLMDescription("The task description or prompt to execute on schedule.")
    val task: String,
    @property:LLMDescription("Optional: unique ID for this cron job. Auto-generated if blank.")
    val jobId: String = "",
    @property:LLMDescription("Action: 'create', 'list', or 'delete'.")
    val action: String = "create",
)

/**
 * CronjobTool — expose the TaskScheduler as an LLM-callable tool.
 *
 * Allows the agent to schedule recurring tasks, list existing jobs,
 * or delete scheduled jobs — matching Hermes' `cronjob` tool.
 */
class CronjobTool(
    private val scheduler: dev.promethe.core.TaskScheduler,
) : SimpleTool<CronjobArgs>(
        argsType = typeToken<CronjobArgs>(),
        name = "cronjob",
        description = "Create, list, or delete scheduled recurring tasks using cron expressions." +
            " The agent can schedule itself to run tasks periodically.",
    ) {
    override suspend fun execute(args: CronjobArgs): String {
        return when (args.action.lowercase()) {
            "create" -> {
                val id = args.jobId.ifBlank { "cron_${System.currentTimeMillis()}" }
                try {
                    scheduler.schedule(
                        jobId = id,
                        cronExpression = args.cronExpression,
                        taskDescription = args.task,
                    )
                    "✅ Cron job '$id' scheduled: ${args.cronExpression}\nTask: ${args.task}"
                } catch (e: Exception) {
                    "[ERROR] Failed to create cron job: ${e.message}"
                }
            }

            "list" -> {
                val jobs = scheduler.listJobs()
                if (jobs.isEmpty()) {
                    "No scheduled jobs."
                } else {
                    val sb = StringBuilder("Scheduled jobs:\n")
                    jobs.forEach { job ->
                        sb.appendLine("  • ${job.id} — ${job.cronExpression} — ${job.description}")
                    }
                    sb.toString()
                }
            }

            "delete" -> {
                if (args.jobId.isBlank()) return "[ERROR] jobId required for delete."
                val removed = scheduler.cancel(args.jobId)
                if (removed) "✅ Cron job '${args.jobId}' deleted." else "❌ Job '${args.jobId}' not found."
            }

            else -> {
                "[ERROR] Unknown action '${args.action}'. Use 'create', 'list', or 'delete'."
            }
        }
    }
}

// ── SendMessageTool ────────────────────────────────────────

@Serializable
data class SendMessageArgs(
    @property:LLMDescription("Target channel: 'telegram', 'discord', 'slack', 'signal', 'email', or 'sms'.")
    val channel: String,
    @property:LLMDescription("The message text to send.")
    val message: String,
    @property:LLMDescription("Recipient identifier (chat ID, channel name, email address, phone number).")
    val recipient: String,
)

/**
 * SendMessageTool — send a message to a messaging channel from within a tool call.
 *
 * This differs from the channel gateway (which receives incoming messages).
 * This tool lets the agent proactively send outbound messages to users
 * on any configured platform — matching Hermes' `send_message` tool.
 */
class SendMessageTool(
    private val channelSender: ChannelSender,
) : SimpleTool<SendMessageArgs>(
        argsType = typeToken<SendMessageArgs>(),
        name = "send_message",
        description = "Send a message to a user or channel on a messaging platform (Telegram, Discord, Slack, Signal, Email, SMS).",
    ) {
    override suspend fun execute(args: SendMessageArgs): String =
        try {
            channelSender.send(args.channel, args.recipient, args.message)
            "✅ Message sent to ${args.channel}:${args.recipient}"
        } catch (e: Exception) {
            "[ERROR] Failed to send message: ${e.message}"
        }
}

/**
 * Abstraction for sending messages to any configured channel.
 * Implementations are provided by the gateway module.
 */
interface ChannelSender {
    suspend fun send(
        channel: String,
        recipient: String,
        message: String,
    )
}

/**
 * Default no-op sender when no gateway channels are configured.
 */
class NoOpChannelSender : ChannelSender {
    override suspend fun send(
        channel: String,
        recipient: String,
        message: String,
    ): Unit =
        throw UnsupportedOperationException(
            "Channel '$channel' is not configured. Set up the messaging gateway to enable send_message.",
        )
}
