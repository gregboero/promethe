package dev.promethe.core

import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort as KoogReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import dev.promethe.api.ReasoningEffort
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams

object OpenAiCompatibleProviderPolicy {
    fun openAIParams(
        model: LLModel,
        effort: ReasoningEffort,
        maxTokens: Int,
    ): LLMParams {
        require(maxTokens > 0)
        return if (model.supports(LLMCapability.OpenAIEndpoint.Completions)) {
            OpenAIChatParams(maxTokens = maxTokens, temperature = null, reasoningEffort = effort.toKoog(), parallelToolCalls = false)
        } else {
            OpenAIResponsesParams(maxTokens = maxTokens, temperature = null, reasoning = effort.toKoog()?.let { ReasoningConfig(effort = it) }, include = listOf(OpenAIInclude.REASONING_ENCRYPTED_CONTENT), store = false, parallelToolCalls = false)
        }
    }

    fun kimiChatParams(
        context: LlmRequestContext?,
        effort: ReasoningEffort,
    ): OpenAIChatParams {
        require(effort != ReasoningEffort.MEDIUM && effort != ReasoningEffort.NONE) {
            "Kimi K3 supports only AUTO, LOW, or HIGH reasoning effort"
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
            ReasoningEffort.NONE -> KoogReasoningEffort.NONE
            ReasoningEffort.LOW -> KoogReasoningEffort.LOW
            ReasoningEffort.MEDIUM -> KoogReasoningEffort.MEDIUM
            ReasoningEffort.HIGH -> KoogReasoningEffort.HIGH
        }
}
