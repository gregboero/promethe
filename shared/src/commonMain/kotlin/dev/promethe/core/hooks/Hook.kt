package dev.promethe.core.hooks

import kotlinx.serialization.json.JsonObject

/**
 * Lifecycle event types that hooks can intercept.
 */
enum class HookEvent {
    /** Fired before a tool is invoked by the agent. */
    BEFORE_TOOL_CALL,

    /** Fired after a tool returns a result. */
    AFTER_TOOL_CALL,

    /** Fired when a new user session starts. */
    SESSION_START,

    /** Fired when a session completes (all iterations done). */
    SESSION_END,

    /** Fired when the agent encounters an unrecoverable error. */
    ON_ERROR,

    /** Fired when a user message is received (before processing). */
    MESSAGE_RECEIVED,

    /** Fired when an assistant response is about to be sent. */
    BEFORE_RESPONSE,
}

/**
 * Context passed to hooks at invocation time.
 */
data class HookContext(
    val event: HookEvent,
    val sessionId: String = "",
    val toolName: String = "",
    val toolArgs: JsonObject? = null,
    val toolResult: String? = null,
    val userMessage: String = "",
    val agentResponse: String = "",
    val error: Throwable? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Result returned by a hook to control flow.
 */
sealed class HookResult {
    /** Continue normal execution. */
    data object Continue : HookResult()

    /** Abort the current operation with a reason. */
    data class Abort(
        val reason: String,
    ) : HookResult()

    /** Replace the tool result or response with a modified value. */
    data class Modify(
        val newValue: String,
    ) : HookResult()
}

/**
 * A hook that can be registered to intercept agent lifecycle events.
 *
 * Hooks are executed in registration order.
 * If any hook returns [HookResult.Abort], subsequent hooks are skipped.
 */
interface Hook {
    /** Unique identifier for this hook. */
    val id: String

    /** Which events this hook listens to. */
    val events: Set<HookEvent>

    /** Priority: lower values execute first. Default is 100. */
    val priority: Int get() = 100

    /** Execute the hook. Should be fast and non-blocking. */
    suspend fun execute(context: HookContext): HookResult
}
