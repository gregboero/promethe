package dev.promethe.evals

import dev.promethe.api.EvalCase
import dev.promethe.api.EvalObservation
import dev.promethe.core.AgentExecutionEvent
import dev.promethe.core.AgentExecutionOrigin
import dev.promethe.core.AgentExecutionPort
import dev.promethe.core.AgentExecutionRequest
import kotlinx.coroutines.flow.collect

/** Runs an eval case through the same agent execution boundary used by public adapters. */
class AgentExecutionEvalSubject(
    private val executionService: AgentExecutionPort,
    private val requestFactory: (EvalCase) -> AgentExecutionRequest = ::defaultEvalRequest,
) : EvalSubject {
    override suspend fun execute(case: EvalCase): EvalObservation {
        var completed: AgentExecutionEvent.Completed? = null
        var failed: AgentExecutionEvent.Failed? = null
        var stepCount = 0
        var lastStepId: String? = null

        executionService.execute(requestFactory(case)).collect { event ->
            when (event) {
                is AgentExecutionEvent.Step -> {
                    stepCount++
                    lastStepId = event.stepId
                }

                is AgentExecutionEvent.Completed -> {
                    completed = event
                }

                is AgentExecutionEvent.Failed -> {
                    failed = event
                }
            }
        }

        failed?.let { failure ->
            return EvalObservation(
                output = failure.message,
                errorCode = failure.code,
                metadata =
                    buildMap {
                        put("status", "failed")
                        put("runId", failure.runId)
                        put("steps", failure.steps.toString())
                        put("retryable", failure.code.isRetryableFailure().toString())
                        failure.stepId?.let { put("lastStepId", it) }
                    },
            )
        }

        val result = checkNotNull(completed) { "Agent execution produced no terminal event" }
        return EvalObservation(
            output = result.response,
            metadata =
                buildMap {
                    put("status", "completed")
                    put("runId", result.runId)
                    put("steps", stepCount.toString())
                    lastStepId?.let { put("lastStepId", it) }
                },
        )
    }
}

private fun defaultEvalRequest(case: EvalCase): AgentExecutionRequest =
    AgentExecutionRequest(
        sessionId = "eval-${case.id}",
        text = case.input,
        provider = case.provider,
        model = case.model,
        origin = AgentExecutionOrigin.INTERNAL,
    )

private fun String.isRetryableFailure(): Boolean = this in setOf("provider_rate_limited", "provider_timeout", "provider_unavailable")
