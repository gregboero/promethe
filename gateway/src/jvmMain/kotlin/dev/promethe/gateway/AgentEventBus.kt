package dev.promethe.gateway

import dev.promethe.api.AgentExecutionEvent
import dev.promethe.core.ConversationTrajectory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-memory event bus that broadcasts [AgentExecutionEvent]s to all WebSocket
 * clients connected on `/ws/agents`.
 *
 * Gateway SSE handlers call [emit] for every trajectory step; WebSocket sessions
 * collect from [events].
 */
object AgentEventBus {
    private val _events = MutableSharedFlow<AgentExecutionEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<AgentExecutionEvent> = _events.asSharedFlow()

    suspend fun emit(event: AgentExecutionEvent) {
        _events.emit(event)
    }

    /**
     * Converts a PRA-loop [ConversationTrajectory] step into an [AgentExecutionEvent].
     *
     * Mapping:
     * - action + observation        → "observation" (tool returned a result)
     * - action without observation  → "tool_call"   (tool invoked)
     * - thought only                → "step_start"  (agent is thinking)
     * - no action, has output       → "step_complete"
     */
    fun ConversationTrajectory.toAgentEvent(agentId: String = "main"): AgentExecutionEvent {
        val now = System.currentTimeMillis()
        val act = this.action
        val obs = this.observation
        val thght = this.thought

        return when {
            // Tool finished with a result
            act != null && obs != null -> {
                AgentExecutionEvent(
                    agentId = agentId,
                    type = "observation",
                    tool = act.toolName,
                    content = obs.take(200),
                    timestamp = now,
                )
            }

            // Tool invoked, no result yet
            act != null -> {
                AgentExecutionEvent(
                    agentId = agentId,
                    type = "tool_call",
                    tool = act.toolName,
                    content = act.args.toString().take(200),
                    timestamp = now,
                )
            }

            // Agent is thinking
            thght != null -> {
                AgentExecutionEvent(
                    agentId = agentId,
                    type = "step_start",
                    content = thght.take(200),
                    timestamp = now,
                )
            }

            // Final output or empty step
            else -> {
                val answer = this.outputs["final_answer"] ?: this.outputs.values.firstOrNull()
                AgentExecutionEvent(
                    agentId = agentId,
                    type = if (answer != null) "step_complete" else "step_start",
                    content = answer?.take(200) ?: "Processing…",
                    timestamp = now,
                )
            }
        }
    }
}
