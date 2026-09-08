package dev.promethe.core

import dev.promethe.api.*
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

fun interface SkillEvaluationSubject {
    suspend fun respond(
        skill: SkillEntry,
        input: String,
    ): String
}

/** Fresh, tool-free request per case. Expected answers are never passed to the subject. */
class SkillEvaluationService(
    private val loader: SkillLoader,
    private val store: SkillGovernanceStore,
    private val subject: SkillEvaluationSubject,
    private val evaluatorId: String,
    private val caseTimeoutMs: Long = 60_000,
) {
    suspend fun evaluate(
        slug: String,
        expectedRevisionHash: String,
    ): SkillEvaluationRun =
        evaluationMutex.withLock {
            val skill = loader.listSkills().firstOrNull { it.slug.ifBlank { it.name } == slug } ?: error("Skill not found")
            val revision = store.revisionHash(skill)
            check(revision == expectedRevisionHash) { "Skill changed; reload before evaluation" }
            val suites = store.declaredSuites(skill)
            val started = Clock.System.now().toEpochMilliseconds()
            val id = newSkillRevisionId()
            withContext(ioDispatcher) { store.beginRun(slug, SkillEvaluationRun(id, revision, started, 0, evaluatorId, SkillEvaluationStatus.RUNNING, emptyList())) }
            val results = mutableListOf<SkillEvaluationCaseResult>()
            var status = SkillEvaluationStatus.PASSED
            var cancellation: CancellationException? = null
            for (suite in suites) {
                for (case in suite.cases) {
                    if (cancellation != null) break
                    try {
                        val actual = withTimeout(caseTimeoutMs) { subject.respond(skill, case.input) }
                        val passed = actual.trim() == case.expectedOutput.trim()
                        results += SkillEvaluationCaseResult(
                            suite.id,
                            case.id,
                            if (passed) SkillEvaluationStatus.PASSED else SkillEvaluationStatus.FAILED,
                            skillContentDigest(actual),
                            if (passed) null else "Output differs from the exact expected answer",
                        )
                        if (!passed) status = SkillEvaluationStatus.FAILED
                    } catch (_: TimeoutCancellationException) {
                        results += SkillEvaluationCaseResult(suite.id, case.id, SkillEvaluationStatus.ERROR, reason = "Case timed out")
                        status = SkillEvaluationStatus.ERROR
                    } catch (error: CancellationException) {
                        results += SkillEvaluationCaseResult(suite.id, case.id, SkillEvaluationStatus.CANCELLED, reason = "Evaluation cancelled")
                        status = SkillEvaluationStatus.CANCELLED
                        cancellation = error
                    } catch (_: Exception) {
                        results += SkillEvaluationCaseResult(suite.id, case.id, SkillEvaluationStatus.ERROR, reason = "Evaluator failed")
                        status = SkillEvaluationStatus.ERROR
                    }
                }
            }
            val run = SkillEvaluationRun(id, revision, started, Clock.System.now().toEpochMilliseconds(), evaluatorId, status, results)
            withContext(NonCancellable + ioDispatcher) { store.saveRun(slug, run) }
            cancellation?.let { throw it }
            run
        }

    companion object {
        private val evaluationMutex = Mutex()
    }
}
