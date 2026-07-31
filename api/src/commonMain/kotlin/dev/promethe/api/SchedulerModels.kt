package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
data class ScheduledTaskRequest(
    val name: String,
    val cronExpression: String = "", // "*/5 * * * *" (min hour dom month dow)
    val prompt: String, // Message to send to the agent
    val profileId: String? = null, // Optional agent profile
    val enabled: Boolean = true,
    val runAt: Long? = null, // One-shot: epoch ms, null = recurring cron
)

@Serializable
data class ScheduledTaskResponse(
    val id: String,
    val name: String,
    val cronExpression: String,
    val prompt: String,
    val profileId: String? = null,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null,
    val lastRunStatus: String? = null,
    val createdAt: Long = 0,
    val runAt: Long? = null, // One-shot: original target time
)

@Serializable
data class ScheduledTaskListResponse(
    val tasks: List<ScheduledTaskResponse>,
)
