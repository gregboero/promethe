package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
enum class ToolIntentStatus {
    PREPARED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    BLOCKED,
}

/** Durable, secret-free record of one logical tool invocation. */
@Serializable
data class ToolIntentRecord(
    val intentId: String,
    val idempotencyKeyHash: String,
    val invocationHash: String,
    val runId: String? = null,
    val stepId: String? = null,
    val sessionId: String,
    val toolName: String,
    val risk: ToolRisk,
    val status: ToolIntentStatus,
    val resultHash: String? = null,
    val errorCode: String? = null,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val updatedAt: Long,
)
