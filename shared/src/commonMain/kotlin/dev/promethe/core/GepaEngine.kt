package dev.promethe.core

import dev.promethe.core.gepa.*
import kotlinx.coroutines.*

class GepaEngine(
    private val llmAdapter: KoogLlmAdapter,
    private val config: AgentConfig,
    private val gepaConfig: GepaConfig = GepaConfig(),
) {
    private val mutations = GepaMutations(llmAdapter, config)
    private val evaluator = GepaEvaluator(llmAdapter, config)
    private val selector = ParetoSelector()

    suspend fun optimize(
        originalPrompt: String,
        testCases: List<GepaTestCase>,
        onProgress: (suspend (GenerationSnapshot) -> Unit)? = null,
    ): GepaResult =
        coroutineScope {
            require(testCases.isNotEmpty()) { "At least one test case required" }

            Tracing.span("gepa.optimize") {
                setAttribute("gepa.population_size", gepaConfig.populationSize)
                setAttribute("gepa.test_cases", testCases.size)
                setAttribute("gepa.max_generations", gepaConfig.maxGenerations)

                var population = mutations.seedPopulation(originalPrompt, gepaConfig.populationSize)
                val history = mutableListOf<GenerationSnapshot>()
                var bestEval: CandidateEvaluation? = null

                for (gen in 1..gepaConfig.maxGenerations) {
                    // Évaluation parallèle
                    val evals = population.map { c -> async { evaluator.evaluate(c, testCases) } }.awaitAll()

                    // Pareto
                    val fronts = selector.computeParetoFronts(evals)
                    val currentBest = fronts.firstOrNull()?.maxByOrNull { it.accuracy }

                    val snapshot = GenerationSnapshot(
                        generation = gen,
                        populationSize = population.size,
                        frontSizes = fronts.map { it.size },
                        bestAccuracy = currentBest?.accuracy ?: 0.0,
                        bestTokenCount = currentBest?.tokenCount ?: 0,
                        avgAccuracy = evals.map { it.accuracy }.average(),
                    )
                    history.add(snapshot)
                    onProgress?.invoke(snapshot)

                    // Convergence check
                    if (bestEval != null && currentBest != null && gen > 2) {
                        if (currentBest.accuracy - bestEval.accuracy < gepaConfig.convergenceThreshold) {
                            bestEval = currentBest
                            break
                        }
                    }
                    bestEval = currentBest ?: bestEval

                    // Reproduction (sauf dernière génération)
                    if (gen < gepaConfig.maxGenerations) {
                        population = reproduce(population, evals, fronts, gen + 1)
                    }
                }

                val finalBest = bestEval ?: error("No evaluation results")
                val bestCandidate = population.find { it.id == finalBest.candidateId } ?: population.first()

                setAttribute("gepa.best_accuracy", finalBest.accuracy)
                setAttribute("gepa.improvement", finalBest.accuracy - (history.firstOrNull()?.bestAccuracy ?: 0.0))

                GepaResult(
                    bestPrompt = bestCandidate.promptText,
                    bestCandidate = bestCandidate,
                    bestEvaluation = finalBest,
                    generations = history,
                    originalPrompt = originalPrompt,
                    improvement = finalBest.accuracy - (history.firstOrNull()?.bestAccuracy ?: 0.0),
                )
            }
        }

    private fun reproduce(
        pop: List<PromptCandidate>,
        evals: List<CandidateEvaluation>,
        fronts: List<List<CandidateEvaluation>>,
        nextGen: Int,
    ): List<PromptCandidate> {
        val next = mutableListOf<PromptCandidate>()
        val map = pop.associateBy { it.id }

        // Élitisme : garder les meilleurs du front 0
        selector.selectElites(fronts, gepaConfig.eliteCount).forEach { e ->
            map[e.candidateId]?.let { next.add(it.copy(generation = nextGen)) }
        }

        // Remplir la population par crossover, mutation ou copie
        var attempts = 0
        val maxAttempts = gepaConfig.populationSize * 10
        while (next.size < gepaConfig.populationSize && attempts < maxAttempts) {
            attempts++
            val roll = kotlin.random.Random.nextDouble()
            when {
                roll < gepaConfig.crossoverRate -> {
                    val p1 = map[selector.tournamentSelect(evals, fronts, gepaConfig.tournamentSize).candidateId] ?: continue
                    val p2 = map[selector.tournamentSelect(evals, fronts, gepaConfig.tournamentSize).candidateId] ?: continue
                    next.add(mutations.crossover(p1, p2, nextGen))
                }

                roll < gepaConfig.crossoverRate + gepaConfig.mutationRate -> {
                    val p = map[selector.tournamentSelect(evals, fronts, gepaConfig.tournamentSize).candidateId] ?: continue
                    next.add(mutations.mutateSegment(p, nextGen))
                }

                else -> {
                    val p = map[selector.tournamentSelect(evals, fronts, gepaConfig.tournamentSize).candidateId] ?: continue
                    next.add(p.copy(id = "gepa-copy-${next.size}", generation = nextGen))
                }
            }
        }
        return next
    }
}
