package dev.promethe.core

import dev.promethe.api.AgentRunEventRecord
import dev.promethe.api.AgentRunEventType
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
        val running = run.copy(status = AgentRunStatus.RUNNING, startedAt = run.createdAt)
        return database.insertAgentRun(
            running,
            AgentRunEventRecord(
                eventId = agentRunEventId(run.runId, AgentRunEventType.RUN_STARTED, "start"),
                runId = run.runId,
                type = AgentRunEventType.RUN_STARTED,
                parentRunId = run.parentRunId,
                sessionId = run.sessionId,
                origin = run.origin,
                projectId = run.projectId,
                runStatus = AgentRunStatus.RUNNING,
                createdAt = run.createdAt,
            ),
        )
    }

    override suspend fun recordStep(
        runId: String,
        stepId: String,
        stepCount: Int,
        updatedAt: Long,
    ): Boolean {
        require(stepCount > 0) { "Run step count must be positive" }
        require(isValidExecutionId(stepId)) { "Invalid step ID" }
        return database.updateAgentRunProgress(
            runId = runId,
            stepCount = stepCount,
            lastStepId = stepId,
            updatedAt = updatedAt,
            event =
                AgentRunEventRecord(
                    eventId = agentRunEventId(runId, AgentRunEventType.RUN_STEP_RECORDED, stepId),
                    runId = runId,
                    type = AgentRunEventType.RUN_STEP_RECORDED,
                    stepId = stepId,
                    stepCount = stepCount,
                    runStatus = AgentRunStatus.RUNNING,
                    createdAt = updatedAt,
                ),
        )
    }

    override suspend fun complete(
        runId: String,
        stepCount: Int,
        lastStepId: String?,
        finishedAt: Long,
    ): Boolean =
        transitionTerminal(
            runId,
            AgentRunStatus.SUCCEEDED,
            AgentRunEventType.RUN_FINISHED,
            stepCount,
            lastStepId,
            null,
            finishedAt,
        )

    override suspend fun fail(
        runId: String,
        stepCount: Int,
        lastStepId: String?,
        errorCode: String,
        finishedAt: Long,
    ): Boolean {
        require(errorCode.isNotBlank()) { "A failed run requires an error code" }
        return transitionTerminal(
            runId,
            AgentRunStatus.FAILED,
            AgentRunEventType.RUN_FAILED,
            stepCount,
            lastStepId,
            errorCode,
            finishedAt,
        )
    }

    override suspend fun cancel(
        runId: String,
        stepCount: Int,
        lastStepId: String?,
        finishedAt: Long,
    ): Boolean =
        transitionTerminal(
            runId,
            AgentRunStatus.CANCELLED,
            AgentRunEventType.RUN_CANCELLED,
            stepCount,
            lastStepId,
            "cancelled",
            finishedAt,
        )

    override suspend fun get(runId: String): AgentRunRecord? = database.getAgentRun(runId)

    override suspend fun recoverable(): List<AgentRunRecord> = database.getAgentRunsByStatus(setOf(AgentRunStatus.PENDING, AgentRunStatus.RUNNING))

    private suspend fun transitionTerminal(
        runId: String,
        status: AgentRunStatus,
        eventType: AgentRunEventType,
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
            event =
                AgentRunEventRecord(
                    eventId = agentRunEventId(runId, eventType, status.name),
                    runId = runId,
                    type = eventType,
                    stepId = lastStepId,
                    stepCount = stepCount,
                    runStatus = status,
                    errorCode = errorCode,
                    createdAt = finishedAt,
                ),
        )
}
