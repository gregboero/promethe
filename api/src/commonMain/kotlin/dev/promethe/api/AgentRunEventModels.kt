package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
enum class AgentRunEventType {
    RUN_STARTED,
    RUN_STEP_RECORDED,
    INTENT_PROPOSED,
    APPROVAL_RESOLVED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    RUN_FAILED,
    RUN_CANCELLED,
    RUN_FINISHED,
}

@Serializable
enum class AgentApprovalScope {
    ONCE,
    SESSION,
    PERSISTENT,
}

/** Append-only, secret-free fact emitted during one agent run. */
@Serializable
data class AgentRunEventRecord(
    val eventId: String,
    val runId: String,
    val sequence: Long = 0,
    val type: AgentRunEventType,
    val parentRunId: String? = null,
    val sessionId: String? = null,
    val origin: String? = null,
    val projectId: String? = null,
    val stepId: String? = null,
    val stepCount: Int? = null,
    val intentId: String? = null,
    val idempotencyKeyHash: String? = null,
    val invocationHash: String? = null,
    val toolName: String? = null,
    val risk: ToolRisk? = null,
    val runStatus: AgentRunStatus? = null,
    val intentStatus: ToolIntentStatus? = null,
    val resultHash: String? = null,
    val errorCode: String? = null,
    val approvalId: String? = null,
    val approvalAllowed: Boolean? = null,
    val approvalScope: AgentApprovalScope? = null,
    val createdAt: Long,
    val eventVersion: Int = 1,
)
