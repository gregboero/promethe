package dev.promethe.core

data class LlmRequestContext(
    val sessionId: String?,
    val origin: AgentExecutionOrigin,
)
