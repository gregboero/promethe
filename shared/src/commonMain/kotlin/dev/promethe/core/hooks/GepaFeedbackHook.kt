package dev.promethe.core.hooks

import dev.promethe.core.ConversationTrajectory
import dev.promethe.core.evolution.GepaScheduler

/**
 * GepaFeedbackHook — connects the hook system to GEPA self-evolution.
 *
 * On SESSION_END, it captures the session's trajectory and score,
 * then feeds it to [GepaScheduler] for future evolution cycles.
 *
 * This is the integration point that closes the learning loop:
 *   Agent runs → Hook captures trajectory → GEPA analyzes failures → Skills improve
 */
class GepaFeedbackHook(
    private val gepaScheduler: GepaScheduler,
) : Hook {
    override val id = "builtin.gepa_feedback"
    override val events = setOf(HookEvent.SESSION_END)
    override val priority = 80 // Run late, after metrics/logging

    // In-memory trajectory buffer per session
    private val sessionTrajectories = mutableMapOf<String, MutableList<ConversationTrajectory>>()
    private val sessionQueries = mutableMapOf<String, String>()

    /**
     * Called by the agent loop to record trajectory steps during execution.
     * This is invoked externally (not via hook events) to accumulate trajectory data.
     */
    fun recordStep(
        sessionId: String,
        step: ConversationTrajectory,
        originalQuery: String? = null,
    ) {
        sessionTrajectories.getOrPut(sessionId) { mutableListOf() }.add(step)
        if (originalQuery != null) {
            sessionQueries[sessionId] = originalQuery
        }
    }

    override suspend fun execute(context: HookContext): HookResult {
        if (context.event != HookEvent.SESSION_END) return HookResult.Continue

        val sessionId = context.sessionId
        val trajectory = sessionTrajectories.remove(sessionId) ?: return HookResult.Continue
        val query = sessionQueries.remove(sessionId) ?: "(unknown)"

        // Compute a basic score from the trajectory
        val score = computeTrajectoryScore(trajectory, context.error)

        // Detect which skills were involved
        val skillsUsed = trajectory
            .mapNotNull { it.action?.toolName }
            .filter { it.startsWith("skill_") || it.contains("skill") }
            .distinct()

        // Feed to GEPA scheduler
        gepaScheduler.recordTrajectory(
            sessionId = sessionId,
            originalQuery = query,
            trajectory = trajectory,
            score = score,
            feedback = if (context.error != null) "Error: ${context.error.message}" else "",
            skillsUsed = skillsUsed,
        )

        return HookResult.Continue
    }

    /**
     * Simple heuristic scoring:
     * - Base score 0.7
     * - Penalty for errors (-0.3 each, capped)
     * - Bonus for completed responses (+0.2)
     * - Penalty for too many steps (-0.05 per step over 10)
     */
    private fun computeTrajectoryScore(
        trajectory: List<ConversationTrajectory>,
        error: Throwable?,
    ): Double {
        var score = 0.7

        // Error penalty
        if (error != null) score -= 0.3
        val errorCount = trajectory.count { it.observation?.contains("[ERROR]") == true }
        score -= (errorCount * 0.1).coerceAtMost(0.3)

        // Completion bonus
        val hasResponse = trajectory.any { it.outputs.containsKey("response") }
        if (hasResponse) score += 0.2

        // Complexity penalty (too many steps = inefficient)
        val stepCount = trajectory.size
        if (stepCount > 10) score -= ((stepCount - 10) * 0.05).coerceAtMost(0.2)

        return score.coerceIn(0.0, 1.0)
    }
}
