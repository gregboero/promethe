package dev.promethe.core.evolution

import dev.promethe.api.SkillContract
import dev.promethe.api.SkillLifecycle
import dev.promethe.core.Log

import dev.promethe.core.AgentConfig
import dev.promethe.core.ConversationTrajectory
import dev.promethe.core.KoogLlmAdapter
import dev.promethe.core.SkillEntry
import dev.promethe.core.SkillLoader
import dev.promethe.core.SkillWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * GEPA — Genetic-Pareto Prompt Evolution for Prométhé.
 *
 * Implements the closed learning loop:
 * 1. **Collect** — gather execution trajectories + evaluation scores
 * 2. **Reflect** — use an LLM to analyze failures and propose targeted mutations
 * 3. **Mutate** — generate candidate variants of prompts/skills
 * 4. **Evaluate** — score candidates on multiple objectives (accuracy, latency, cost)
 * 5. **Select** — keep Pareto-optimal candidates, discard the rest
 * 6. **Integrate** — save improved skills back to the skill library
 *
 * Inspired by NousResearch/hermes-agent-self-evolution.
 */
class GepaEvolver(
    private val llmAdapter: KoogLlmAdapter,
    private val config: AgentConfig,
    private val skillLoader: SkillLoader,
    private val skillWriter: SkillWriter,
    private val populationSize: Int = 4,
    private val maxGenerations: Int = 3,
) {
    private val logger = Log.create("GepaEvolver")
    // ── Data Types ──────────────────────────────────────────

    data class Candidate(
        val id: String,
        val content: String,
        val scores: MutableMap<String, Double> = mutableMapOf(),
        val generation: Int = 0,
        val parentId: String? = null,
        val mutationType: String = "seed",
    )

    data class EvolutionResult(
        val skillName: String,
        val originalContent: String,
        val bestCandidate: Candidate,
        val generations: Int,
        val totalCandidatesEvaluated: Int,
        val paretoFront: List<Candidate>,
        val improvementDelta: Map<String, Double>,
    )

    data class TrajectoryContext(
        val trajectory: List<ConversationTrajectory>,
        val originalQuery: String,
        val score: Double,
        val feedback: String = "",
    )

    // ── Main Evolution Loop ─────────────────────────────────

    /**
     * Run GEPA optimization on a single skill.
     *
     * @param skillName The skill file name to optimize
     * @param failedTrajectories Recent trajectories where this skill performed poorly
     * @return EvolutionResult with the best candidate, or null if no improvement found
     */
    suspend fun evolve(
        skillName: String,
        failedTrajectories: List<TrajectoryContext>,
    ): EvolutionResult? {
        val skillEntry = skillLoader.listSkills().find { it.name == skillName } ?: run {
            logger.warn { "Skill '$skillName' not found, skipping evolution" }
            return null
        }

        val originalContent = skillEntry.content
        logger.info { "Starting evolution for skill '$skillName' (pop=$populationSize, gens=$maxGenerations)" }

        // Seed population: original + reflective mutations
        var population = seedPopulation(originalContent, failedTrajectories)

        // Baseline: original gets 0.5 on all criteria (it is identical to itself)
        val originalCandidate = Candidate("original", originalContent)
        val originalScores = mapOf("completeness" to 0.5, "clarity" to 0.5, "robustness" to 0.5)

        var totalEvaluated = population.size

        // Evolution loop
        for (gen in 1..maxGenerations) {
            logger.info { "Generation $gen — ${population.size} candidates" }

            // Evaluate all candidates pairwise against the original in parallel
            population = coroutineScope {
                population.map { candidate ->
                    async {
                        if (candidate.scores.isEmpty()) {
                            val scores = evaluatePairwise(candidate, originalCandidate, failedTrajectories)
                            candidate.copy(scores = scores.toMutableMap())
                        } else {
                            candidate
                        }
                    }
                }.awaitAll()
            }

            // Pareto selection
            val paretoFront = selectParetoFront(population)
            logger.info { "Pareto front: ${paretoFront.size} candidates" }

            if (gen < maxGenerations) {
                // Generate next generation via mutation + crossover
                val offspring = mutableListOf<Candidate>()
                for (parent in paretoFront.take(2)) {
                    val mutations = generateMutations(parent, failedTrajectories, gen)
                    offspring.addAll(mutations)
                }
                totalEvaluated += offspring.size
                population = paretoFront + offspring
            } else {
                population = paretoFront
            }
        }

        // Select the best candidate
        val best = population.maxByOrNull { it.scores.values.average() } ?: return null
        val bestAvg = best.scores.values.average()
        val originalAvg = originalScores.values.average()

        if (bestAvg <= originalAvg) {
            logger.info { "No improvement found (original=$originalAvg, best=$bestAvg). Keeping original" }
            return null
        }

        val delta = best.scores.mapValues { (key, value) ->
            value - (originalScores[key] ?: 0.0)
        }

        logger.info { "Evolution complete! Improvement: $delta" }

        return EvolutionResult(
            skillName = skillName,
            originalContent = originalContent,
            bestCandidate = best,
            generations = maxGenerations,
            totalCandidatesEvaluated = totalEvaluated,
            paretoFront = selectParetoFront(population),
            improvementDelta = delta,
        )
    }

    /**
     * Apply an evolution result: save the improved skill.
     */
    suspend fun apply(result: EvolutionResult) {
        val existing = skillLoader.listSkills().find { skill -> skill.name == result.skillName }
        val candidate =
            existing?.copy(
                content = result.bestCandidate.content,
                contract =
                    existing.contract.copy(
                        lifecycle = SkillLifecycle.CANDIDATE,
                        contentHash = null,
                    ),
            ) ?: SkillEntry(
                name = result.skillName,
                content = result.bestCandidate.content,
                contract =
                    SkillContract(
                        lifecycle = SkillLifecycle.CANDIDATE,
                        provenance = "gepa",
                    ),
            )
        if (existing == null) {
            skillWriter.write(candidate)
        } else {
            skillWriter.update(candidate)
        }
        skillLoader.invalidateCache()
        logger.info { "Applied evolved skill '${result.skillName}' (delta: ${result.improvementDelta})" }
    }

    // ── Seed Population ─────────────────────────────────────

    private suspend fun seedPopulation(
        originalContent: String,
        contexts: List<TrajectoryContext>,
    ): List<Candidate> {
        val candidates = mutableListOf(
            Candidate(id = "seed_0", content = originalContent, mutationType = "original"),
        )

        // Generate reflective mutations from failure analysis
        for (i in 1 until populationSize) {
            val mutated = reflectAndMutate(originalContent, contexts, generation = 0, index = i)
            if (mutated != null) {
                candidates.add(mutated)
            }
        }

        return candidates
    }

    // ── Reflective Mutation ─────────────────────────────────

    /**
     * Core GEPA mechanism: use an LLM to analyze WHY a trajectory failed,
     * then produce a targeted edit to the skill content.
     */
    private suspend fun reflectAndMutate(
        parentContent: String,
        contexts: List<TrajectoryContext>,
        generation: Int,
        index: Int,
    ): Candidate? {
        val failureSummary = contexts
            .filter { it.score < 0.6 }
            .take(3)
            .joinToString("\n---\n") { ctx ->
                buildString {
                    appendLine("Query: ${ctx.originalQuery}")
                    appendLine("Score: ${ctx.score}")
                    appendLine("Feedback: ${ctx.feedback}")
                    appendLine("Trajectory (last 3 steps):")
                    ctx.trajectory.takeLast(3).forEach { step ->
                        step.thought?.let { appendLine("  Thought: $it") }
                        step.action?.let { appendLine("  Action: ${it.toolName}(${it.args})") }
                        step.observation?.let { appendLine("  Observation: ${it.take(200)}") }
                    }
                }
            }

        if (failureSummary.isBlank()) return null

        val mutationPrompt =
            """
            You are a skill optimizer. Analyze the following failures and improve the skill.
            
            ## Current Skill Content
            ```
            $parentContent
            ```
            
            ## Failure Analysis
            $failureSummary
            
            ## Instructions
            1. Identify the root cause of each failure
            2. Produce an IMPROVED version of the skill that addresses these failures
            3. Keep the same structure (# Skill:, ## Objective, ## Steps, etc.)
            4. Make targeted, minimal changes — don't rewrite everything
            5. Add guardrails or fallback steps where failures occurred
            
            Output ONLY the improved skill content (Markdown), nothing else.
            """.trimIndent()

        return try {
            val response = llmAdapter.complete(
                systemPrompt = "You are a GEPA reflective mutator. You analyze agent failures and produce improved skill definitions.",
                messages = listOf("user" to mutationPrompt),
                model = config.modelName,
                temperature = 0.4 + (index * 0.15).coerceAtMost(0.4), // Higher diversity for later candidates
            )
            Candidate(
                id = "gen${generation}_mut$index",
                content = response.content,
                generation = generation,
                parentId = "seed_0",
                mutationType = "reflective_mutation",
            )
        } catch (e: Exception) {
            logger.warn(e) { "Mutation failed" }
            null
        }
    }

    private suspend fun generateMutations(
        parent: Candidate,
        contexts: List<TrajectoryContext>,
        generation: Int,
    ): List<Candidate> {
        val mutations = mutableListOf<Candidate>()
        for (i in 0 until 2) {
            val mutated = reflectAndMutate(parent.content, contexts, generation, i)
            if (mutated != null) {
                mutations.add(mutated.copy(parentId = parent.id))
            }
        }
        return mutations
    }

    // ── Pairwise LLM-as-Judge Evaluation ─────────────────────

    /**
     * Evaluate a candidate against the original using pairwise comparison.
     *
     * To mitigate position bias, runs two comparisons with swapped order
     * and averages the results. Returns win-rate scores:
     * 1.0 = candidate is better, 0.5 = same, 0.0 = original is better.
     */
    private suspend fun evaluatePairwise(
        candidate: Candidate,
        originalCandidate: Candidate,
        contexts: List<TrajectoryContext>,
    ): Map<String, Double> {
        val contextLines = contexts.take(3).joinToString("\n") { "- ${it.originalQuery}" }

        fun buildPrompt(
            contentA: String,
            contentB: String,
        ): String =
            """
            You are evaluating two versions of an AI skill definition.
            
            ## Context
            This skill is used for queries like:
            $contextLines
            
            ## Version A
            ```
            ${contentA.take(3000)}
            ```
            
            ## Version B
            ```
            ${contentB.take(3000)}
            ```
            
            ## Instructions
            For each criterion, think step by step about which version is better, then give your verdict.
            
            Criteria:
            1. completeness - Does it cover all necessary steps and edge cases?
            2. clarity - Are the instructions clear and unambiguous?
            3. robustness - Does it handle errors and fallback scenarios?
            
            For EACH criterion, respond in this format:
            completeness: <your reasoning> → A_BETTER | SAME | B_BETTER
            clarity: <your reasoning> → A_BETTER | SAME | B_BETTER
            robustness: <your reasoning> → A_BETTER | SAME | B_BETTER
            """.trimIndent()

        return try {
            // Pass 1: candidate = A, original = B
            val promptAB = buildPrompt(candidate.content, originalCandidate.content)
            val responseAB = llmAdapter.complete(
                systemPrompt = "You are a pairwise skill evaluation judge. Compare two skill versions using chain-of-thought reasoning, then give a verdict per criterion.",
                messages = listOf("user" to promptAB),
                model = config.modelName,
                temperature = 0.0,
            )
            val scoresAB = parsePairwiseVerdicts(responseAB.content, candidateIsA = true)

            // Pass 2: original = A, candidate = B (swapped to mitigate position bias)
            val promptBA = buildPrompt(originalCandidate.content, candidate.content)
            val responseBA = llmAdapter.complete(
                systemPrompt = "You are a pairwise skill evaluation judge. Compare two skill versions using chain-of-thought reasoning, then give a verdict per criterion.",
                messages = listOf("user" to promptBA),
                model = config.modelName,
                temperature = 0.0,
            )
            val scoresBA = parsePairwiseVerdicts(responseBA.content, candidateIsA = false)

            // Average both passes for position-debiased scores
            val criteria = listOf("completeness", "clarity", "robustness")
            criteria.associateWith { key ->
                ((scoresAB[key] ?: 0.5) + (scoresBA[key] ?: 0.5)) / 2.0
            }
        } catch (e: Exception) {
            logger.warn(e) { "Pairwise evaluation failed" }
            mapOf("completeness" to 0.5, "clarity" to 0.5, "robustness" to 0.5)
        }
    }

    /**
     * Parse pairwise verdicts from judge output.
     *
     * @param text Raw LLM response containing lines like "completeness: ... → A_BETTER"
     * @param candidateIsA true if the candidate was placed in position A, false if in position B
     * @return Scores mapped so 1.0 = candidate wins, 0.5 = tie, 0.0 = original wins
     */
    private fun parsePairwiseVerdicts(
        text: String,
        candidateIsA: Boolean,
    ): Map<String, Double> {
        val scores = mutableMapOf<String, Double>()
        val pattern = Regex("""(\w+):.*→\s*(A_BETTER|B_BETTER|SAME)""")
        for (match in pattern.findAll(text)) {
            val key = match.groupValues[1].lowercase()
            val verdict = match.groupValues[2]
            val score = when {
                verdict == "SAME" -> 0.5
                (verdict == "A_BETTER" && candidateIsA) || (verdict == "B_BETTER" && !candidateIsA) -> 1.0
                else -> 0.0
            }
            scores[key] = score
        }
        if ("completeness" !in scores) scores["completeness"] = 0.5
        if ("clarity" !in scores) scores["clarity"] = 0.5
        if ("robustness" !in scores) scores["robustness"] = 0.5
        return scores
    }

    // ── Pareto Selection ────────────────────────────────────

    /**
     * Select the Pareto front: candidates that are not dominated by any other.
     * A candidate A dominates B if A is >= B on all objectives and > on at least one.
     */
    private fun selectParetoFront(candidates: List<Candidate>): List<Candidate> {
        if (candidates.size <= 1) return candidates

        val objectives = listOf("completeness", "clarity", "robustness")
        return candidates.filter { candidate ->
            candidates.none { other ->
                other !== candidate && dominates(other, candidate, objectives)
            }
        }
    }

    private fun dominates(
        a: Candidate,
        b: Candidate,
        objectives: List<String>,
    ): Boolean {
        var allGe = true
        var anyGt = false
        for (obj in objectives) {
            val aScore = a.scores[obj] ?: 0.0
            val bScore = b.scores[obj] ?: 0.0
            if (aScore < bScore) allGe = false
            if (aScore > bScore) anyGt = true
        }
        return allGe && anyGt
    }
}
