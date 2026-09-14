package dev.promethe.core

data class LlmRequestContext(
    val sessionId: String?,
    val origin: AgentExecutionOrigin,
    val runId: String? = null,
    val parentRunId: String? = null,
    val stepId: String? = null,
)
