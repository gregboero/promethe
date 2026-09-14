package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
enum class AgentRunStatus {
    PENDING,
    RUNNING,
    RECOVERABLE,
    RESUMING,
    NEEDS_REVIEW,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

@Serializable
data class AgentRunRecord(
    val runId: String,
    val parentRunId: String? = null,
    val sessionId: String,
    val origin: String,
    val projectId: String? = null,
    val requestFingerprint: String? = null,
    val status: AgentRunStatus,
    val stepCount: Int = 0,
    val lastStepId: String? = null,
    val errorCode: String? = null,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val updatedAt: Long,
)
