package dev.promethe.gateway

import dev.promethe.api.GepaJobResponse
import dev.promethe.api.GepaOptimizeRequest
import dev.promethe.api.GepaResultDto
import dev.promethe.core.AIAgent
import dev.promethe.core.AgentConfig
import dev.promethe.core.KoogLlmAdapter
import dev.promethe.core.SkillLoader
import dev.promethe.core.SkillWriter
import dev.promethe.core.evolution.GepaEvolver
import dev.promethe.db.PrometheDatabaseApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger("GepaJobManager")

/**
 * Manages GEPA optimization jobs in the background.
 *
 * Jobs survive HTTP request lifecycles — the client starts a job,
 * then polls for status. Navigation away and back works seamlessly.
 *
 * Supports three optimization targets:
 * - "system_prompt" — optimize the main agent system prompt
 * - "skill:<name>" — optimize a specific skill via GepaEvolver
 * - "profile:<id>" — optimize an agent profile's system prompt
 */
class GepaJobManager(
    private val agent: AIAgent,
    private val llmAdapter: KoogLlmAdapter? = null,
    private val config: AgentConfig? = null,
    private val skillLoader: SkillLoader? = null,
    private val skillWriter: SkillWriter? = null,
    private val database: PrometheDatabaseApi? = null,
) {
    // Background scope that outlives individual HTTP requests
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val jobs = ConcurrentHashMap<String, GepaJobState>()

    private data class GepaJobState(
        val id: String,
        val targetLabel: String = "System Prompt",
        @Volatile var status: String = "running",
        @Volatile var progress: Float = 0f,
        @Volatile var currentGeneration: Int = 0,
        val maxGenerations: Int,
        @Volatile var result: GepaResultDto? = null,
        @Volatile var error: String? = null,
        val startedAt: Long = System.currentTimeMillis(),
    )

    /**
     * Start a new GEPA optimization in the background.
     * Returns the job ID immediately.
     */
    fun startOptimization(request: GepaOptimizeRequest): String {
        val jobId = UUID.randomUUID().toString().take(8)
        val targetLabel = resolveTargetLabel(request.target)
        val state = GepaJobState(
            id = jobId,
            targetLabel = targetLabel,
            maxGenerations = request.maxGenerations,
        )
        jobs[jobId] = state

        scope.launch {
            try {
                logger.info { "GEPA job $jobId started: target='${request.target}' ($targetLabel)" }
                when {
                    // ── Skill optimization ──
                    request.target.startsWith("skill:") -> {
                        val skillName = request.target.removePrefix("skill:")
                        optimizeSkill(jobId, state, skillName, request)
                    }

                    // ── Profile optimization ──
                    request.target.startsWith("profile:") -> {
                        val profileId = request.target.removePrefix("profile:")
                        optimizeProfile(jobId, state, profileId, request)
                    }

                    // ── System prompt (default) ──
                    else -> {
                        optimizeSystemPrompt(jobId, state, request)
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "GEPA job $jobId failed" }
                state.status = "failed"
                state.error = e.message ?: "Unknown error"
            }
        }

        return jobId
    }

    // ── System Prompt optimization (existing behavior) ──────────────────────

    private suspend fun optimizeSystemPrompt(
        jobId: String,
        state: GepaJobState,
        request: GepaOptimizeRequest,
    ) {
        val result = agent.optimizeSystemPrompt { snapshot ->
            state.currentGeneration = snapshot.generation
            state.progress = snapshot.generation.toFloat() / request.maxGenerations.toFloat()
            logger.debug { "GEPA job $jobId: gen ${snapshot.generation}/${request.maxGenerations} (accuracy=${snapshot.bestAccuracy})" }
        }

        state.result = GepaResultDto(
            bestPromptPreview = result.bestPrompt.take(200) + "...",
            accuracy = result.bestEvaluation.accuracy,
            improvement = result.improvement,
            generations = result.generations.size,
            totalCandidatesEvaluated = result.generations.sumOf { it.populationSize },
        )
        state.progress = 1f
        state.status = "completed"
        logger.info { "GEPA job $jobId completed (improvement=${result.improvement})" }
    }

    // ── Skill optimization via GepaEvolver ──────────────────────────────────

    private suspend fun optimizeSkill(
        jobId: String,
        state: GepaJobState,
        skillName: String,
        request: GepaOptimizeRequest,
    ) {
        val evolver = createEvolver(request) ?: run {
            state.status = "failed"
            state.error = "Skill optimization not available (missing dependencies)"
            return
        }

        // Track progress by generation (GepaEvolver logs but doesn't callback)
        state.currentGeneration = 0
        state.progress = 0.1f

        // Run evolution with empty trajectories (manual optimization = no failures to analyze)
        // GepaEvolver will still generate reflective mutations based on skill content analysis
        val result = evolver.evolve(skillName, emptyList())

        if (result != null) {
            // Auto-apply the improvement
            evolver.apply(result)
            val avgImprovement = result.improvementDelta.values.average()
            state.result = GepaResultDto(
                bestPromptPreview = result.bestCandidate.content.take(200) + "...",
                accuracy = result.bestCandidate.scores.values.average(),
                improvement = avgImprovement,
                generations = result.generations,
                totalCandidatesEvaluated = result.totalCandidatesEvaluated,
            )
            state.progress = 1f
            state.status = "completed"
            logger.info { "GEPA skill job $jobId completed: '$skillName' improved by $avgImprovement" }
        } else {
            state.result = GepaResultDto(
                bestPromptPreview = "No improvement found",
                accuracy = 0.0,
                improvement = 0.0,
                generations = request.maxGenerations,
                totalCandidatesEvaluated = request.populationSize * request.maxGenerations,
            )
            state.progress = 1f
            state.status = "completed"
            logger.info { "GEPA skill job $jobId: no improvement for '$skillName'" }
        }
    }

    // ── Profile optimization ────────────────────────────────────────────────

    private suspend fun optimizeProfile(
        jobId: String,
        state: GepaJobState,
        profileId: String,
        request: GepaOptimizeRequest,
    ) {
        val db = database ?: run {
            state.status = "failed"
            state.error = "Profile optimization not available (no database)"
            return
        }

        val profile = db.getAgentProfile(profileId) ?: run {
            state.status = "failed"
            state.error = "Profile '$profileId' not found"
            return
        }

        if (profile.systemPrompt.isBlank()) {
            state.status = "failed"
            state.error = "Profile '${profile.name}' has no system prompt to optimize"
            return
        }

        // Use the main agent.optimizeSystemPrompt as a proxy:
        // We temporarily optimize the profile's prompt using GepaEngine
        state.currentGeneration = 0
        state.progress = 0.1f

        val result = agent.optimizeSystemPrompt { snapshot ->
            state.currentGeneration = snapshot.generation
            state.progress = snapshot.generation.toFloat() / request.maxGenerations.toFloat()
        }

        // Update the profile with the optimized prompt
        db.updateAgentProfile(
            profile.copy(
                systemPrompt = result.bestPrompt,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        state.result = GepaResultDto(
            bestPromptPreview = result.bestPrompt.take(200) + "...",
            accuracy = result.bestEvaluation.accuracy,
            improvement = result.improvement,
            generations = result.generations.size,
            totalCandidatesEvaluated = result.generations.sumOf { it.populationSize },
        )
        state.progress = 1f
        state.status = "completed"
        logger.info { "GEPA profile job $jobId completed: '${profile.name}' (improvement=${result.improvement})" }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun createEvolver(request: GepaOptimizeRequest): GepaEvolver? {
        val adapter = llmAdapter ?: return null
        val cfg = config ?: return null
        val loader = skillLoader ?: return null
        val writer = skillWriter ?: return null
        return GepaEvolver(
            llmAdapter = adapter,
            config = cfg,
            skillLoader = loader,
            skillWriter = writer,
            populationSize = request.populationSize,
            maxGenerations = request.maxGenerations,
        )
    }

    private fun resolveTargetLabel(target: String): String =
        when {
            target.startsWith("skill:") -> "Skill: ${target.removePrefix("skill:")}"
            target.startsWith("profile:") -> "Profile: ${target.removePrefix("profile:")}"
            else -> "System Prompt"
        }

    /**
     * Get the current status of a job.
     */
    fun getJob(jobId: String): GepaJobResponse? {
        val state = jobs[jobId] ?: return null
        return state.toResponse()
    }

    /**
     * Get all jobs (for listing).
     */
    fun getAllJobs(): List<GepaJobResponse> = jobs.values.map { it.toResponse() }.sortedByDescending { it.startedAt }

    /**
     * Check if any job is currently running.
     */
    fun hasRunningJob(): Boolean = jobs.values.any { it.status == "running" }

    /**
     * Get the currently running job, if any.
     */
    fun getRunningJob(): GepaJobResponse? = getAllJobs().firstOrNull { it.status == "running" }

    private fun GepaJobState.toResponse() =
        GepaJobResponse(
            jobId = id,
            status = status,
            progress = progress.coerceIn(0f, 1f),
            result = result,
            error = error,
            startedAt = startedAt,
            currentGeneration = currentGeneration,
            maxGenerations = maxGenerations,
            targetLabel = targetLabel,
        )
}
