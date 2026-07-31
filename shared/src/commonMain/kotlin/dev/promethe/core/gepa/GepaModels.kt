package dev.promethe.core.gepa

import kotlinx.serialization.Serializable

@Serializable
data class PromptCandidate(
    val id: String,
    val promptText: String,
    val generation: Int,
    val parentIds: List<String> = emptyList(),
    val mutationType: MutationType = MutationType.SEED,
)

@Serializable
enum class MutationType {
    SEED,
    LLM_REPHRASE,
    CROSSOVER,
    SEGMENT_INSERT,
    SEGMENT_DELETE,
    SEGMENT_MODIFY,
}

@Serializable
data class CandidateEvaluation(
    val candidateId: String,
    val accuracy: Double, // 0.0 - 1.0
    val tokenCount: Int,
    val avgLatencyMs: Long,
    val testCaseResults: List<TestCaseResult>,
) {
    /** Dominance Pareto : meilleur ou égal partout, strictement meilleur quelque part */
    fun dominates(other: CandidateEvaluation): Boolean {
        val geAccuracy = accuracy >= other.accuracy
        val leTokens = tokenCount <= other.tokenCount
        val leLatency = avgLatencyMs <= other.avgLatencyMs
        val strictlyBetter =
            accuracy > other.accuracy ||
                tokenCount < other.tokenCount ||
                avgLatencyMs < other.avgLatencyMs
        return geAccuracy && leTokens && leLatency && strictlyBetter
    }
}

@Serializable
data class TestCaseResult(
    val testCaseId: String,
    val expectedOutput: String,
    val actualOutput: String,
    val score: Double,
    val latencyMs: Long,
)

@Serializable
data class GepaTestCase(
    val id: String,
    val userInput: String,
    val expectedBehavior: String,
    val expectedOutput: String? = null,
    val evaluationType: EvaluationType = EvaluationType.LLM_JUDGE,
)

@Serializable
enum class EvaluationType { EXACT_MATCH, CONTAINS, LLM_JUDGE }

@Serializable
data class GepaConfig(
    val populationSize: Int = 8,
    val maxGenerations: Int = 5,
    val eliteCount: Int = 2,
    val crossoverRate: Double = 0.4,
    val mutationRate: Double = 0.3,
    val tournamentSize: Int = 3,
    val convergenceThreshold: Double = 0.01,
)

data class GepaResult(
    val bestPrompt: String,
    val bestCandidate: PromptCandidate,
    val bestEvaluation: CandidateEvaluation,
    val generations: List<GenerationSnapshot>,
    val originalPrompt: String,
    val improvement: Double,
)

@Serializable
data class GenerationSnapshot(
    val generation: Int,
    val populationSize: Int,
    val frontSizes: List<Int>,
    val bestAccuracy: Double,
    val bestTokenCount: Int,
    val avgAccuracy: Double,
)
