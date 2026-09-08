package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
enum class ReasoningEffort {
    AUTO,
    NONE,
    LOW,
    MEDIUM,
    HIGH,
}
