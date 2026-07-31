package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
enum class ReasoningEffort {
    AUTO,
    LOW,
    MEDIUM,
    HIGH,
}
