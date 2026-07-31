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

        return llmAdapter.complete(
            systemPrompt = systemInstruction,
            messages = messages,
            model = model ?: llmAdapter.currentModel,
            temperature = temperature ?: 0.2,
        )
    }
}
