package dev.promethe.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DelegationRequest(
    val task: String,
    val profileId: String? = null,
    val systemPromptOverride: String? = null,
    val parentSessionId: String,
)

@Serializable
data class DelegationResponse(
    val childSessionId: String,
    val status: String,
)

@Serializable
data class SubAgentStatusResponse(
    val sessionId: String,
    val profileId: String?,
    val task: String,
    val status: String,
    val response: String?,
    val durationMs: Long?,
)

@Serializable
data class OrchestratorDashboardResponse(
    val activeCount: Int,
    val completedCount: Int,
    val subAgents: List<SubAgentStatusResponse>,
)
