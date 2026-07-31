package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
data class StatsResponse(
    val totalTokens: Long = 0,
    val totalRequests: Long = 0,
    val estimatedCost: Double = 0.0,
    val avgFeedback: Double = 0.0,
    val feedbackCount: Long = 0,
)
