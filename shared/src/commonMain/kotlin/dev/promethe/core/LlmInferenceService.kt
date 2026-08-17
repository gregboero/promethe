package dev.promethe.core

/**
 * Tool-free LLM primitive for memory, compression, classification, and evaluation.
 * It is deliberately separate from user task execution and enforces provider-safe content.
 */
class LlmInferenceService(
    private val llmAdapter: KoogLlmAdapter,
) {
    suspend fun complete(
        systemInstruction: String,
        messages: List<Pair<String, String>>,
        provider: String? = null,
        model: String? = null,
        temperature: Double? = null,
    ): LlmResponse {
        val contentMessages =
            messages.filter { (role, content) ->
                role.lowercase() != "system" && content.isNotBlank()
            }
        require(contentMessages.isNotEmpty()) {
            "LLM inference requires at least one non-system message with content"
        }

        val resolvedProvider = provider?.trim()?.takeIf { it.isNotEmpty() } ?: llmAdapter.currentProvider
        val resolvedModel = llmAdapter.resolveModel(resolvedProvider, model)
        return llmAdapter.completeWithProfile(
            systemPrompt = systemInstruction,
            messages = messages,
            provider = resolvedProvider,
            model = resolvedModel,
            temperature = temperature ?: 0.2,
        )
    }
}
