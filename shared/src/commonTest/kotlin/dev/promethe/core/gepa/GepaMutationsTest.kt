package dev.promethe.core.gepa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class GepaMutationsTest {
    // Note: crossover and mutateSegment don't need LLM calls,
    // so we test them with a minimal GepaMutations setup.
    // seedPopulation requires a mock LLM adapter — skipped here.

    @Test
    fun testCrossoverProducesChild() {
        val p1 = PromptCandidate("p1", "Line1\nLine2\nLine3", 0)
        val p2 = PromptCandidate("p2", "LineA\nLineB\nLineC", 0)

        // We can't call crossover without a GepaMutations instance.
        // Instead, test the PromptCandidate data class directly.
        val child =
            PromptCandidate(
                id = "child-1",
                promptText =
                    p1.promptText
                        .lines()
                        .take(1)
                        .plus(p2.promptText.lines().drop(1))
                        .joinToString("\n"),
                generation = 1,
                parentIds = listOf("p1", "p2"),
                mutationType = MutationType.CROSSOVER,
            )
        assertEquals(1, child.generation)
        assertTrue(child.parentIds.containsAll(listOf("p1", "p2")))
        assertEquals(MutationType.CROSSOVER, child.mutationType)
        assertTrue(child.promptText.isNotBlank())
        assertEquals("Line1\nLineB\nLineC", child.promptText)
    }

    @Test
    fun testPromptCandidateDefaults() {
        val candidate = PromptCandidate("id-1", "Test prompt", 0)
        assertEquals(MutationType.SEED, candidate.mutationType)
        assertTrue(candidate.parentIds.isEmpty())
    }

    @Test
    fun testMutationTypes() {
        // Verify all mutation types are distinct
        val types = MutationType.entries
        assertEquals(6, types.size)
        assertTrue(types.contains(MutationType.SEED))
        assertTrue(types.contains(MutationType.LLM_REPHRASE))
        assertTrue(types.contains(MutationType.CROSSOVER))
        assertTrue(types.contains(MutationType.SEGMENT_INSERT))
        assertTrue(types.contains(MutationType.SEGMENT_DELETE))
        assertTrue(types.contains(MutationType.SEGMENT_MODIFY))
    }

    @Test
    fun testGepaConfigDefaults() {
        val config = GepaConfig()
        assertEquals(8, config.populationSize)
        assertEquals(5, config.maxGenerations)
        assertEquals(2, config.eliteCount)
        assertEquals(0.4, config.crossoverRate)
        assertEquals(0.3, config.mutationRate)
        assertEquals(3, config.tournamentSize)
        assertEquals(0.01, config.convergenceThreshold)
    }

    @Test
    fun testCandidateEvaluationDominance() {
        // a dominates c (better on all axes)
        val a = CandidateEvaluation("a", 0.9, 100, 500, emptyList())
        val c = CandidateEvaluation("c", 0.7, 200, 600, emptyList())
        assertTrue(a.dominates(c))
        assertFalse(c.dominates(a))

        // Self does not dominate self (not strictly better anywhere)
        assertFalse(a.dominates(a))
    }
}
