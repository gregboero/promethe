package dev.promethe.core

import dev.promethe.core.config.ConfigProvider

data class LlmSelection(
    val provider: String?,
    val model: String?,
)

/** Resolves one coherent provider/model pair for both profile seeding and runtime bootstrap. */
object LlmSelectionResolver {
    fun resolve(credentials: CredentialsStore.Credentials): LlmSelection {
        val storedProvider = credentials.llmProvider.trim().takeIf { it.isNotEmpty() }
        val storedModel =
            credentials.llmModel.trim().takeIf { it.isNotEmpty() }
                ?: storedProvider
                    ?.let(credentials.llmModels::get)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

        val envProvider = ConfigProvider.get().get("LLM_PROVIDER")?.trim()?.takeIf { it.isNotEmpty() }
        val envModel = ConfigProvider.get().get("LLM_MODEL")?.trim()?.takeIf { it.isNotEmpty() }

        // A persisted model means setup was completed in the UI and retains its
        // documented priority. A provider-only default is not a configuration:
        // allow an explicit Docker/CLI environment pair to replace it.
        val provider =
            if (storedProvider != null && storedModel != null) {
                storedProvider
            } else {
                envProvider ?: storedProvider
            }
        val model =
            if (provider == storedProvider && storedModel != null) {
                storedModel
            } else {
                envModel
                    ?: provider
                        ?.let(credentials.llmModels::get)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    ?: provider?.let(MultiModelRouter::getDefaultModelForProvider)?.takeIf { it.isNotBlank() }
            }

        return LlmSelection(provider, model)
    }
}
