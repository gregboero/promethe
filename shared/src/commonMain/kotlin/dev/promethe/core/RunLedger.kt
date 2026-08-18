package dev.promethe.core

import dev.promethe.api.AgentRunRecord
import dev.promethe.api.AgentRunStatus
import dev.promethe.db.PrometheDatabaseApi

interface RunLedger {
    suspend fun begin(run: AgentRunRecord): Boolean

    suspend fun recordStep(
        runId: String,
        stepId: String,
        stepCount: Int,
        updatedAt: Long,
    ): Boolean

    suspend fun complete(
        runId: String,
        stepCount: Int,
        lastStepId: String?,
        finishedAt: Long,
    ): Boolean

    suspend fun fail(
        runId: String,
        stepCount: Int,
        lastStepId: String?,
        errorCode: String,
        finishedAt: Long,
    ): Boolean

    suspend fun cancel(
        runId: String,
        stepCount: Int,
        lastStepId: String?,
        finishedAt: Long,
    ): Boolean

    suspend fun get(runId: String): AgentRunRecord?

    suspend fun recoverable(): List<AgentRunRecord>
}

class PersistentRunLedger(
    private val database: PrometheDatabaseApi,
) : RunLedger {
    override suspend fun begin(run: AgentRunRecord): Boolean {
        require(run.status == AgentRunStatus.PENDING) { "A new run must start as PENDING" }
        if (!database.insertAgentRun(run)) return false
        check(
            database.transitionAgentRun(
                runId = run.runId,
                expectedStatuses = setOf(AgentRunStatus.PENDING),
                status = AgentRunStatus.RUNNING,
                stepCount = 0,
                lastStepId = null,
                errorCode = null,
                startedAt = run.createdAt,
                finishedAt = null,
                updatedAt = run.createdAt,
            ),
        ) { "Failed to transition new run to RUNNING" }
        return true
    }

    override suspend fun recordStep(
        runId: String,
        stepId: String,
        stepCount: Int,
        updatedAt: Long,
    ): Boolean {
        require(stepCount > 0) { "Run step count must be positive" }
        require(isValidExecutionId(stepId)) { "Invalid step ID" }
        return database.updateAgentRunProgress(runId, stepCount, stepId, updatedAt)
    }

    override suspend fun complete(
        runId: String,
        stepCount: Int,
        lastStepId: String?,
        finishedAt: Long,
    ): Boolean = transitionTerminal(runId, AgentRunStatus.SUCCEEDED, stepCount, lastStepId, null, finishedAt)

    override suspend fun fail(
        runId: String,
        stepCount: Int,
        lastStepId: String?,
        errorCode: String,
        finishedAt: Long,
    ): Boolean {
        require(errorCode.isNotBlank()) { "A failed run requires an error code" }
        return transitionTerminal(runId, AgentRunStatus.FAILED, stepCount, lastStepId, errorCode, finishedAt)
    }

    override suspend fun cancel(
        runId: String,
        stepCount: Int,
        lastStepId: String?,
        finishedAt: Long,
    ): Boolean = transitionTerminal(runId, AgentRunStatus.CANCELLED, stepCount, lastStepId, "cancelled", finishedAt)

    override suspend fun get(runId: String): AgentRunRecord? = database.getAgentRun(runId)

    override suspend fun recoverable(): List<AgentRunRecord> = database.getAgentRunsByStatus(setOf(AgentRunStatus.PENDING, AgentRunStatus.RUNNING))

    private suspend fun transitionTerminal(
        runId: String,
        status: AgentRunStatus,
        stepCount: Int,
        lastStepId: String?,
        errorCode: String?,
        finishedAt: Long,
    ): Boolean =
        database.transitionAgentRun(
            runId = runId,
            expectedStatuses = setOf(AgentRunStatus.PENDING, AgentRunStatus.RUNNING),
            status = status,
            stepCount = stepCount,
            lastStepId = lastStepId,
            errorCode = errorCode,
            startedAt = null,
            finishedAt = finishedAt,
            updatedAt = finishedAt,
        )
}
