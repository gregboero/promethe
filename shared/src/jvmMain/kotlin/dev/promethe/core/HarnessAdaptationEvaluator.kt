package dev.promethe.core

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Host-owned held-out contract checks, never supplied by the candidate. No model or network calls. */
class HarnessAdaptationEvaluator(
    private val runner: HarnessRunner,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun evaluate(revision: HarnessRevision): HarnessAdaptationEvidence {
        val session = "eval-${UUID.randomUUID()}"
        val started = nanoTime()
        var passed = false
        var cold = 0L
        var warm = 0L
        var presented = 0L
        try {
            val coldStart = nanoTime()
            val results = runner.executeBatch(revision.source, CASES.map { HarnessObservation(revision.toolName, it.first) }, session)
            cold = elapsed(coldStart)
            passed = results == CASES.map { it.second }
            if (passed) {
                for ((input, expected) in CASES.take(3)) {
                    val warmStart = nanoTime()
                    val result = runner.execute(revision.source, HarnessObservation(revision.toolName, input), session)
                    warm = maxOf(warm, elapsed(warmStart))
                    passed = passed && result == expected
                }
                presented = CASES.zip(results).sumOf { (fixture, result) ->
                    // The real processor retains raw when provenance would erase the byte saving.
                    minOf(fixture.first.encodeToByteArray().size, result.encodeToByteArray().size + PROVENANCE_BYTES).toLong()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            passed = false
        } finally {
            withContext(NonCancellable) { runner.endSession(session) }
        }
        return HarnessAdaptationEvidence(SUITE, passed, CASES.size, CASES.sumOf { it.first.encodeToByteArray().size.toLong() }, presented, cold, warm, elapsed(started))
    }

    private fun elapsed(start: Long) = ((nanoTime() - start) / 1_000_000).coerceAtLeast(0)

    companion object {
        const val CONTRACT = "answer-extraction-v1"
        const val SUITE = "answer-extraction-heldout-v1"
        const val PROVENANCE_BYTES = 256

        // Distinct from the five public SessionHarness examples. This is finite regression coverage,
        // not a proof that arbitrary Kotlin preserves semantics for every unseen input.
        internal val CASES = listOf(
            "{\"noise\":\"${"a".repeat(9000)}\",\"answer\":-731}" to "-731",
            "{\"answer\":\"雪 et été\",\"noise\":\"${"b".repeat(9000)}\"}" to "雪 et été",
            "{\"answer\":false,\"noise\":\"${"c".repeat(9000)}\"}" to "false",
            "{\"answer\":null}" to "null",
            "{\"answer\":[1,2]}" to "[1,2]",
            "answer,id\n319,2" to "319",
            "DATA answer=-84" to "-84",
            "{\"other\":\"answer=wrong\"}" to "{\"other\":\"answer=wrong\"}",
            "no answer in this text" to "no answer in this text",
            "malformed { JSON" to "malformed { JSON",
        )
    }
}
