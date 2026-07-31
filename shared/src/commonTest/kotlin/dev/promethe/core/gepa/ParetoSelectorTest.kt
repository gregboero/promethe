package dev.promethe.core.gepa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParetoSelectorTest {
    @Test
    fun testDominance() {
        val a = CandidateEvaluation("a", 0.9, 100, 500, emptyList())
        val b = CandidateEvaluation("b", 0.8, 150, 600, emptyList())
        assertTrue(a.dominates(b))
        assertFalse(b.dominates(a))
    }

    @Test
    fun testNonDominance_tradeoff() {
        val a = CandidateEvaluation("a", 0.9, 200, 500, emptyList())
        val b = CandidateEvaluation("b", 0.8, 100, 600, emptyList())
        assertFalse(a.dominates(b))
        assertFalse(b.dominates(a))
    }

    @Test
    fun testFronts() {
        val selector = ParetoSelector()
        val evals =
            listOf(
                CandidateEvaluation("a", 0.9, 200, 500, emptyList()),
                CandidateEvaluation("b", 0.8, 100, 600, emptyList()),
                CandidateEvaluation("c", 0.7, 300, 700, emptyList()),
            )
        val fronts = selector.computeParetoFronts(evals)
        assertEquals(2, fronts.size)
        assertEquals(2, fronts[0].size) // a, b non-dominés
        assertEquals(1, fronts[1].size) // c dominé par a
    }

    @Test
    fun testEliteSelection() {
        val selector = ParetoSelector()
        val evals =
            listOf(
                CandidateEvaluation("a", 0.9, 100, 500, emptyList()),
                CandidateEvaluation("b", 0.95, 150, 600, emptyList()),
                CandidateEvaluation("c", 0.7, 80, 400, emptyList()),
            )
        val fronts = selector.computeParetoFronts(evals)
        val elites = selector.selectElites(fronts, 2)
        assertTrue(elites.isNotEmpty())
        // Elites should be from front 0, sorted by accuracy desc
        assertEquals("b", elites.first().candidateId)
    }

    @Test
    fun testTournamentSelect() {
        val selector = ParetoSelector()
        val evals =
            listOf(
                CandidateEvaluation("a", 0.9, 100, 500, emptyList()),
                CandidateEvaluation("b", 0.8, 150, 600, emptyList()),
            )
        val fronts = selector.computeParetoFronts(evals)
        val selected = selector.tournamentSelect(evals, fronts, 2)
        assertTrue(selected.candidateId in listOf("a", "b"))
    }

    @Test
    fun testEmptyEvals() {
        val selector = ParetoSelector()
        val fronts = selector.computeParetoFronts(emptyList())
        assertTrue(fronts.isEmpty())
    }
}
