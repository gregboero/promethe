package dev.promethe.core.hooks

import dev.promethe.core.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/*
 * Built-in hooks that ship with Promethe.
 * Each can be enabled/disabled via configuration.
 */

// ── Logging Hook ─────────────────────────────────────────────

/**
 * Logs all tool calls and their results to stdout.
 * Useful for debugging and audit trails.
 */
class LoggingHook : Hook {
    private val logger = Log.create("LoggingHook")
    override val id = "builtin.logging"
    override val events =
        setOf(
            HookEvent.BEFORE_TOOL_CALL,
            HookEvent.AFTER_TOOL_CALL,
            HookEvent.ON_ERROR,
        )
    override val priority = 10 // Run first

    override suspend fun execute(context: HookContext): HookResult {
        when (context.event) {
            HookEvent.BEFORE_TOOL_CALL -> {
                logger.debug { "Tool call: ${context.toolName} args=${context.toolArgs?.keys}" }
            }

            HookEvent.AFTER_TOOL_CALL -> {
                val resultPreview = context.toolResult?.take(200) ?: ""
                logger.debug { "Tool result: ${context.toolName} -> $resultPreview..." }
            }

            HookEvent.ON_ERROR -> {
                logger.error { "ERROR in session ${context.sessionId}: ${context.error?.message}" }
            }

            else -> {}
        }
        return HookResult.Continue
    }
}

// ── Metrics Hook ─────────────────────────────────────────────

/**
 * Tracks tool call counts and error rates.
 * Exposes metrics via [getMetrics].
 */
class MetricsHook : Hook {
    override val id = "builtin.metrics"
    override val events =
        setOf(
            HookEvent.BEFORE_TOOL_CALL,
            HookEvent.AFTER_TOOL_CALL,
            HookEvent.ON_ERROR,
            HookEvent.SESSION_START,
            HookEvent.SESSION_END,
        )
    override val priority = 20

    // Counters are hit by concurrent sessions (HookManager fires hooks outside
    // its own lock) and read by GET /status/metrics — guard everything.
    private val mutex = Mutex()
    private val toolCallCount = mutableMapOf<String, Int>()
    private var errorCount = 0
    private var sessionCount = 0

    override suspend fun execute(context: HookContext): HookResult {
        mutex.withLock {
            when (context.event) {
                HookEvent.BEFORE_TOOL_CALL -> {
                    toolCallCount[context.toolName] = (toolCallCount[context.toolName] ?: 0) + 1
                }

                HookEvent.ON_ERROR -> {
                    errorCount++
                }

                HookEvent.SESSION_START -> {
                    sessionCount++
                }

                else -> {}
            }
        }
        return HookResult.Continue
    }

    suspend fun getMetrics(): Map<String, Any> =
        mutex.withLock {
            mapOf(
                "tool_calls" to toolCallCount.toMap(),
                "error_count" to errorCount,
                "session_count" to sessionCount,
            )
        }
}

// ── Command Guardrail Hook ───────────────────────────────────

/**
 * Blocks dangerous tool calls based on configurable patterns.
 * Acts as a safety guardrail for the agent.
 */
class GuardrailHook(
    private val blockedTools: Set<String> = emptySet(),
    private val blockedArgPatterns: List<Regex> = emptyList(),
) : Hook {
    override val id = "builtin.guardrail"
    override val events = setOf(HookEvent.BEFORE_TOOL_CALL)
    override val priority = 5 // Run before everything

    override suspend fun execute(context: HookContext): HookResult {
        // Block specific tools
        if (context.toolName in blockedTools) {
            return HookResult.Abort("Tool '${context.toolName}' is blocked by guardrail policy")
        }

        // Block if args match dangerous patterns
        val argsStr = context.toolArgs?.guardrailText(context.toolName) ?: ""
        for (pattern in blockedArgPatterns) {
            if (pattern.containsMatchIn(argsStr)) {
                return HookResult.Abort("Tool args match blocked pattern: ${pattern.pattern}")
            }
        }

        return HookResult.Continue
    }
}

private fun JsonObject.guardrailText(toolName: String): String {
    if (toolName !in setOf("execute_command", "shell")) return toString()
    val executable =
        (this["executable"] ?: this["command"])
            ?.let { element -> (element as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .orEmpty()
    val arguments =
        (this["arguments"] ?: this["args"])
            ?.let { element -> element as? kotlinx.serialization.json.JsonArray }
            ?.mapNotNull { element -> (element as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .orEmpty()
    return (listOf(executable) + arguments).filter(String::isNotBlank).joinToString(" ")
}

// ── Webhook Dispatch Hook ────────────────────────────────────

/**
 * Dispatches events to an external HTTP webhook.
 * Fires on session events and errors.
 */
class WebhookDispatchHook(
    private val webhookUrl: String,
) : Hook {
    private val logger = Log.create("WebhookDispatchHook")
    override val id = "builtin.webhook"
    override val events =
        setOf(
            HookEvent.SESSION_START,
            HookEvent.SESSION_END,
            HookEvent.ON_ERROR,
        )
    override val priority = 90 // Run late

    override suspend fun execute(context: HookContext): HookResult {
        // Fire-and-forget: webhook dispatch is best-effort
        try {
            val payload =
                buildString {
                    append("{\"event\":\"${context.event.name}\"")
                    append(",\"session\":\"${context.sessionId}\"")
                    if (context.error != null) {
                        val msg = context.error.message?.replace("\"", "\\\"") ?: ""
                        append(",\"error\":\"$msg\"")
                    }
                    append("}")
                }
            // Note: actual HTTP dispatch would use the shared httpClient.
            // For now we log the intent — actual wiring done in AgentBootstrap.
            logger.debug { "POST $webhookUrl -> $payload" }
        } catch (e: Exception) {
            // Best-effort — never abort for webhook failures
            logger.warn(e) { "Webhook dispatch to $webhookUrl failed" }
        }
        return HookResult.Continue
    }
}
