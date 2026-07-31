package dev.promethe.core

import dev.promethe.core.config.ConfigProvider
import dev.promethe.core.config.SystemEnvConfigProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LlmSelectionResolverTest {
    @AfterTest
    fun cleanup() {
        ConfigProvider.clearOverride("LLM_PROVIDER")
        ConfigProvider.clearOverride("LLM_MODEL")
        ConfigProvider.initialize(SystemEnvConfigProvider)
    }

    @Test
    fun `explicit environment replaces an unconfigured default provider`() {
        ConfigProvider.setOverride("LLM_PROVIDER", "ollama")
        ConfigProvider.setOverride("LLM_MODEL", "llama3.2")

        val selection = LlmSelectionResolver.resolve(CredentialsStore.Credentials())

        assertEquals("ollama", selection.provider)
        assertEquals("llama3.2", selection.model)
    }

    @Test
    fun `completed persisted selection keeps priority over environment`() {
        ConfigProvider.setOverride("LLM_PROVIDER", "ollama")
        ConfigProvider.setOverride("LLM_MODEL", "llama3.2")

        val selection =
            LlmSelectionResolver.resolve(
                CredentialsStore.Credentials(
                    llmProvider = "openai",
                    llmModel = "gpt-5.6-sol",
                ),
            )

        assertEquals("openai", selection.provider)
        assertEquals("gpt-5.6-sol", selection.model)
    }

    @Test
    fun `provider-specific persisted model completes the stored selection`() {
        val selection =
            LlmSelectionResolver.resolve(
                CredentialsStore.Credentials(
                    llmProvider = "google",
                    llmModels = mapOf("google" to "gemini-3.5-flash"),
                ),
            )

        assertEquals("google", selection.provider)
        assertEquals("gemini-3.5-flash", selection.model)
    }
}
