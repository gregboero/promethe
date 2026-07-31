package dev.promethe.core

import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort as KoogReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import dev.promethe.api.ReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KoogContractTest {
    private val context = LlmRequestContext("session-42", AgentExecutionOrigin.A2A)

    @Test
    fun `kimi chat parameters omit temperature and disable parallel tools`() {
        val automatic = OpenAiCompatibleProviderPolicy.kimiChatParams(context, ReasoningEffort.AUTO)
        val low = OpenAiCompatibleProviderPolicy.kimiChatParams(context, ReasoningEffort.LOW)

        assertNull(automatic.temperature)
        assertNull(automatic.reasoningEffort)
        assertEquals(false, automatic.parallelToolCalls)
        assertEquals("session-42", automatic.promptCacheKey)
        assertEquals(KoogReasoningEffort.LOW, low.reasoningEffort)
        assertFailsWith<IllegalArgumentException> {
            OpenAiCompatibleProviderPolicy.kimiChatParams(context, ReasoningEffort.MEDIUM)
        }
    }

    @Test
    fun `xai Responses parameters preserve encrypted reasoning without storage`() {
        val params = OpenAiCompatibleProviderPolicy.xaiResponsesParams(context, ReasoningEffort.MEDIUM)

        assertNull(params.temperature)
        assertEquals(false, params.store)
        assertEquals(false, params.parallelToolCalls)
        assertEquals("session-42", params.promptCacheKey)
        assertEquals(KoogReasoningEffort.MEDIUM, params.reasoning?.effort)
        assertTrue(OpenAIInclude.REASONING_ENCRYPTED_CONTENT in params.include.orEmpty())
    }

    @Test
    fun `xai AUTO omits reasoning and Chat omits Responses-only fields`() {
        val responses = OpenAiCompatibleProviderPolicy.xaiResponsesParams(context, ReasoningEffort.AUTO)
        val chat = OpenAiCompatibleProviderPolicy.xaiChatParams(ReasoningEffort.HIGH)

        assertNull(responses.reasoning)
        assertEquals(KoogReasoningEffort.HIGH, chat.reasoningEffort)
        assertNull(chat.temperature)
        assertEquals(false, chat.parallelToolCalls)
    }

    @Test
    fun `typed tool turn preserves reasoning call id and result`() {
        val reasoning = MessagePart.Reasoning(content = "thinking", encrypted = "encrypted-state", id = "reasoning-1")
        val call = MessagePart.Tool.Call(id = "call-1", tool = "weather", args = "{\"city\":\"Paris\"}")
        val assistant = Message.Assistant(listOf(reasoning, call), ResponseMetaInfo.Empty)
        val pending = PendingToolTurn(assistant, "call-1", "weather", "Sunny")

        val prompt = KoogLlmAdapter(AgentConfig()).buildPrompt(
            systemPrompt = "system",
            messages = listOf("user" to "Weather?"),
            pendingToolTurn = pending,
        )
        val parts = prompt.messages.flatMap { it.parts }
        val result = parts.filterIsInstance<MessagePart.Tool.Result>().single()
        val preservedReasoning = parts.filterIsInstance<MessagePart.Reasoning>().single()

        assertTrue(prompt.messages.contains(assistant))
        assertEquals("call-1", result.id)
        assertEquals("weather", result.tool)
        assertFalse(result.isError)
        assertEquals("encrypted-state", preservedReasoning.encrypted)
    }
}
