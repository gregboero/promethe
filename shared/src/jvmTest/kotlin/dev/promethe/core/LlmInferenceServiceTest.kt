package dev.promethe.core

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

    private class RecordingAdapter : KoogLlmAdapter(AgentConfig()) {
        var calls = 0
        var lastMessages = emptyList<Pair<String, String>>()

        override suspend fun complete(
            systemPrompt: String,
            messages: List<Pair<String, String>>,
            model: String,
            temperature: Double,
        ): LlmResponse {
            calls++
            lastMessages = messages
            return LlmResponse("ok", 0, 0, model, "test")
        }
    }
}
