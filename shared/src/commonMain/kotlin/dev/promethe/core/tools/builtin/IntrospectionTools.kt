package dev.promethe.core.tools.builtin

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.KoogLlmAdapter
import dev.promethe.db.PrometheDatabaseApi
import kotlinx.serialization.Serializable

/**
 * IntrospectionTools -- tools that let the agent inspect its own runtime status.
 *
 * - agent_status: Get current LLM stats, memory provider, uptime
 * - session_history: List recent sessions with message counts
 * - token_budget: Check remaining token budget for current session
 *
 * These tools close the "self-awareness gap" vs Hermes Agent,
 * allowing the agent to reason about its own resource usage.
 */

// ── agent_status ────────────────────────────────────────────

@Serializable
data class AgentStatusArgs(
    @property:LLMDescription("Set to true for per-provider breakdown.")
    val verbose: Boolean = false,
)

class AgentStatusTool(
    private val llmAdapter: KoogLlmAdapter,
    private val database: PrometheDatabaseApi,
) : SimpleTool<AgentStatusArgs>(
        argsType = typeToken<AgentStatusArgs>(),
        name = "agent_status",
        description =
            """Get the agent's current runtime status.
        |Returns LLM usage stats (requests, tokens, cost), memory provider info,
        |and prompt cache performance. Use this to monitor resource consumption.
            """.trimMargin(),
    ) {
    override suspend fun execute(args: AgentStatusArgs): String {
        val stats = llmAdapter.getStats()
        val poolStats = llmAdapter.getPoolStats()

        return buildString {
            appendLine("[Agent Status]")
            appendLine("  Requests: ${stats.totalRequests}")
            appendLine("  Prompt tokens: ${stats.promptTokens}")
            appendLine("  Completion tokens: ${stats.completionTokens}")
            appendLine("  Estimated cost: $${String.format("%.4f", stats.totalCost)}")
            appendLine("  Provider cache: ${stats.cacheHits} hits / ${stats.cacheMisses} misses (${stats.cacheReadTokens} read tokens)")
            appendLine("  Stable prefixes: ${stats.prefixReuseHits} reused / ${stats.prefixReuseMisses} new (${stats.cacheSize} tracked)")

            if (args.verbose && poolStats.isNotEmpty()) {
                appendLine("  Provider pools:")
                poolStats.forEach { (provider, keyCount) ->
                    appendLine("    - $provider: $keyCount key(s)")
                }
            }
        }
    }
}

// ── session_history ─────────────────────────────────────────

@Serializable
data class SessionHistoryArgs(
    @property:LLMDescription("Maximum number of sessions to return.")
    val limit: Int = 10,
    @property:LLMDescription("User ID to filter sessions for.")
    val userId: String = "default",
)

class SessionHistoryTool(
    private val database: PrometheDatabaseApi,
) : SimpleTool<SessionHistoryArgs>(
        argsType = typeToken<SessionHistoryArgs>(),
        name = "session_history",
        description =
            """List recent conversation sessions.
        |Returns session IDs with message counts and last activity timestamps.
        |Use this to find previous conversations or check session status.
            """.trimMargin(),
    ) {
    override suspend fun execute(args: SessionHistoryArgs): String =
        try {
            val sessions = database.getRecentSessions(args.userId, args.limit)
            if (sessions.isEmpty()) {
                "[Sessions] No sessions found for user '${args.userId}'."
            } else {
                buildString {
                    appendLine("[Sessions] ${sessions.size} recent session(s):")
                    sessions.forEach { session ->
                        appendLine("  - ${session.sessionId}: ${session.messageCount} messages (last: ${session.lastActivity})")
                    }
                }
            }
        } catch (e: Exception) {
            "[Sessions] Error fetching history: ${e.message}"
        }
}

// ── token_budget ────────────────────────────────────────────

@Serializable
data class TokenBudgetArgs(
    @property:LLMDescription("Optional session ID. If empty, shows global stats.")
    val sessionId: String = "",
)

class TokenBudgetTool(
    private val llmAdapter: KoogLlmAdapter,
    private val resourceGovernors: dev.promethe.core.ResourceGovernorRegistry = dev.promethe.core.GlobalResourceGovernorRegistry,
) : SimpleTool<TokenBudgetArgs>(
        argsType = typeToken<TokenBudgetArgs>(),
        name = "token_budget",
        description =
            """Check token consumption and remaining budget.
        |Returns total tokens used across all sessions, estimated cost,
        |and prompt cache efficiency. Useful for cost-aware decisions.
            """.trimMargin(),
    ) {
    override suspend fun execute(args: TokenBudgetArgs): String {
        val stats = llmAdapter.getStats()
        val total = stats.promptTokens + stats.completionTokens
        val cacheTotal = stats.cacheHits + stats.cacheMisses
        val hitRate = if (cacheTotal > 0) (stats.cacheHits * 100.0 / cacheTotal) else 0.0
        val quotas = resourceGovernors.quotaSnapshot()

        return buildString {
            appendLine("[Token Budget]")
            appendLine("  Total tokens used: $total")
            appendLine("    Prompt: ${stats.promptTokens}")
            appendLine("    Completion: ${stats.completionTokens}")
            appendLine("  Total requests: ${stats.totalRequests}")
            appendLine("  Estimated cost: $${String.format("%.4f", stats.totalCost)}")
            appendLine("  Provider cache hit rate: ${String.format("%.1f", hitRate)}% (${stats.cacheObservableResponses} observable)")
            appendLine("  Provider cache tokens: ${stats.cacheReadTokens} read / ${stats.cacheWriteTokens} written")
            appendLine("  Stable prefix reuse: ${stats.prefixReuseHits} reused / ${stats.prefixReuseMisses} new")
            if (stats.totalRequests > 0) {
                appendLine("  Avg tokens/request: ${total / stats.totalRequests}")
            }
            appendLine("  Aggregate start quotas (local profile; not USD limits):")
            val policies = resourceGovernors.quotaPolicies()
            if (policies.isEmpty()) appendLine("    Disabled")
            policies.forEach { policy ->
                appendLine("    Policy ${policy.id}: ${policy.dimension}/${policy.selector} ${policy.resource}, max=${policy.maxStarts} per ${policy.windowSeconds}s; wildcard is per subject")
            }
            quotas.forEach { quota ->
                appendLine("    ${quota.ruleId}: ${quota.dimension}/${quota.subject} ${quota.resource} ${quota.used}/${quota.maxStarts}, resetsAt=${quota.resetsAt}")
            }
        }
    }
}
