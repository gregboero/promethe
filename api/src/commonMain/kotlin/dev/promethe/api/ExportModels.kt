package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
data class ConversationExport(
    val sessionId: String,
    val title: String?,
    val createdAt: Long,
    val messages: List<ExportMessage>,
    val exportedAt: Long,
)

@Serializable
data class ExportMessage(
    val role: String,
    val content: String,
    val timestamp: Long,
)
