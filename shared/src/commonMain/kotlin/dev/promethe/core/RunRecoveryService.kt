package dev.promethe.core

import dev.promethe.api.AgentRunRecord
import dev.promethe.api.AgentRunStatus
import kotlin.time.Clock

enum class RunRecoveryDisposition {
    RECOVERABLE,
    NEEDS_REVIEW,
    NOT_RECOVERABLE,
}

data class RunRecoveryAssessment(
    val run: AgentRunRecord,
    val disposition: RunRecoveryDisposition,
    val reasonCode: String,
    val uncertainIntentIds: List<String> = emptyList(),
)

/**
 * Converts runs left active by a process crash into an explicit recovery state.
 * External effects with an unknown outcome always require human reconciliation.
 */
class RunRecoveryService(
    private val runLedger: RunLedger,
    private val eventLedger: RunEventLedger,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun auditInterruptedRuns(): List<RunRecoveryAssessment> = runLedger.interrupted().map { run -> classifyInterrupted(run) }

    suspend fun assess(runId: String): RunRecoveryAssessment? {
        val run = runLedger.get(runId) ?: return null
        return when (run.status) {
            AgentRunStatus.PENDING,
            AgentRunStatus.RUNNING,
            AgentRunStatus.RESUMING,
            -> {
                classifyInterrupted(run)
            }

            AgentRunStatus.RECOVERABLE -> {
                RunRecoveryAssessment(
                    run = run,
                    disposition = RunRecoveryDisposition.RECOVERABLE,
                    reasonCode = run.errorCode ?: RECOVERABLE_REASON,
                )
            }

            AgentRunStatus.NEEDS_REVIEW -> {
                RunRecoveryAssessment(
                    run = run,
                    disposition = RunRecoveryDisposition.NEEDS_REVIEW,
                    reasonCode = run.errorCode ?: UNCERTAIN_EFFECT_REASON,
                    uncertainIntentIds = eventLedger.uncertainToolIntents(runId).map { it.intentId },
                )
            }

            AgentRunStatus.SUCCEEDED,
            AgentRunStatus.FAILED,
            AgentRunStatus.CANCELLED,
            -> {
                RunRecoveryAssessment(
                    run = run,
                    disposition = RunRecoveryDisposition.NOT_RECOVERABLE,
                    reasonCode = "run_is_terminal",
                )
            }
        }
    }

    suspend fun claimResume(runId: String): AgentRunRecord? = runLedger.claimResume(runId, now())

    suspend fun markResumed(runId: String): Boolean = runLedger.markResumed(runId, now())

    private suspend fun classifyInterrupted(run: AgentRunRecord): RunRecoveryAssessment {
        val uncertainIntents = eventLedger.uncertainToolIntents(run.runId)
        val missingRequestFingerprint = run.requestFingerprint.isNullOrBlank()
        val disposition =
            if (uncertainIntents.isEmpty() && !missingRequestFingerprint) {
                RunRecoveryDisposition.RECOVERABLE
            } else {
                RunRecoveryDisposition.NEEDS_REVIEW
            }
        val status =
            if (disposition == RunRecoveryDisposition.RECOVERABLE) {
                AgentRunStatus.RECOVERABLE
            } else {
                AgentRunStatus.NEEDS_REVIEW
            }
        val reason =
            if (missingRequestFingerprint) {
                MISSING_REQUEST_FINGERPRINT_REASON
            } else if (disposition == RunRecoveryDisposition.RECOVERABLE) {
                RECOVERABLE_REASON
            } else {
                UNCERTAIN_EFFECT_REASON
            }
        val classified = runLedger.classifyRecovery(run.runId, status, reason, now())
        val persisted = runLedger.get(run.runId) ?: run
        return RunRecoveryAssessment(
            run = persisted,
            disposition = if (classified || persisted.status == status) disposition else RunRecoveryDisposition.NOT_RECOVERABLE,
            reasonCode = if (classified || persisted.status == status) reason else "recovery_state_changed",
            uncertainIntentIds = uncertainIntents.map { it.intentId },
        )
    }

    private companion object {
        const val RECOVERABLE_REASON = "interrupted_before_completion"
        const val UNCERTAIN_EFFECT_REASON = "uncertain_external_effect"
        const val MISSING_REQUEST_FINGERPRINT_REASON = "missing_request_fingerprint"
    }
}
