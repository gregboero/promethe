package dev.promethe.core.tools.builtin

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.AgentConfig
import dev.promethe.core.KoogLlmAdapter
import dev.promethe.db.PrometheDatabaseApi
import kotlinx.serialization.Serializable

// ══════════════════════════════════════════════════════════════
// Agent-level tools — Hermes parity, wave 2:
//   - SessionSearchTool    → search past sessions (FTS)
//   - MixtureOfAgentsTool  → multi-model consensus
// ══════════════════════════════════════════════════════════════

// ── SessionSearchTool ───────────────────────────────────────

@Serializable
data class SessionSearchArgs(
    @property:LLMDescription("Search query (keywords to find in past sessions).")
    val query: String,
    @property:LLMDescription("Optional: search within a specific session ID.")
    val sessionId: String = "",
    @property:LLMDescription("Maximum results to return. Default: 10.")
    val limit: Int = 10,
)

class SessionSearchTool(
    private val database: PrometheDatabaseApi,
) : SimpleTool<SessionSearchArgs>(
        argsType = typeToken<SessionSearchArgs>(),
        name = "session_search",
        description = "Search past conversation sessions by keyword. Returns matching messages with session IDs, " +
            "timestamps, and content previews.",
    ) {
    override suspend fun execute(args: SessionSearchArgs): String {
        return try {
            val allResults = database.searchMessages(args.query)
            val filtered = if (args.sessionId.isNotBlank()) {
                allResults.filter { it.sessionId == args.sessionId }
            } else {
                allResults
            }
            val limited = filtered.take(args.limit)

            if (limited.isEmpty()) {
                return "No messages found matching '${args.query}'"
            }

            val sb = StringBuilder("Found ${limited.size} matching messages:\n\n")
            limited.forEach { msg ->
                sb.appendLine("• [${msg.sessionId}] (${msg.role}) ${msg.content.take(300)}")
            }
            sb.toString()
        } catch (e: Exception) {
            "[ERROR] Session search failed: ${e.message}"
        }
    }
}

// ── MixtureOfAgentsTool ─────────────────────────────────────

@Serializable
data class MixtureOfAgentsArgs(
    @property:LLMDescription("The question or task to send to multiple models.")
    val prompt: String,
    @property:LLMDescription("Comma-separated model names. Default uses 3 diverse models.")
    val models: String = "",
    @property:LLMDescription("Synthesis strategy: 'best_of', 'merge', or 'vote'. Default: 'best_of'.")
    val strategy: String = "best_of",
)

class MixtureOfAgentsTool(
    private val llmAdapter: KoogLlmAdapter,
    private val config: AgentConfig,
) : SimpleTool<MixtureOfAgentsArgs>(
        argsType = typeToken<MixtureOfAgentsArgs>(),
        name = "mixture_of_agents",
        description = "Get a high-quality answer by consulting multiple AI models and synthesizing their best responses.",
    ) {
    companion object {
        val DEFAULT_MODELS = listOf(
            "nousresearch/hermes-3-llama-3.1-405b",
            "anthropic/claude-3.5-sonnet",
            "openai/gpt-4o",
        )
    }

    override suspend fun execute(args: MixtureOfAgentsArgs): String {
        val models = if (args.models.isNotBlank()) {
            args.models.split(",").map { it.trim() }
        } else {
            DEFAULT_MODELS
        }

        return try {
            // Phase 1: Query all models
            val responses = mutableListOf<Pair<String, String>>()
            for (model in models) {
                try {
                    val response = llmAdapter.complete(
                        systemPrompt = "You are a helpful assistant. Answer concisely and accurately.",
                        messages = listOf("user" to args.prompt),
                        model = model,
                        temperature = 0.3,
                    )
                    responses.add(model to response.content)
                } catch (e: Exception) {
                    responses.add(model to "[ERROR: ${e.message}]")
                }
            }

            // Phase 2: Synthesize
            val synthesis = when (args.strategy) {
                "vote" -> synthesizeVote(responses)
                "merge" -> synthesizeMerge(args.prompt, responses)
                else -> synthesizeBestOf(args.prompt, responses)
            }

            val sb = StringBuilder()
            sb.appendLine("**Synthesis** (${ args.strategy }):")
            sb.appendLine(synthesis)
            sb.appendLine()
            sb.appendLine("**Model responses** (${responses.size}):")
            responses.forEach { (model, resp) ->
                sb.appendLine("• $model: ${resp.take(200)}...")
            }
            sb.toString()
        } catch (e: Exception) {
            "[ERROR] Mixture of agents failed: ${e.message}"
        }
    }

    private suspend fun synthesizeBestOf(
        prompt: String,
        responses: List<Pair<String, String>>,
    ): String {
        val judgePrompt = buildString {
            appendLine("You are a judge. Select the BEST response or improve it:")
            appendLine("Question: $prompt")
            appendLine()
            responses.forEachIndexed { i, (model, resp) ->
                appendLine("--- Response ${i + 1} ($model) ---")
                appendLine(resp.take(2000))
            }
            appendLine("Return only the best answer.")
        }
        return llmAdapter.complete("Expert judge.", listOf("user" to judgePrompt), config.modelName, 0.1).content
    }

    private suspend fun synthesizeMerge(
        prompt: String,
        responses: List<Pair<String, String>>,
    ): String {
        val mergePrompt = buildString {
            appendLine("Merge these responses into one comprehensive answer:")
            appendLine("Question: $prompt")
            responses.forEachIndexed { i, (model, resp) ->
                appendLine("--- ${i + 1} ($model) ---")
                appendLine(resp.take(2000))
            }
        }
        return llmAdapter.complete("Merge expert.", listOf("user" to mergePrompt), config.modelName, 0.2).content
    }

    private fun synthesizeVote(responses: List<Pair<String, String>>): String =
        responses.filter { !it.second.startsWith("[ERROR") }
            .maxByOrNull { it.second.length }
            ?.second ?: "No valid responses."
}
