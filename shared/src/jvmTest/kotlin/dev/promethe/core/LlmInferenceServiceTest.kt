package dev.promethe.core

import ai.koog.agents.core.tools.ToolDescriptor
import dev.promethe.api.ReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class LlmInferenceServiceTest {
    @Test
    fun `rejects a system-only inference before provider execution`() =
        runTest {
            val adapter = RecordingAdapter()
            val service = LlmInferenceService(adapter)

            assertFailsWith<IllegalArgumentException> {
                service.complete("Extract facts", emptyList())
            }
            assertEquals(0, adapter.calls)
        }

    @Test
    fun `sends non-empty user content to the provider`() =
        runTest {
            val adapter = RecordingAdapter()
            val service = LlmInferenceService(adapter)

            val response = service.complete("Extract facts", listOf("user" to "Conversation text"))

            assertEquals("ok", response.content)
            assertEquals(listOf("user" to "Conversation text"), adapter.lastMessages)
        }

    @Test
    fun `forwards an explicit request provider and model`() =
        runTest {
            val adapter = RecordingAdapter()
            val service = LlmInferenceService(adapter)

            service.complete(
                systemInstruction = "Extract facts",
                messages = listOf("user" to "Conversation text"),
                provider = "openai",
                model = "gpt-5.6-luna",
            )

            assertEquals("openai", adapter.lastProvider)
            assertEquals("gpt-5.6-luna", adapter.lastModel)
        }

    @Test
    fun `blank active model resolves to the provider default`() {
        val adapter = RecordingAdapter()

        adapter.updateActiveModel("openai", "")

        assertEquals("openai", adapter.currentProvider)
        assertEquals("gpt-5.6-terra", adapter.currentModel)
    }

    private class RecordingAdapter : KoogLlmAdapter(AgentConfig()) {
        var calls = 0
        var lastMessages = emptyList<Pair<String, String>>()
        var lastProvider = ""
        var lastModel = ""

        override suspend fun completeWithProfile(
            systemPrompt: String,
            messages: List<Pair<String, String>>,
            provider: String,
            model: String,
            temperature: Double,
            tools: List<ToolDescriptor>,
            context: LlmRequestContext?,
            pendingToolTurn: PendingToolTurn?,
            reasoningEffort: ReasoningEffort,
        ): LlmResponse {
            calls++
            lastMessages = messages
            lastProvider = provider
            lastModel = model
            return LlmResponse("ok", 0, 0, model, "test")
        }
    }
}
