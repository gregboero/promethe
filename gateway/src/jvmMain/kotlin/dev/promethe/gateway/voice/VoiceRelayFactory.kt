package dev.promethe.gateway.voice

import dev.promethe.api.voice.VoiceSessionConfig
import dev.promethe.core.AgentExecutionPort
import dev.promethe.core.Log
import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.core.CredentialsStore
import dev.promethe.core.config.ConfigProvider
import io.ktor.client.*
import io.ktor.client.plugins.websocket.WebSockets

private val logger = Log.create("VoiceRelayFactory")

/**
 * VoiceRelayFactory — resolves the correct [VoiceRelay] implementation
 * based on the provider selected in [VoiceSessionConfig].
 *
 * For S2S providers (Gemini Live, OpenAI Realtime, Moshi):
 *   → Creates a direct audio relay to the provider's API.
 *
 * For non-S2S providers (STT + TTS combos):
 *   → Creates a pipeline relay: STT → AIAgent → TTS.
 *
 * The factory looks up the API key from multiple sources:
 * DB settings → credentials.json.
 */
object VoiceRelayFactory {
    /**
     * Create a [VoiceRelay] for the given provider configuration.
     *
     * @param providerId The provider ID from [VoiceSessionConfig.provider]
     * @param config Session configuration (passed to pipeline relay)
     * @param registry The voice provider registry for lookup
     * @param database Database for API key retrieval
     * @param agent Agent loop used by non-S2S voice pipelines
     */
    suspend fun create(
        providerId: String,
        config: VoiceSessionConfig,
        registry: VoiceProviderRegistry,
        database: PrometheDatabaseApi,
        executionService: AgentExecutionPort,
        sessionId: String,
    ): VoiceRelay {
        val provider = registry.getProvider(providerId)
            ?: error("Unknown voice provider: $providerId")

        val apiKey = resolveApiKey(provider.requiredSettingKey, database)
            ?: error("No API key configured for provider '$providerId' (setting: ${provider.requiredSettingKey})")

        logger.info { "Creating relay for provider '$providerId' (${provider.displayName})" }

        return when {
            VoiceCapability.S2S in provider.capabilities ||
                VoiceCapability.TRANSLATE in provider.capabilities -> createS2SRelay(provider, apiKey)

            else -> SttAgentTtsRelay(config, registry, database, executionService, sessionId)
        }
    }

    /**
     * Resolve an API key by checking DB settings, then credentials.json.
     */
    private suspend fun resolveApiKey(
        settingKey: String,
        database: PrometheDatabaseApi,
    ): String? {
        // 1. DB settings table
        database.getSetting(settingKey)?.takeIf { it.isNotBlank() }?.let { return it }

        // 2. Unified config (credentials.json + env vars)
        try {
            ConfigProvider.get().get(settingKey)?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) {
        }

        // 3. credentials.json llmApiKeys
        try {
            val creds = CredentialsStore.load()
            creds?.llmApiKeys?.forEach { (provider, key) ->
                if (key.isNotBlank() && "${provider.uppercase()}_API_KEY" == settingKey) {
                    return key
                }
            }
            // Also check dedicated keys
            if (settingKey == "ELEVENLABS_API_KEY") creds?.elevenlabsApiKey?.takeIf { it.isNotBlank() }?.let { return it }
            if (settingKey == "DEEPGRAM_API_KEY") creds?.deepgramApiKey?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) {
        }

        return null
    }

    private fun createS2SRelay(
        provider: VoiceProvider,
        apiKey: String,
    ): VoiceRelay {
        val httpClient = HttpClient {
            install(WebSockets)
        }

        return when (provider.id) {
            "gemini_live", "gemini_translate" -> GeminiLiveRelay(httpClient, apiKey)

            "openai_realtime", "openai_translate" -> OpenAIRealtimeRelay(httpClient, apiKey)

            // "moshi" -> MoshiRelay(apiKey) // Future
            else -> error("No S2S relay implementation for provider '${provider.id}'")
        }
    }
}
