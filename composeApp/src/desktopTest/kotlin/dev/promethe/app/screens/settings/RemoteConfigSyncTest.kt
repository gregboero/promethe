package dev.promethe.app.screens.settings

import dev.promethe.api.ProviderRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RemoteConfigSyncTest {
    @Test
    fun `blank loaded value is explicitly deleted`() {
        val mutations =
            planRemoteConfigMutations(
                desired = mapOf("OPENAI_API_KEY" to ""),
                loaded = mapOf("OPENAI_API_KEY" to "sk-a***"),
            )

        assertEquals(1, mutations.size)
        assertEquals("OPENAI_API_KEY", assertIs<RemoteConfigMutation.Delete>(mutations.single()).key)
    }

    @Test
    fun `blank value that was never loaded is not deleted`() {
        val mutations =
            planRemoteConfigMutations(
                desired = mapOf("OPENAI_API_KEY" to ""),
                loaded = emptyMap(),
            )

        assertTrue(mutations.isEmpty())
    }

    @Test
    fun `masked values are never written or deleted`() {
        val mutations =
            planRemoteConfigMutations(
                desired =
                    mapOf(
                        "OPENAI_API_KEY" to "***",
                        "ANTHROPIC_API_KEY" to "sk-a***",
                    ),
                loaded =
                    mapOf(
                        "OPENAI_API_KEY" to "***",
                        "ANTHROPIC_API_KEY" to "sk-a***",
                    ),
            )

        assertTrue(mutations.isEmpty())
    }

    @Test
    fun `changed real value is put while unchanged value is skipped`() {
        val mutations =
            planRemoteConfigMutations(
                desired =
                    linkedMapOf(
                        "OPENAI_API_KEY" to "new-openai",
                        "GOOGLE_API_KEY" to "same-google",
                    ),
                loaded =
                    mapOf(
                        "OPENAI_API_KEY" to "old-openai",
                        "GOOGLE_API_KEY" to "same-google",
                    ),
            )

        val put = assertIs<RemoteConfigMutation.Put>(mutations.single())
        assertEquals("OPENAI_API_KEY", put.key)
        assertEquals("new-openai", put.value)
    }

    @Test
    fun `masked remote values are never persisted locally`() {
        val merged =
            mergeCredentialValuesForLocalSave(
                existing = mapOf("openai" to "local-secret"),
                edited =
                    mapOf(
                        "openai" to "sk-r***",
                        "anthropic" to "***",
                        "google" to "new-google-secret",
                    ),
            )

        assertEquals("local-secret", merged["openai"])
        assertEquals(null, merged["anthropic"])
        assertEquals("new-google-secret", merged["google"])
    }

    @Test
    fun `provider synchronization covers every direct provider and runtime endpoint`() {
        val providerKeys =
            ProviderRegistry.providers
                .map { it.key }
                .filterNot { it == "ollama" }
                .associateWith { "$it-secret" }
        val state =
            SettingsState(
                selectedProvider = ProviderRegistry.providers.indexOfFirst { it.key == "litellm" },
                llmApiKeys =
                    providerKeys +
                        mapOf(
                            "litellm_url" to "https://litellm.example",
                            "kimi_url" to "https://kimi.example",
                            "xai_url" to "https://xai.example",
                            "xai_api_mode" to "responses",
                        ),
            )

        val values = providerRemoteConfigValues(state)

        assertEquals("openai-secret", values["OPENAI_API_KEY"])
        assertEquals("google-secret", values["GOOGLE_API_KEY"])
        assertEquals("anthropic-secret", values["ANTHROPIC_API_KEY"])
        assertEquals("deepseek-secret", values["DEEPSEEK_API_KEY"])
        assertEquals("nvidia-secret", values["NVIDIA_NIM_API_KEY"])
        assertEquals("litellm-secret", values["LITELLM_API_KEY"])
        assertEquals("openrouter-secret", values["OPENROUTER_API_KEY"])
        assertEquals("kimi-secret", values["MOONSHOT_API_KEY"])
        assertEquals("xai-secret", values["XAI_API_KEY"])
        assertEquals("https://litellm.example", values["LITELLM_BASE_URL"])
        assertEquals("https://kimi.example", values["MOONSHOT_BASE_URL"])
        assertEquals("https://xai.example", values["XAI_BASE_URL"])
        assertEquals("responses", values["XAI_API_MODE"])
    }
}
