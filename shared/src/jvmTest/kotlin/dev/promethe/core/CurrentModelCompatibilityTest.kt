package dev.promethe.core

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import dev.promethe.api.XaiApiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurrentModelCompatibilityTest {
    @Test
    fun `current native provider models keep their provider identity`() {
        val anthropic = KoogLlmAdapter.resolveKnownModel("anthropic", "claude-sonnet-5")
        val google = KoogLlmAdapter.resolveKnownModel("google", "gemini-3.5-flash")
        val deepSeek = KoogLlmAdapter.resolveKnownModel("deepseek", "deepseek-v4-flash")

        assertEquals(LLMProvider.Anthropic, anthropic.provider)
        assertEquals(LLMProvider.Google, google.provider)
        assertEquals(LLMProvider.DeepSeek, deepSeek.provider)
        assertFalse(anthropic.supports(LLMCapability.Temperature))
        assertTrue(google.supports(LLMCapability.Tools))
        assertTrue(deepSeek.supports(LLMCapability.Tools))
    }

    @Test
    fun `gpt 5_6 keeps the OpenAI Responses endpoint`() {
        val model = KoogLlmAdapter.resolveKnownModel("openai", "gpt-5.6-terra")

        assertEquals(LLMProvider.OpenAI, model.provider)
        assertTrue(model.supports(LLMCapability.OpenAIEndpoint.Responses))
        assertTrue(model.supports(LLMCapability.Thinking))
    }

    @Test
    fun `kimi and xai use their certified OpenAI compatible endpoints`() {
        val kimi = KoogLlmAdapter.resolveKnownModel("kimi", "kimi-k3")
        val xaiResponses = KoogLlmAdapter.resolveKnownModel("xai", "grok-4.5")
        val xaiChat = KoogLlmAdapter.resolveKnownModel("xai", "grok-4.5", XaiApiMode.CHAT_COMPLETIONS)

        assertEquals(LLMProvider.OpenAI, kimi.provider)
        assertEquals(1_048_576, kimi.contextLength)
        assertTrue(kimi.supports(LLMCapability.OpenAIEndpoint.Completions))
        assertFalse(kimi.supports(LLMCapability.Temperature))
        assertEquals(500_000, xaiResponses.contextLength)
        assertTrue(xaiResponses.supports(LLMCapability.OpenAIEndpoint.Responses))
        assertTrue(xaiChat.supports(LLMCapability.OpenAIEndpoint.Completions))
    }

    @Test
    fun `legacy gemini alias resolves to Google and unknown providers fail closed`() {
        assertEquals(LLMProvider.Google, KoogLlmAdapter.resolveProvider("gemini"))
        assertFailsWith<IllegalArgumentException> { KoogLlmAdapter.resolveProvider("unknown-provider") }
    }

    @Test
    fun `proxy model aliases remain opaque chat completion ids`() {
        val liteLlm = KoogLlmAdapter.resolveKnownModel("litellm", "team/production-chat")
        val nim = KoogLlmAdapter.resolveKnownModel("nvidia", "account/custom-nim")

        assertEquals("team/production-chat", liteLlm.id)
        assertEquals("account/custom-nim", nim.id)
        assertTrue(liteLlm.supports(LLMCapability.OpenAIEndpoint.Completions))
        assertTrue(nim.supports(LLMCapability.OpenAIEndpoint.Completions))
    }
}
