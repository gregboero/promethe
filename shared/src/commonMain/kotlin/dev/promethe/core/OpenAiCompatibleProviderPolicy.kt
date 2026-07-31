package dev.promethe.core

import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort as KoogReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import dev.promethe.api.ReasoningEffort

object OpenAiCompatibleProviderPolicy {
    fun kimiChatParams(
        context: LlmRequestContext?,
        effort: ReasoningEffort,
    ): OpenAIChatParams {
        require(effort != ReasoningEffort.MEDIUM) {
            "Kimi K3 does not support reasoning effort MEDIUM; use AUTO, LOW, or HIGH"
        }
        return OpenAIChatParams(
            temperature = null,
            reasoningEffort = effort.toKoog(),
            parallelToolCalls = false,
            promptCacheKey = context?.sessionId,
        )
    }

    fun xaiChatParams(effort: ReasoningEffort) =
        OpenAIChatParams(
            temperature = null,
            reasoningEffort = effort.toKoog(),
            parallelToolCalls = false,
        )

    fun xaiResponsesParams(
        context: LlmRequestContext?,
        effort: ReasoningEffort,
    ): OpenAIResponsesParams =
        OpenAIResponsesParams(
            temperature = null,
            reasoning = effort.toKoog()?.let { ReasoningConfig(effort = it) },
            include = listOf(OpenAIInclude.REASONING_ENCRYPTED_CONTENT),
            store = false,
            parallelToolCalls = false,
            promptCacheKey = context?.sessionId,
        )

    fun ReasoningEffort.toKoog(): KoogReasoningEffort? =
        when (this) {
            ReasoningEffort.AUTO -> null
            ReasoningEffort.LOW -> KoogReasoningEffort.LOW
            ReasoningEffort.MEDIUM -> KoogReasoningEffort.MEDIUM
            ReasoningEffort.HIGH -> KoogReasoningEffort.HIGH
        }
}
