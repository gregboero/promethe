package dev.promethe.core

import kotlinx.coroutines.flow.Flow

interface AgentExecutionPort {
    fun execute(request: AgentExecutionRequest): Flow<AgentExecutionEvent>

    suspend fun executeToCompletion(request: AgentExecutionRequest): String
}

interface RecoverableAgentExecutionPort : AgentExecutionPort {
    fun resume(request: AgentExecutionRequest): Flow<AgentExecutionEvent>

    suspend fun auditInterruptedRuns(): List<RunRecoveryAssessment>
}
