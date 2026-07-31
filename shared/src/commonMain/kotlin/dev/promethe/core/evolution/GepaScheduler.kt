package dev.promethe.core.evolution

import dev.promethe.core.Log

import dev.promethe.core.AgentConfig
import dev.promethe.core.ConversationTrajectory
import dev.promethe.core.KoogLlmAdapter
import dev.promethe.core.SkillLoader
import dev.promethe.core.SkillWriter
import dev.promethe.db.PrometheDatabaseApi
import kotlinx.coroutines.*

/**
 * GepaScheduler — orchestrates periodic self-evolution cycles.
 *
 * Responsibilities:
 * 1. Collect failed trajectories from the database
 * 2. Group failures by skill
 * 3. Run GEPA evolution for underperforming skills
 * 4. Log evolution results and optionally auto-apply improvements
 *
 * Can be run as:
 * - A scheduled background task (via TaskScheduler cron)
 * - A manual one-shot optimization (via API call)
 * - A post-session hook (after each conversation ends)
 */
class GepaScheduler(
    private val database: PrometheDatabaseApi,
    private val llmAdapter: KoogLlmAdapter,
    private val config: AgentConfig,
    private val skillLoader: SkillLoader,
    private val skillWriter: SkillWriter,
    private val autoApply: Boolean = false,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    private val logger = Log.create("GepaScheduler")
    private var job: Job? = null

    // ── Trajectory Buffer ────────────────────────────────────

    data class TrackedTrajectory(
        val sessionId: String,
        val originalQuery: String,
        val trajectory: List<ConversationTrajectory>,
        val score: Double,
        val feedback: String,
        val skillsUsed: List<String>,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val recentTrajectories = mutableListOf<TrackedTrajectory>()
    private val evolutionHistory = mutableListOf<GepaEvolver.EvolutionResult>()

    // ── Public API ──────────────────────────────────────────

    /**
     * Record a completed trajectory for future evolution analysis.
     */
    fun recordTrajectory(
        sessionId: String,
        originalQuery: String,
        trajectory: List<ConversationTrajectory>,
        score: Double,
        feedback: String = "",
        skillsUsed: List<String> = emptyList(),
    ) {
        synchronized(recentTrajectories) {
            recentTrajectories.add(
                TrackedTrajectory(
                    sessionId = sessionId,
                    originalQuery = originalQuery,
                    trajectory = trajectory,
                    score = score,
                    feedback = feedback,
                    skillsUsed = skillsUsed,
                ),
            )
            // Keep only last 100 trajectories in memory
            if (recentTrajectories.size > 100) {
                recentTrajectories.removeAt(0)
            }
        }
    }

    /**
     * Run a single evolution cycle: analyze recent failures and evolve underperforming skills.
     */
    suspend fun runEvolutionCycle(): List<GepaEvolver.EvolutionResult> {
        val failures = synchronized(recentTrajectories) {
            recentTrajectories.filter { it.score < 0.6 }.toList()
        }

        if (failures.isEmpty()) {
            logger.info { "No failures to analyze. Skipping evolution cycle" }
            return emptyList()
        }

        logger.info { "Starting evolution cycle with ${failures.size} failed trajectories" }

        // Group failures by skill
        val failuresBySkill = mutableMapOf<String, MutableList<TrackedTrajectory>>()
        for (failure in failures) {
            for (skill in failure.skillsUsed) {
                failuresBySkill.getOrPut(skill) { mutableListOf() }.add(failure)
            }
        }

        // If no skills identified, try evolving the system prompt itself
        if (failuresBySkill.isEmpty()) {
            failuresBySkill["_system_prompt"] = failures.toMutableList()
        }

        val evolver = GepaEvolver(
            llmAdapter = llmAdapter,
            config = config,
            skillLoader = skillLoader,
            skillWriter = skillWriter,
        )

        val results = mutableListOf<GepaEvolver.EvolutionResult>()

        for ((skillName, skillFailures) in failuresBySkill) {
            logger.info { "Evolving skill '$skillName' (${skillFailures.size} failures)" }

            val contexts = skillFailures.map { tracked ->
                GepaEvolver.TrajectoryContext(
                    trajectory = tracked.trajectory,
                    originalQuery = tracked.originalQuery,
                    score = tracked.score,
                    feedback = tracked.feedback,
                )
            }

            val result = evolver.evolve(skillName, contexts)
            if (result != null) {
                results.add(result)
                evolutionHistory.add(result)

                if (autoApply) {
                    evolver.apply(result)
                    logger.info { "Auto-applied evolution for '$skillName'" }
                } else {
                    logger.info {
                        "Evolution complete for '$skillName'. " +
                            "Improvement: ${result.improvementDelta}. " +
                            "Call apply() to integrate."
                    }
                }
            }
        }

        // Clear processed failures
        synchronized(recentTrajectories) {
            recentTrajectories.removeAll { it.score < 0.6 }
        }

        logger.info { "Evolution cycle complete. ${results.size} skills improved" }
        return results
    }

    // ── Background Scheduling ───────────────────────────────

    /**
     * Start periodic evolution cycles (runs every [intervalMinutes]).
     */
    fun startPeriodicEvolution(intervalMinutes: Long = 60) {
        if (job != null) return
        logger.info { "Starting periodic evolution (every ${intervalMinutes}min)" }

        job = scope.launch {
            while (isActive) {
                delay(intervalMinutes * 60_000)
                try {
                    runEvolutionCycle()
                } catch (e: Exception) {
                    logger.error(e) { "Evolution cycle failed" }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        logger.info { "Stopped" }
    }

    // ── Status & History ────────────────────────────────────

    fun getStatus(): Map<String, Any> =
        mapOf(
            "isRunning" to (job?.isActive == true),
            "pendingTrajectories" to recentTrajectories.size,
            "failedTrajectories" to recentTrajectories.count { it.score < 0.6 },
            "evolutionsCompleted" to evolutionHistory.size,
            "lastEvolution" to (
                evolutionHistory.lastOrNull()?.let {
                    mapOf(
                        "skill" to it.skillName,
                        "improvement" to it.improvementDelta,
                        "candidatesEvaluated" to it.totalCandidatesEvaluated,
                    )
                } ?: "none"
            ),
        )

    fun getEvolutionHistory(): List<GepaEvolver.EvolutionResult> = evolutionHistory.toList()
}
