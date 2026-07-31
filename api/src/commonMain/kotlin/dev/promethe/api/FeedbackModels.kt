package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
data class FeedbackRequest(
    val sessionId: String,
    val score: Double,
    val comment: String? = null,
)

@Serializable
data class FeedbackStats(
    val averageScore: Double,
    val totalCount: Long,
)
