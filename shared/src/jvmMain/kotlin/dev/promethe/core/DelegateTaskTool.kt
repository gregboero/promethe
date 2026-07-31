package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

// ── Argument data class ──────────────────────────────────────────

@Serializable
data class DelegateTaskArgs(
    @property:LLMDescription("The task description to delegate to a sub-agent.")
    val task: String,
    @property:LLMDescription("Optional: the agent profile ID to use (determines model/provider). Leave empty for default.")
    val profileId: String = "",
    @property:LLMDescription("Optional: custom system prompt override for the sub-agent.")
    val systemPrompt: String = "",
    @property:LLMDescription(
        "If true, wait for the sub-agent to complete and return its response. If false, return immediately with the session ID.",
    )
    val blocking: Boolean = true,
)

// ── Tool implementation ────────────────────────────────────────

/**
 * DelegateTaskTool — allows the main agent to spawn sub-agents via tool calls.
 *
 * Usage in LLM response:
 * ```json
 * {
 *   "action": {
 *     "tool_name": "delegate_task",
 *     "args": {
 *       "task": "Summarize the README.md file",
 *       "profileId": "profile-uuid-for-claude",
 *       "blocking": true
 *     }
 *   }
 * }
 * ```
 */
class DelegateTaskTool(
    private val orchestrator: AgentOrchestrator,
    private val parentSessionId: String,
) : SimpleTool<DelegateTaskArgs>(
        argsType = typeToken<DelegateTaskArgs>(),
        name = "delegate_task",
        description =
            """Delegate a task to a sub-agent. The sub-agent runs in an isolated session with its own conversation.
        |Use this to break complex tasks into smaller pieces, or to use a specialized model for a specific task.
        |Set 'blocking' to true to wait for the result, or false to get a session ID for later retrieval.
        |Set 'profileId' to route to a specific model (e.g., a cheaper model for summarization, or a code-specialist model).
            """.trimMargin(),
    ) {
    override suspend fun execute(args: DelegateTaskArgs): String {
        val request =
            AgentOrchestrator.DelegationRequest(
                task = args.task,
                systemPromptOverride = args.systemPrompt.ifBlank { null },
                profileId = args.profileId.ifBlank { null },
                parentSessionId = parentSessionId,
            )

        return if (args.blocking) {
            // Blocking: spawn and wait
            val childSessionId = orchestrator.delegateTask(request)

            // Poll for completion (the job is already running in background)
            var attempts = 0
            val maxAttempts = 600 // 10 minutes max (1s per check)
            while (orchestrator.isRunning(childSessionId) && attempts < maxAttempts) {
                kotlinx.coroutines.delay(1000)
                attempts++
            }

            val result = orchestrator.getResult(childSessionId)
            if (result != null) {
                "[Sub-Agent Result (${result.status}, ${result.durationMs}ms)]\n${result.response}"
            } else {
                "[ERROR] Sub-agent timed out after ${maxAttempts}s"
            }
        } else {
            // Non-blocking: return session ID
            val childSessionId = orchestrator.delegateTask(request)
            "[Sub-Agent Spawned] Session ID: $childSessionId — Use 'get_subtask_result' to retrieve the result later."
        }
    }
}

// ── Companion tool: retrieve results of async sub-agents ────────

@Serializable
data class GetSubtaskResultArgs(
    @property:LLMDescription("The session ID of the sub-agent to retrieve the result from.")
    val sessionId: String,
)

class GetSubtaskResultTool(
    private val orchestrator: AgentOrchestrator,
) : SimpleTool<GetSubtaskResultArgs>(
        argsType = typeToken<GetSubtaskResultArgs>(),
        name = "get_subtask_result",
        description = "Retrieve the result of a previously spawned sub-agent by its session ID.",
    ) {
    override suspend fun execute(args: GetSubtaskResultArgs): String {
        if (orchestrator.isRunning(args.sessionId)) {
            return "[Sub-Agent Still Running] Session ${args.sessionId} has not completed yet."
        }

        val result =
            orchestrator.getResult(args.sessionId)
                ?: return "[ERROR] No result found for session ${args.sessionId}"

        return "[Sub-Agent Result (${result.status}, ${result.durationMs}ms)]\n${result.response}"
    }
}
