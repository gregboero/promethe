package dev.promethe.core.gepa

/**
 * ParetoSelector — brute-force non-dominated sorting.
 *
 * O(n²) per front, optimal for GEPA population sizes (8-20).
 */
class ParetoSelector {
    fun computeParetoFronts(evals: List<CandidateEvaluation>): List<List<CandidateEvaluation>> {
        val remaining = evals.toMutableList()
        val fronts = mutableListOf<List<CandidateEvaluation>>()
        while (remaining.isNotEmpty()) {
            val front = remaining.filter { c -> remaining.none { o -> o != c && o.dominates(c) } }
            fronts.add(front)
            remaining.removeAll(front.toSet())
        }
        return fronts
    }

    fun tournamentSelect(
        evals: List<CandidateEvaluation>,
        fronts: List<List<CandidateEvaluation>>,
        k: Int,
    ): CandidateEvaluation {
        val rankMap = mutableMapOf<String, Int>()
        fronts.forEachIndexed { rank, front ->
            front.forEach { rankMap[it.candidateId] = rank }
        }
        val contestants = (0 until k).map { evals[kotlin.random.Random.nextInt(evals.size)] }
        return contestants.minByOrNull { rankMap[it.candidateId] ?: Int.MAX_VALUE } ?: evals.first()
    }

    fun selectElites(
        fronts: List<List<CandidateEvaluation>>,
        count: Int,
    ): List<CandidateEvaluation> = fronts.firstOrNull()?.sortedByDescending { it.accuracy }?.take(count) ?: emptyList()
}
