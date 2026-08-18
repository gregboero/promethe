package dev.promethe.evals

import dev.promethe.api.EvalAssertion
import dev.promethe.api.EvalAssertionKind
import dev.promethe.api.EvalAssertionResult
import dev.promethe.api.EvalCase
import dev.promethe.api.EvalCaseResult
import dev.promethe.api.EvalCaseStatus
import dev.promethe.api.EvalObservation
import dev.promethe.api.EvalRun
import dev.promethe.api.EvalRunStatus
import dev.promethe.api.EvalSuite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.time.Clock

fun interface EvalSubject {
    suspend fun execute(case: EvalCase): EvalObservation
}

class EvalRunner(
    private val subject: EvalSubject,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val runId: () -> String = { "eval-${UUID.randomUUID()}" },
) {
    suspend fun run(suite: EvalSuite): EvalRun {
        validate(suite)
        val startedAt = clock()
        val results = suite.cases.map { case -> runCase(case) }
        val finishedAt = clock()
        return EvalRun(
            id = runId(),
            suiteId = suite.id,
            suiteVersion = suite.version,
            startedAt = startedAt,
            finishedAt = finishedAt,
            status = if (results.all { it.status == EvalCaseStatus.PASSED }) EvalRunStatus.PASSED else EvalRunStatus.FAILED,
            results = results,
        )
    }

    private suspend fun runCase(case: EvalCase): EvalCaseResult {
        val startedAt = clock()
        return try {
            val observation = withTimeout(case.timeoutMs) { subject.execute(case) }
            val assertions = case.assertions.map { assertion -> evaluate(assertion, observation) }
            EvalCaseResult(
                caseId = case.id,
                status = if (assertions.all(EvalAssertionResult::passed)) EvalCaseStatus.PASSED else EvalCaseStatus.FAILED,
                durationMs = (clock() - startedAt).coerceAtLeast(0),
                assertions = assertions,
            )
        } catch (e: TimeoutCancellationException) {
            EvalCaseResult(
                caseId = case.id,
                status = EvalCaseStatus.ERROR,
                durationMs = (clock() - startedAt).coerceAtLeast(0),
                error = "Timed out after ${case.timeoutMs} ms",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EvalCaseResult(
                caseId = case.id,
                status = EvalCaseStatus.ERROR,
                durationMs = (clock() - startedAt).coerceAtLeast(0),
                error = e.message ?: e::class.simpleName,
            )
        }
    }

    private fun evaluate(
        assertion: EvalAssertion,
        observation: EvalObservation,
    ): EvalAssertionResult {
        val actual = observation.field(assertion.field)
        val expected = assertion.expected
        val passed =
            when (assertion.kind) {
                EvalAssertionKind.EQUALS -> actual == expected
                EvalAssertionKind.CONTAINS -> expected != null && actual?.contains(expected) == true
                EvalAssertionKind.NOT_CONTAINS -> expected != null && actual?.contains(expected) != true
                EvalAssertionKind.MATCHES_REGEX -> expected != null && actual?.matches(Regex(expected)) == true
                EvalAssertionKind.EXISTS -> actual != null
                EvalAssertionKind.NOT_EXISTS -> actual == null
            }
        return EvalAssertionResult(
            assertionId = assertion.id,
            passed = passed,
            actual = actual,
            message = if (passed) null else "${assertion.kind} failed for '${assertion.field}'",
        )
    }

    private fun validate(suite: EvalSuite) {
        require(suite.id.isNotBlank()) { "Eval suite ID must not be blank" }
        require(suite.version > 0) { "Eval suite version must be positive" }
        require(suite.cases.isNotEmpty()) { "Eval suite must contain at least one case" }
        require(suite.cases.map(EvalCase::id).distinct().size == suite.cases.size) { "Eval case IDs must be unique" }
        suite.cases.forEach { case ->
            require(case.id.isNotBlank()) { "Eval case ID must not be blank" }
            require(case.capability.isNotBlank()) { "Eval capability must not be blank" }
            require(case.timeoutMs > 0) { "Eval case '${case.id}' must have a positive timeout" }
            require(case.assertions.isNotEmpty()) { "Eval case '${case.id}' must contain assertions" }
            require(case.assertions.map(EvalAssertion::id).distinct().size == case.assertions.size) {
                "Eval assertion IDs must be unique in case '${case.id}'"
            }
            case.assertions.forEach { assertion ->
                require(assertion.id.isNotBlank()) { "Eval assertion ID must not be blank" }
                if (assertion.kind !in setOf(EvalAssertionKind.EXISTS, EvalAssertionKind.NOT_EXISTS)) {
                    require(assertion.expected != null) {
                        "Eval assertion '${assertion.id}' requires an expected value"
                    }
                }
            }
        }
    }
}

private fun EvalObservation.field(name: String): String? =
    when {
        name == "output" -> output
        name == "errorCode" -> errorCode
        name == "exitCode" -> exitCode?.toString()
        name.startsWith("metadata.") -> metadata[name.removePrefix("metadata.")]
        else -> null
    }
