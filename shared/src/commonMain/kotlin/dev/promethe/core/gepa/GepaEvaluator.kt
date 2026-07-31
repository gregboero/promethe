package dev.promethe.core.gepa

import dev.promethe.core.Log

import dev.promethe.core.AgentConfig
import dev.promethe.core.KoogLlmAdapter
import kotlinx.coroutines.*
import kotlin.time.Clock

class GepaEvaluator(
    private val llmAdapter: KoogLlmAdapter,
    private val config: AgentConfig,
) {
    private val logger = Log.create("GepaEvaluator")

    /** Évalue un candidat sur tous les test cases en parallèle */
    suspend fun evaluate(
        candidate: PromptCandidate,
        testCases: List<GepaTestCase>,
    ): CandidateEvaluation =
        coroutineScope {
            val results = testCases.map { tc -> async { evaluateSingle(candidate, tc) } }.awaitAll()
            CandidateEvaluation(
                candidateId = candidate.id,
                accuracy = results.map { it.score }.average().takeIf { !it.isNaN() } ?: 0.0,
                tokenCount = estimateTokens(candidate.promptText),
                avgLatencyMs = results.map { it.latencyMs }.average().toLong(),
                testCaseResults = results,
            )
        }

    private suspend fun evaluateSingle(
        candidate: PromptCandidate,
        tc: GepaTestCase,
    ): TestCaseResult {
        val start = Clock.System.now().toEpochMilliseconds()
        val output =
            try {
                llmAdapter
                    .complete(
                        systemPrompt = candidate.promptText,
                        messages = listOf("user" to tc.userInput),
                        temperature = 0.1,
                    ).content
            } catch (e: Exception) {
                "[ERROR] ${e.message}"
            }
        val latency = Clock.System.now().toEpochMilliseconds() - start

        val score =
            when (tc.evaluationType) {
                EvaluationType.EXACT_MATCH -> {
                    if (output.trim() == tc.expectedOutput?.trim()) 1.0 else 0.0
                }

                EvaluationType.CONTAINS -> {
                    if (tc.expectedOutput != null && output.contains(tc.expectedOutput, true)) 1.0 else 0.0
                }

                EvaluationType.LLM_JUDGE -> {
                    llmJudge(tc, output)
                }
            }
        return TestCaseResult(tc.id, tc.expectedBehavior, output, score, latency)
    }

    private suspend fun llmJudge(
        tc: GepaTestCase,
        output: String,
    ): Double =
        try {
            llmAdapter
                .complete(
                    systemPrompt = "Score the response 0.0-1.0. Output ONLY the number.",
                    messages =
                        listOf(
                            "user" to
                                "Query: ${tc.userInput}\nExpected: ${tc.expectedBehavior}\nActual: $output",
                        ),
                    temperature = 0.0,
                ).content
                .trim()
                .toDoubleOrNull()
                ?.coerceIn(0.0, 1.0) ?: 0.5
        } catch (e: Exception) {
            logger.warn(e) { "LLM judge scoring failed for test case '${tc.id}'" }
            0.5
        }

    private fun estimateTokens(text: String) = (text.length / 4.0).toInt()
}
