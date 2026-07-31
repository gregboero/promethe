package dev.promethe.core

import dev.promethe.core.Log

/**
 * ResilienceStrategy — implements Hermes-style 3-level failure escalation.
 *
 * Escalation flow:
 *   RETRY (same prompt, up to 3 attempts)
 *     ↓ if still fails
 *   REPLAN (LLM reformulates the approach based on error)
 *     ↓ if still fails
 *   DECOMPOSE (LLM breaks the task into sub-tasks → delegate each)
 *
 * Each level has its own max attempts. If all levels are exhausted,
 * the error is propagated to the user.
 */
class ResilienceStrategy(
    private val llmAdapter: KoogLlmAdapter,
    private val retryAttempts: Int = 3,
    private val replanAttempts: Int = 2,
) {
    private val logger = Log.create("ResilienceStrategy")
    private val inferenceService = LlmInferenceService(llmAdapter)

    enum class EscalationLevel { RETRY, REPLAN, DECOMPOSE, EXHAUSTED }

    data class EscalationState(
        val level: EscalationLevel = EscalationLevel.RETRY,
        val attempt: Int = 0,
        val errors: MutableList<String> = mutableListOf(),
        val originalTask: String = "",
        val currentTask: String = "",
        val decomposedTasks: List<String> = emptyList(),
    )

    /**
     * Determine the next escalation action after a failure.
     * Returns the updated state with the new level/attempt.
     */
    fun escalate(
        state: EscalationState,
        error: String,
    ): EscalationState {
        val updated = state.copy()
        updated.errors.add(error)

        // Config/model errors are permanent — skip all retry levels immediately
        if (isNonRetryableError(error)) {
            logger.warn { "Non-retryable error detected, skipping escalation: $error" }
            return updated.copy(level = EscalationLevel.EXHAUSTED)
        }

        return when (state.level) {
            EscalationLevel.RETRY -> {
                if (state.attempt < retryAttempts) {
                    updated.copy(attempt = state.attempt + 1)
                } else {
                    // Escalate to REPLAN
                    updated.copy(
                        level = EscalationLevel.REPLAN,
                        attempt = 0,
                    )
                }
            }

            EscalationLevel.REPLAN -> {
                if (state.attempt < replanAttempts) {
                    updated.copy(attempt = state.attempt + 1)
                } else {
                    // Escalate to DECOMPOSE
                    updated.copy(
                        level = EscalationLevel.DECOMPOSE,
                        attempt = 0,
                    )
                }
            }

            EscalationLevel.DECOMPOSE -> {
                // DECOMPOSE is a one-shot — if it fails, we're done
                updated.copy(level = EscalationLevel.EXHAUSTED)
            }

            EscalationLevel.EXHAUSTED -> {
                updated
            }
        }
    }

    /**
     * Returns true for errors that are permanent configuration issues:
     * unknown model, missing params, auth failures.
     * These should NOT be retried — go straight to EXHAUSTED.
     */
    private fun isNonRetryableError(error: String): Boolean {
        val msg = error.lowercase()
        return "cannot determine" in msg ||
            "not initialized" in msg ||
            "invalid api" in msg ||
            "401" in msg ||
            "403" in msg
    }

    /**
     * Generate a replanned prompt by asking the LLM to reformulate the task
     * based on the failures encountered.
     */
    suspend fun replan(
        originalTask: String,
        errors: List<String>,
        history: List<dev.promethe.db.MessageRow>,
    ): String {
        val errorSummary = errors.takeLast(3).joinToString("\n") { "- $it" }

        val replanContent = """A task has failed multiple times.

ORIGINAL TASK:
$originalTask

ERRORS ENCOUNTERED:
$errorSummary

Analyze why the task failed and provide a REFORMULATED version of the task that avoids the same pitfalls.
Return ONLY the reformulated task description, nothing else. Be specific and actionable."""

        return try {
            val pairs = history.map { it.role to it.content }
            inferenceService.complete(
                systemInstruction =
                    "You are a meta-planner. Reformulate failed tasks. " +
                        "Return only a specific and actionable task description.",
                messages = pairs + ("user" to replanContent),
            ).content
        } catch (e: Exception) {
            logger.error(e) { "Replan LLM call failed, falling back to original task" }
            originalTask
        }
    }

    /**
     * Decompose a complex task into smaller sub-tasks.
     * Returns a list of task descriptions that can be delegated individually.
     */
    suspend fun decompose(
        originalTask: String,
        errors: List<String>,
    ): List<String> {
        val errorSummary = errors.takeLast(3).joinToString("\n") { "- $it" }

        val decomposeContent = """A complex task has failed even after replanning.

ORIGINAL TASK:
$originalTask

ERRORS ENCOUNTERED:
$errorSummary

Break this task into 2-5 smaller, independent sub-tasks that each have a higher chance of succeeding.
Return ONLY a JSON array of strings, each being a sub-task description.
Example: ["Sub-task 1 description", "Sub-task 2 description"]"""

        return try {
            val response =
                inferenceService.complete(
                    systemInstruction =
                        "You are a task decomposition specialist. Return only a JSON array of task strings.",
                    messages = listOf("user" to decomposeContent),
                ).content
            parseDecomposedTasks(response)
        } catch (e: Exception) {
            // Fallback: return original as single task
            logger.error(e) { "Task decomposition LLM call failed" }
            listOf(originalTask)
        }
    }

    private fun parseDecomposedTasks(response: String): List<String> {
        // Extract JSON array from response
        val jsonStart = response.indexOf('[')
        val jsonEnd = response.lastIndexOf(']')
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            return listOf(response)
        }
        val jsonStr = response.substring(jsonStart, jsonEnd + 1)
        return try {
            kotlinx.serialization.json.Json
                .decodeFromString<List<String>>(jsonStr)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse decomposed tasks JSON" }
            listOf(response)
        }
    }

    /**
     * Build a human-readable summary of the escalation state for logging/UI.
     */
    fun describe(state: EscalationState): String =
        when (state.level) {
            EscalationLevel.RETRY -> "⟳ Retry ${state.attempt}/$retryAttempts"
            EscalationLevel.REPLAN -> "🔄 Replan ${state.attempt}/$replanAttempts"
            EscalationLevel.DECOMPOSE -> "🔀 Decompose into ${state.decomposedTasks.size} sub-tasks"
            EscalationLevel.EXHAUSTED -> "❌ All escalation levels exhausted (${state.errors.size} errors)"
        }
}
