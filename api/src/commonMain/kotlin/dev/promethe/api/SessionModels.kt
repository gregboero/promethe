package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
data class SessionInfo(
    val id: String,
    val createdAt: Long,
    val messageCount: Int = 0,
    val title: String? = null,
    val metadata: String? = null,
)

@Serializable
data class SessionListResponse(
    val sessions: List<SessionInfo>,
)

@Serializable
data class CreateSessionRequest(
    val id: String? = null, // auto-generated if null
)
