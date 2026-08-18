package dev.promethe.core

import dev.promethe.api.AgentRunEventRecord
import dev.promethe.api.AgentRunEventType
import dev.promethe.api.AgentRunRecord
import dev.promethe.api.AgentRunStatus
import dev.promethe.api.ToolIntentRecord
import dev.promethe.api.ToolIntentStatus
import dev.promethe.api.ToolRisk
import dev.promethe.db.PrometheDatabaseApi

interface RunEventLedger {
    suspend fun events(runId: String): List<AgentRunEventRecord>

    suspend fun reconstructRun(runId: String): AgentRunRecord?

    suspend fun reconstructToolIntents(runId: String): List<ToolIntentRecord>

    suspend fun uncertainToolIntents(runId: String): List<ToolIntentRecord>
}

class PersistentRunEventLedger(
    private val database: PrometheDatabaseApi,
) : RunEventLedger {
    override suspend fun events(runId: String): List<AgentRunEventRecord> = database.getAgentRunEvents(runId).sortedBy(AgentRunEventRecord::sequence)

    override suspend fun reconstructRun(runId: String): AgentRunRecord? {
        val events = events(runId)
        val started = events.firstOrNull { it.type == AgentRunEventType.RUN_STARTED } ?: return null
        val sessionId = started.sessionId ?: return null
        val origin = started.origin ?: return null
        var status = started.runStatus ?: AgentRunStatus.RUNNING
        var stepCount = 0
        var lastStepId: String? = null
        var errorCode: String? = null
        var finishedAt: Long? = null
        var updatedAt = started.createdAt

        events.forEach { event ->
            when (event.type) {
                AgentRunEventType.RUN_STEP_RECORDED -> {
                    stepCount = event.stepCount ?: stepCount
                    lastStepId = event.stepId ?: lastStepId
                    updatedAt = event.createdAt
                }

                AgentRunEventType.RUN_FINISHED,
                AgentRunEventType.RUN_FAILED,
                AgentRunEventType.RUN_CANCELLED,
                -> {
                    status = event.runStatus ?: status
                    stepCount = event.stepCount ?: stepCount
                    lastStepId = event.stepId ?: lastStepId
                    errorCode = event.errorCode
                    finishedAt = event.createdAt
                    updatedAt = event.createdAt
                }

                else -> {}
            }
        }

        return AgentRunRecord(
            runId = runId,
            parentRunId = started.parentRunId,
            sessionId = sessionId,
            origin = origin,
            projectId = started.projectId,
            status = status,
            stepCount = stepCount,
            lastStepId = lastStepId,
            errorCode = errorCode,
            createdAt = started.createdAt,
            startedAt = started.createdAt,
            finishedAt = finishedAt,
            updatedAt = updatedAt,
        )
    }

    override suspend fun reconstructToolIntents(runId: String): List<ToolIntentRecord> =
        events(runId)
            .filter { it.intentId != null }
            .groupBy { requireNotNull(it.intentId) }
            .mapNotNull { (intentId, intentEvents) -> reconstructToolIntent(intentId, intentEvents) }
            .sortedBy(ToolIntentRecord::createdAt)

    override suspend fun uncertainToolIntents(runId: String): List<ToolIntentRecord> =
        reconstructToolIntents(runId).filter { intent ->
            intent.status == ToolIntentStatus.EXECUTING && intent.risk != ToolRisk.READ
        }

    private fun reconstructToolIntent(
        intentId: String,
        events: List<AgentRunEventRecord>,
    ): ToolIntentRecord? {
        val ordered = events.sortedBy(AgentRunEventRecord::sequence)
        val proposed = ordered.firstOrNull { it.type == AgentRunEventType.INTENT_PROPOSED } ?: return null
        val idempotencyKeyHash = proposed.idempotencyKeyHash ?: return null
        val invocationHash = proposed.invocationHash ?: return null
        val sessionId = proposed.sessionId ?: return null
        val toolName = proposed.toolName ?: return null
        val risk = proposed.risk ?: return null
        var status = proposed.intentStatus ?: ToolIntentStatus.PREPARED
        var resultHash: String? = null
        var errorCode: String? = null
        var startedAt: Long? = null
        var finishedAt: Long? = null
        var updatedAt = proposed.createdAt

        ordered.forEach { event ->
            when (event.type) {
                AgentRunEventType.INTENT_PROPOSED -> {
                    status = event.intentStatus ?: ToolIntentStatus.PREPARED
                    resultHash = null
                    errorCode = null
                    startedAt = null
                    finishedAt = null
                    updatedAt = event.createdAt
                }

                AgentRunEventType.TOOL_STARTED -> {
                    status = event.intentStatus ?: ToolIntentStatus.EXECUTING
                    startedAt = event.createdAt
                    updatedAt = event.createdAt
                }

                AgentRunEventType.TOOL_COMPLETED -> {
                    status = event.intentStatus ?: status
                    resultHash = event.resultHash
                    errorCode = event.errorCode
                    finishedAt = event.createdAt
                    updatedAt = event.createdAt
                }

                else -> {}
            }
        }

        return ToolIntentRecord(
            intentId = intentId,
            idempotencyKeyHash = idempotencyKeyHash,
            invocationHash = invocationHash,
            runId = proposed.runId,
            stepId = proposed.stepId,
            sessionId = sessionId,
            toolName = toolName,
            risk = risk,
            status = status,
            resultHash = resultHash,
            errorCode = errorCode,
            createdAt = proposed.createdAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            updatedAt = updatedAt,
        )
    }
}

internal fun agentRunEventId(
    runId: String,
    type: AgentRunEventType,
    subject: String,
): String = "event-${toolIntentDigest("promethe-run-event-v1\u0000$runId\u0000${type.name}\u0000$subject")}"
