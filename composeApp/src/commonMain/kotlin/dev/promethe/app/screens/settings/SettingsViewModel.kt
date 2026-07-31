package dev.promethe.app.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.api.ProviderRegistry
import dev.promethe.api.SandboxMode
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.app.config.AppCredentials
import dev.promethe.app.config.CredentialManager
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

internal sealed interface RemoteConfigMutation {
    val key: String

    data class Put(
        override val key: String,
        val value: String,
    ) : RemoteConfigMutation

    data class Delete(
        override val key: String,
    ) : RemoteConfigMutation
}

internal fun isMaskedRemoteValue(value: String): Boolean = value == "***" || value.endsWith("***")

internal fun planRemoteConfigMutations(
    desired: Map<String, String>,
    loaded: Map<String, String>,
): List<RemoteConfigMutation> =
    desired.mapNotNull { (key, value) ->
        when {
            isMaskedRemoteValue(value) -> null
            value.isBlank() && loaded.containsKey(key) -> RemoteConfigMutation.Delete(key)
            value.isBlank() -> null
            loaded[key] == value -> null
            else -> RemoteConfigMutation.Put(key, value)
        }
    }

internal fun mergeCredentialValuesForLocalSave(
    existing: Map<String, String>,
    edited: Map<String, String>,
): Map<String, String> =
    existing.toMutableMap().apply {
        edited.forEach { (key, value) ->
            when {
                isMaskedRemoteValue(value) -> Unit
                value.isBlank() -> remove(key)
                else -> put(key, value)
            }
        }
    }

private val providerSecretEnvKeys =
    linkedMapOf(
        "openai" to "OPENAI_API_KEY",
        "google" to "GOOGLE_API_KEY",
        "anthropic" to "ANTHROPIC_API_KEY",
        "deepseek" to "DEEPSEEK_API_KEY",
        "nvidia" to "NVIDIA_NIM_API_KEY",
        "litellm" to "LITELLM_API_KEY",
        "openrouter" to "OPENROUTER_API_KEY",
        "kimi" to "MOONSHOT_API_KEY",
        "xai" to "XAI_API_KEY",
    )

private val providerRuntimeEnvKeys =
    linkedMapOf(
        "litellm_url" to "LITELLM_BASE_URL",
        "kimi_url" to "MOONSHOT_BASE_URL",
        "xai_url" to "XAI_BASE_URL",
        "xai_api_mode" to "XAI_API_MODE",
    )

internal fun providerRemoteConfigValues(state: SettingsState): Map<String, String> {
    val selectedProvider = ProviderRegistry.providers.getOrNull(state.selectedProvider)?.key
    return buildMap {
        providerSecretEnvKeys.forEach { (provider, envKey) ->
            put(envKey, state.llmApiKeys[provider] ?: state.llmApiKey.takeIf { selectedProvider == provider }.orEmpty())
        }
        providerRuntimeEnvKeys.forEach { (stateKey, envKey) ->
            val value =
                state.llmApiKeys[stateKey]
                    ?: state.ollamaUrl.takeIf { stateKey == "litellm_url" && selectedProvider == "litellm" }
                    ?: ""
            put(envKey, value)
        }
    }
}

private fun integrationRemoteConfigValues(state: SettingsState): Map<String, String> =
    linkedMapOf(
        "GITHUB_TOKEN" to state.intGithubToken,
        "NOTION_API_KEY" to state.intNotionKey,
        "JIRA_URL" to state.intJiraUrl,
        "JIRA_EMAIL" to state.intJiraEmail,
        "JIRA_API_TOKEN" to state.intJiraToken,
        "TWILIO_ACCOUNT_SID" to state.intTwilioSid,
        "TWILIO_AUTH_TOKEN" to state.intTwilioAuth,
        "TWILIO_PHONE_NUMBER" to state.intTwilioPhone,
        "EMAIL_API_KEY" to state.intEmailApiKey,
        "EMAIL_PROVIDER" to state.intEmailProvider,
        "TELEGRAM_BOT_TOKEN" to state.intTelegramBotToken,
        "TELEGRAM_SECRET_TOKEN" to state.intTelegramSecretToken,
        "DISCORD_BOT_TOKEN" to state.intDiscordBotToken,
        "DISCORD_PUBLIC_KEY" to state.intDiscordPublicKey,
        "SLACK_BOT_TOKEN" to state.intSlackBotToken,
        "SLACK_SIGNING_SECRET" to state.intSlackSigningSecret,
        "WHATSAPP_PHONE_NUMBER_ID" to state.intWhatsappPhoneId,
        "WHATSAPP_ACCESS_TOKEN" to state.intWhatsappAccessToken,
        "SIGNAL_CLI_REST_URL" to state.intSignalRestUrl,
        "SIGNAL_PHONE_NUMBER" to state.intSignalPhoneNumber,
        "MATRIX_HOMESERVER_URL" to state.intMatrixHomeserver,
        "MATRIX_ACCESS_TOKEN" to state.intMatrixAccessToken,
        "GOOGLE_CALENDAR_TOKEN" to state.intGoogleCalendarToken,
        "EMAIL_FROM" to state.intEmailFrom,
        "PROMETHE_WEBHOOK_URL" to state.intPrometheWebhookUrl,
        "DISCORD_WEBHOOK_URL" to state.intDiscordWebhookUrl,
    )

private fun mediaRemoteConfigValues(state: SettingsState): Map<String, String> =
    linkedMapOf(
        "STABILITY_API_KEY" to state.mediaStabilityKey,
        "ELEVENLABS_API_KEY" to state.mediaElevenlabsKey,
        "REPLICATE_API_TOKEN" to state.mediaReplicateKey,
        "DEEPGRAM_API_KEY" to state.mediaDeepgramKey,
        "FAL_KEY" to state.mediaFalKey,
        "RUNWAY_API_KEY" to state.mediaRunwayKey,
    )

private fun extendedRemoteConfigValues(state: SettingsState): Map<String, String> =
    linkedMapOf(
        "TAVILY_API_KEY" to state.tavilyApiKey,
        "SEARXNG_URL" to state.searxngUrl,
        "TWITTER_BEARER_TOKEN" to state.twitterBearerToken,
        "BROWSER_BACKEND" to state.browserBackend,
        "BROWSERBASE_API_KEY" to state.browserbasApiKey,
        "BROWSERBASE_PROJECT_ID" to state.browserbaseProjectId,
        "BROWSER_CDP_HOST" to state.browserCdpHost,
        "BROWSER_CDP_PORT" to state.browserCdpPort,
        "HA_URL" to state.haUrl,
        "HA_TOKEN" to state.haToken,
    )

private fun voiceRemoteConfigValues(state: SettingsState): Map<String, String> =
    linkedMapOf(
        "voice_s2s_enabled" to state.voiceS2sEnabled.toString(),
        "voice_stt_enabled" to state.voiceSttEnabled.toString(),
        "voice_tts_enabled" to state.voiceTtsEnabled.toString(),
        "voice_translate_enabled" to state.voiceTranslateEnabled.toString(),
        "voice_s2s_provider" to state.voiceS2sProvider,
        "voice_s2s_model" to state.voiceS2sModel,
        "voice_s2s_voice" to state.voiceS2sVoice,
        "voice_tts_provider" to state.voiceTtsProvider,
        "voice_tts_model" to state.voiceTtsModel,
        "voice_tts_voice" to state.voiceTtsVoice,
        "voice_stt_provider" to state.voiceSttProvider,
        "voice_stt_model" to state.voiceSttModel,
        "voice_translate_provider" to state.voiceTranslateProvider,
        "voice_translate_model" to state.voiceTranslateModel,
        "voice_translate_target_lang" to state.voiceTranslateTargetLang,
        "voice_system_instructions" to state.voiceSystemInstructions,
    )

/**
 * ViewModel for the Settings screen.
 *
 * Manages all settings state, loading from [CredentialManager] + gateway API,
 * and persisting changes. Sections compose only the state slices they need.
 *
 * Injected via Koin: `koinViewModel<SettingsViewModel>()`
 */
class SettingsViewModel(
    private val client: PrometheClient?,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()
    private var loadedRemoteConfig: Map<String, String> = emptyMap()

    init {
        loadCredentials()
        loadProviderConfig()
        loadProfiles()
        loadIntegrations()
        loadMediaProviders()
        loadRagConfig()
        loadVoiceProviders()
        loadExtendedConfig()
        loadSandbox()
    }

    // ── State Mutation ──────────────────────────────────────────────────────

    /** Generic update — sections call this with a copy lambda. */
    fun update(transform: SettingsState.() -> SettingsState) {
        _state.update { it.transform() }
    }

    fun loadSandbox() {
        viewModelScope.launch {
            _state.update { it.copy(sandboxLoading = true, sandboxError = null) }
            try {
                val status = client?.getSandboxStatus()
                val profile = client?.getSandboxPermissionProfile()
                _state.update { it.copy(sandboxStatus = status, sandboxProfile = profile, sandboxLoading = false) }
            } catch (e: Exception) {
                logger.debug(e) { "Failed to load sandbox settings" }
                _state.update { it.copy(sandboxLoading = false, sandboxError = e.message ?: "Sandbox unavailable") }
            }
        }
    }

    fun runSandboxSelfTest() {
        viewModelScope.launch {
            _state.update { it.copy(sandboxSelfTestRunning = true, sandboxError = null) }
            try {
                val status = client?.runSandboxSelfTest()
                _state.update { it.copy(sandboxStatus = status, sandboxSelfTestRunning = false) }
            } catch (e: Exception) {
                logger.debug(e) { "Sandbox self-test failed" }
                _state.update { it.copy(sandboxSelfTestRunning = false, sandboxError = e.message ?: "Sandbox self-test failed") }
            }
        }
    }

    fun updateSandboxMode(mode: SandboxMode) {
        val current = _state.value.sandboxProfile ?: return
        viewModelScope.launch {
            _state.update { it.copy(sandboxProfile = current.copy(mode = mode), sandboxError = null) }
            try {
                val saved = client?.updateSandboxPermissionProfile(current.copy(mode = mode))
                if (saved != null) _state.update { it.copy(sandboxProfile = saved) }
            } catch (e: Exception) {
                logger.debug(e) { "Failed to update sandbox permission profile" }
                _state.update { it.copy(sandboxProfile = current, sandboxError = e.message ?: "Sandbox profile update failed") }
            }
        }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    private fun loadCredentials() {
        val creds = CredentialManager.load() ?: AppCredentials()
        _state.update { s ->
            s.copy(
                gatewayUrl = creds.gatewayUrl,
                selectedProvider = PROVIDERS.indexOf(creds.llmProvider).coerceAtLeast(0),
                llmApiKey = creds.llmApiKey,
                llmModel = creds.llmModel,
                llmApiKeys = creds.llmApiKeys,
                llmModels = creds.llmModels,
                ollamaUrl = creds.ollamaUrl,
                temperature = creds.temperature.toFloat(),
                maxTokens = creds.maxTokens.toString(),
                maxIterations = creds.maxIterations.toFloat(),
                memoryEnabled = creds.memoryEnabled,
                selectedMemoryProvider = MEMORY_PROVIDERS.indexOf(creds.memoryProvider).coerceAtLeast(0),
                selectedExecutionBackend = EXECUTION_BACKENDS.indexOf(creds.executionBackend).coerceAtLeast(0),
                executionTimeoutMs = creds.executionTimeoutMs.toFloat(),
                maxOutputBytes = creds.maxOutputBytes.toString(),
                dockerImage = creds.dockerImage,
                sshHost = creds.sshHost,
                sshUser = creds.sshUser,
                sshKeyPath = creds.sshKeyPath,
                sshPort = creds.sshPort.toString(),
                maxContextTokens = creds.maxContextTokens.toFloat(),
                compressionThreshold = creds.compressionThreshold.toFloat(),
                selectedTracingBackend = TRACING_BACKENDS.indexOf(creds.tracingBackend).coerceAtLeast(0),
                langfusePublicKey = creds.langfusePublicKey,
                langfuseSecretKey = creds.langfuseSecretKey,
                langfuseHost = creds.langfuseHost,
                otlpEndpoint = creds.otlpEndpoint,
                selectedApprovalMode = APPROVAL_MODES.indexOf(creds.approvalMode).coerceAtLeast(0),
                approvalTimeoutMs = creds.approvalTimeoutMs.toFloat(),
                gepaEnabled = creds.gepaEnabled,
                gepaIntervalMinutes = creds.gepaIntervalMinutes.toFloat(),
                gepaAutoApply = creds.gepaAutoApply,
                systemPrompt = creds.systemPrompt,
                selectedTheme = THEMES.indexOf(creds.theme).coerceAtLeast(0),
                honchoBaseUrl = creds.honchoBaseUrl,
                honchoApiKey = creds.honchoApiKey,
                tencentMemoryUrl = creds.tencentMemoryUrl,
                tencentMemoryServiceId = creds.tencentMemoryServiceId,
                tencentMemoryApiKey = creds.tencentMemoryApiKey,
                remoteUser = creds.remoteUser,
            )
        }
    }

    // ── Remote Access ─────────────────────────────────────────────────────

    fun saveRemoteAccess() {
        val s = _state.value
        if (s.remoteUser.isBlank() || s.remoteNewPassword.length < 12) return
        if (s.remoteNewPassword != s.remoteNewPasswordConfirm) return
        viewModelScope.launch {
            try {
                client?.setupRemoteOwner(user = s.remoteUser, password = s.remoteNewPassword)
                _state.update {
                    it.copy(
                        remoteNewPassword = "",
                        remoteNewPasswordConfirm = "",
                        remoteAccessSaved = true,
                        remoteAccessError = null,
                    )
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to save remote access credentials" }
                _state.update { it.copy(remoteAccessError = e.message, remoteAccessSaved = false) }
            }
        }
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            try {
                val loaded = client?.getAgentProfiles() ?: emptyList()
                _state.update { s ->
                    s.copy(
                        profiles = loaded,
                        activeProfileId = loaded.find { it.id == "promethe" }?.id
                            ?: loaded.firstOrNull()?.id ?: "promethe",
                        profilesLoading = false,
                    )
                }
            } catch (e: Exception) {
                logger.debug(e) { "Failed to load profiles" }
                _state.update { it.copy(profilesLoading = false) }
            }
        }
    }

    private fun loadProviderConfig() {
        viewModelScope.launch {
            try {
                val env = client?.getConfigEnv() ?: return@launch
                _state.update { state ->
                    val mergedKeys = state.llmApiKeys.toMutableMap()
                    providerSecretEnvKeys.forEach { (provider, envKey) ->
                        env[envKey]?.jsonPrimitive?.content?.let { remoteValue ->
                            val localValue = mergedKeys[provider].orEmpty()
                            mergedKeys[provider] =
                                if (isMaskedRemoteValue(remoteValue) && localValue.isNotBlank() && !isMaskedRemoteValue(localValue)) {
                                    localValue
                                } else {
                                    remoteValue
                                }
                        }
                    }
                    providerRuntimeEnvKeys.forEach { (stateKey, envKey) ->
                        env[envKey]?.jsonPrimitive?.content?.let { mergedKeys[stateKey] = it }
                    }
                    val selectedProvider = ProviderRegistry.providers.getOrNull(state.selectedProvider)?.key
                    state.copy(
                        llmApiKeys = mergedKeys,
                        llmApiKey = mergedKeys[selectedProvider] ?: state.llmApiKey,
                        ollamaUrl =
                            if (selectedProvider == "litellm") {
                                mergedKeys["litellm_url"] ?: state.ollamaUrl
                            } else {
                                state.ollamaUrl
                            },
                    )
                }
                recordLoadedRemoteConfig(env, providerRemoteConfigValues(_state.value).keys)
            } catch (e: Exception) {
                logger.debug(e) { "Failed to load provider env config" }
            }
        }
    }

    private fun loadIntegrations() {
        viewModelScope.launch {
            try {
                val env = client?.getConfigEnv() ?: emptyMap()
                _state.update { s ->
                    s.copy(
                        intGithubToken = env["GITHUB_TOKEN"]?.jsonPrimitive?.content ?: "",
                        intNotionKey = env["NOTION_API_KEY"]?.jsonPrimitive?.content ?: "",
                        intJiraUrl = env["JIRA_URL"]?.jsonPrimitive?.content ?: "",
                        intJiraEmail = env["JIRA_EMAIL"]?.jsonPrimitive?.content ?: "",
                        intJiraToken = env["JIRA_API_TOKEN"]?.jsonPrimitive?.content ?: "",
                        intTwilioSid = env["TWILIO_ACCOUNT_SID"]?.jsonPrimitive?.content ?: "",
                        intTwilioAuth = env["TWILIO_AUTH_TOKEN"]?.jsonPrimitive?.content ?: "",
                        intTwilioPhone = env["TWILIO_PHONE_NUMBER"]?.jsonPrimitive?.content ?: "",
                        intEmailApiKey = env["EMAIL_API_KEY"]?.jsonPrimitive?.content ?: "",
                        intEmailProvider = env["EMAIL_PROVIDER"]?.jsonPrimitive?.content ?: "",
                        intTelegramBotToken = env["TELEGRAM_BOT_TOKEN"]?.jsonPrimitive?.content ?: "",
                        intTelegramSecretToken = env["TELEGRAM_SECRET_TOKEN"]?.jsonPrimitive?.content ?: "",
                        intDiscordBotToken = env["DISCORD_BOT_TOKEN"]?.jsonPrimitive?.content ?: "",
                        intDiscordPublicKey = env["DISCORD_PUBLIC_KEY"]?.jsonPrimitive?.content ?: "",
                        intSlackBotToken = env["SLACK_BOT_TOKEN"]?.jsonPrimitive?.content ?: "",
                        intSlackSigningSecret = env["SLACK_SIGNING_SECRET"]?.jsonPrimitive?.content ?: "",
                        intWhatsappPhoneId = env["WHATSAPP_PHONE_NUMBER_ID"]?.jsonPrimitive?.content ?: "",
                        intWhatsappAccessToken = env["WHATSAPP_ACCESS_TOKEN"]?.jsonPrimitive?.content ?: "",
                        intSignalRestUrl = env["SIGNAL_CLI_REST_URL"]?.jsonPrimitive?.content ?: "",
                        intSignalPhoneNumber = env["SIGNAL_PHONE_NUMBER"]?.jsonPrimitive?.content ?: "",
                        intMatrixHomeserver = env["MATRIX_HOMESERVER_URL"]?.jsonPrimitive?.content ?: "",
                        intMatrixAccessToken = env["MATRIX_ACCESS_TOKEN"]?.jsonPrimitive?.content ?: "",
                        integrationsLoaded = true,
                        // Extended integrations (loaded here to avoid extra API call)
                        intGoogleCalendarToken = env["GOOGLE_CALENDAR_TOKEN"]?.jsonPrimitive?.content ?: "",
                        intEmailFrom = env["EMAIL_FROM"]?.jsonPrimitive?.content ?: "",
                        intPrometheWebhookUrl = env["PROMETHE_WEBHOOK_URL"]?.jsonPrimitive?.content ?: "",
                        intDiscordWebhookUrl = env["DISCORD_WEBHOOK_URL"]?.jsonPrimitive?.content ?: "",
                    )
                }
                recordLoadedRemoteConfig(env, integrationRemoteConfigValues(_state.value).keys)
            } catch (e: Exception) {
                logger.debug(e) { "Failed to load integration env config" }
                _state.update { it.copy(integrationsLoaded = true) }
            }
        }
    }

    private fun loadMediaProviders() {
        viewModelScope.launch {
            try {
                val env = client?.getConfigEnv() ?: emptyMap()
                _state.update { s ->
                    s.copy(
                        // Note: OpenAI/Google/Anthropic keys come from llmApiKeys — no duplication
                        mediaStabilityKey = env["STABILITY_API_KEY"]?.jsonPrimitive?.content ?: "",
                        mediaElevenlabsKey = env["ELEVENLABS_API_KEY"]?.jsonPrimitive?.content ?: "",
                        mediaReplicateKey = env["REPLICATE_API_TOKEN"]?.jsonPrimitive?.content ?: "",
                        mediaDeepgramKey = env["DEEPGRAM_API_KEY"]?.jsonPrimitive?.content ?: "",
                        mediaFalKey = env["FAL_KEY"]?.jsonPrimitive?.content ?: "",
                        mediaRunwayKey = env["RUNWAY_API_KEY"]?.jsonPrimitive?.content ?: "",
                        mediaProvidersLoaded = true,
                    )
                }
                recordLoadedRemoteConfig(env, mediaRemoteConfigValues(_state.value).keys)
            } catch (e: Exception) {
                logger.debug(e) { "Failed to load media provider env config" }
                _state.update { it.copy(mediaProvidersLoaded = true) }
            }
        }
    }

    private fun loadExtendedConfig() {
        viewModelScope.launch {
            try {
                val env = client?.getConfigEnv() ?: emptyMap()
                _state.update { s ->
                    s.copy(
                        // Web Search
                        tavilyApiKey = env["TAVILY_API_KEY"]?.jsonPrimitive?.content ?: "",
                        searxngUrl = env["SEARXNG_URL"]?.jsonPrimitive?.content ?: "",
                        twitterBearerToken = env["TWITTER_BEARER_TOKEN"]?.jsonPrimitive?.content ?: "",
                        // Browser
                        browserBackend = env["BROWSER_BACKEND"]?.jsonPrimitive?.content ?: "",
                        browserbasApiKey = env["BROWSERBASE_API_KEY"]?.jsonPrimitive?.content ?: "",
                        browserbaseProjectId = env["BROWSERBASE_PROJECT_ID"]?.jsonPrimitive?.content ?: "",
                        browserCdpHost = env["BROWSER_CDP_HOST"]?.jsonPrimitive?.content ?: "localhost",
                        browserCdpPort = env["BROWSER_CDP_PORT"]?.jsonPrimitive?.content ?: "",
                        // Home Assistant
                        haUrl = env["HA_URL"]?.jsonPrimitive?.content ?: "",
                        haToken = env["HA_TOKEN"]?.jsonPrimitive?.content ?: "",
                    )
                }
                recordLoadedRemoteConfig(env, extendedRemoteConfigValues(_state.value).keys)
            } catch (e: Exception) {
                logger.debug(e) { "Failed to load extended config" }
            }
        }
    }

    private suspend fun saveExtendedConfigToGateway(s: SettingsState) {
        saveConfigEnv(extendedRemoteConfigValues(s))
    }

    private fun loadRagConfig() {
        viewModelScope.launch {
            try {
                val ragConfig = client?.getRagConfig() ?: return@launch
                _state.update { s ->
                    s.copy(
                        ragEnabled = ragConfig.enabled,
                        ragEmbeddingProvider = ragConfig.embeddingProvider.name,
                        ragEmbeddingModel = ragConfig.embeddingModel,
                        ragEmbeddingBaseUrl = ragConfig.embeddingBaseUrl,
                        ragEmbeddingApiKey = ragConfig.embeddingApiKey,
                        ragEmbeddingDimensions = ragConfig.embeddingDimensions.toString(),
                        ragVectorStoreType = ragConfig.vectorStoreType.name,
                        ragVectorStoreUrl = ragConfig.vectorStoreUrl,
                        ragVectorStoreApiKey = ragConfig.vectorStoreApiKey,
                        ragVectorStorePath = ragConfig.vectorStorePath,
                        ragChunkSize = ragConfig.chunkSize.toFloat(),
                        ragChunkOverlap = ragConfig.chunkOverlap.toFloat(),
                    )
                }
            } catch (e: Exception) {
                logger.debug(e) { "Failed to load RAG config" }
            }
        }
    }

    // Context files loading is handled in the screen composable via LaunchedEffect,
    // because PrometheClient doesn't have a getContextFiles() method.

    // ── Save ─────────────────────────────────────────────────────────────────

    fun save(onCredentialsChanged: ((AppCredentials) -> Unit)? = null) {
        val s = _state.value
        val persisted = CredentialManager.load()
        val localApiKeys = mergeCredentialValuesForLocalSave(persisted?.llmApiKeys.orEmpty(), s.llmApiKeys)
        val localApiKey =
            if (isMaskedRemoteValue(s.llmApiKey)) {
                persisted?.llmApiKey.orEmpty()
            } else {
                s.llmApiKey
            }
        _state.update { it.copy(isSaving = true, saveError = null) }

        val creds = AppCredentials(
            apiKey = persisted?.apiKey.orEmpty(),
            llmProvider = PROVIDERS[s.selectedProvider],
            llmApiKey = localApiKey,
            llmModel = s.llmModel,
            llmApiKeys = localApiKeys,
            llmModels = s.llmModels,
            gatewayUrl = s.gatewayUrl,
            lastRemoteGatewayUrl = persisted?.lastRemoteGatewayUrl.orEmpty(),
            ollamaUrl = s.ollamaUrl,
            temperature = s.temperature.toDouble(),
            maxTokens = s.maxTokens.toIntOrNull() ?: 4096,
            maxIterations = s.maxIterations.toInt(),
            executionBackend = EXECUTION_BACKENDS[s.selectedExecutionBackend],
            executionTimeoutMs = s.executionTimeoutMs.toLong(),
            maxOutputBytes = s.maxOutputBytes.toIntOrNull() ?: 50_000,
            dockerImage = s.dockerImage,
            sshHost = s.sshHost,
            sshUser = s.sshUser,
            sshKeyPath = s.sshKeyPath,
            sshPort = s.sshPort.toIntOrNull() ?: 22,
            maxContextTokens = s.maxContextTokens.toInt(),
            compressionThreshold = s.compressionThreshold.toDouble(),
            memoryEnabled = s.memoryEnabled,
            memoryProvider = MEMORY_PROVIDERS[s.selectedMemoryProvider],
            honchoBaseUrl = s.honchoBaseUrl,
            honchoApiKey = s.honchoApiKey,
            tencentMemoryUrl = s.tencentMemoryUrl,
            tencentMemoryServiceId = s.tencentMemoryServiceId,
            tencentMemoryApiKey = s.tencentMemoryApiKey,
            tracingBackend = TRACING_BACKENDS[s.selectedTracingBackend],
            langfusePublicKey = s.langfusePublicKey,
            langfuseSecretKey = s.langfuseSecretKey,
            langfuseHost = s.langfuseHost,
            otlpEndpoint = s.otlpEndpoint,
            approvalMode = APPROVAL_MODES[s.selectedApprovalMode],
            approvalTimeoutMs = s.approvalTimeoutMs.toLong(),
            gepaEnabled = s.gepaEnabled,
            gepaIntervalMinutes = s.gepaIntervalMinutes.toLong(),
            gepaAutoApply = s.gepaAutoApply,
            systemPrompt = s.systemPrompt,
            theme = THEMES[s.selectedTheme],
            language = persisted?.language ?: "system",
            remoteUser = s.remoteUser,
        )

        viewModelScope.launch {
            try {
                CredentialManager.save(creds)
                val gatewayErrors = mutableListOf<String>()

                suspend fun saveSection(
                    label: String,
                    block: suspend () -> Unit,
                ) {
                    runCatching { block() }
                        .onFailure { error ->
                            logger.warn(error) { "Failed to save $label settings" }
                            gatewayErrors += "$label: ${error.message ?: "unknown error"}"
                        }
                }

                saveSection("integrations") { saveIntegrationsToGateway(s) }
                saveSection("providers and media") { saveMediaProvidersToGateway(s) }
                saveSection("RAG") { saveRagConfigToGateway(s) }
                saveSection("voice") { saveVoiceConfigToGateway(s) }
                saveSection("extended configuration") { saveExtendedConfigToGateway(s) }

                // Live reload credentials on the gateway
                saveSection("gateway reload") {
                    client?.reloadSettings()
                }
                if (gatewayErrors.isNotEmpty()) {
                    error(gatewayErrors.joinToString(separator = "\n"))
                }

                // No longer need to update a "default" profile — system agents use system provider
                logger.debug { "Credentials saved; system agents will pick up new provider/model on next request" }

                _state.update { it.copy(isSaving = false) }
                onCredentialsChanged?.invoke(creds)
            } catch (e: Exception) {
                logger.error(e) { "Failed to save settings" }
                _state.update { it.copy(isSaving = false, saveError = e.message) }
            }
        }
    }

    private suspend fun saveRagConfigToGateway(s: SettingsState) {
        client?.updateRagConfig(
            dev.promethe.api.RagConfig(
                enabled = s.ragEnabled,
                embeddingProvider = dev.promethe.api.EmbeddingProvider.valueOf(s.ragEmbeddingProvider),
                embeddingModel = s.ragEmbeddingModel,
                embeddingBaseUrl = s.ragEmbeddingBaseUrl,
                embeddingApiKey = s.ragEmbeddingApiKey,
                embeddingDimensions = s.ragEmbeddingDimensions.toIntOrNull() ?: 768,
                vectorStoreType = dev.promethe.api.VectorStoreType.valueOf(s.ragVectorStoreType),
                vectorStoreUrl = s.ragVectorStoreUrl,
                vectorStoreApiKey = s.ragVectorStoreApiKey,
                vectorStorePath = s.ragVectorStorePath,
                chunkSize = s.ragChunkSize.toInt(),
                chunkOverlap = s.ragChunkOverlap.toInt(),
            ),
        )
    }

    private suspend fun saveIntegrationsToGateway(s: SettingsState) {
        saveConfigEnv(integrationRemoteConfigValues(s))
    }

    private suspend fun saveMediaProvidersToGateway(s: SettingsState) {
        saveConfigEnv(providerRemoteConfigValues(s) + mediaRemoteConfigValues(s))
    }

    private fun loadVoiceProviders() {
        viewModelScope.launch {
            try {
                val env = client?.getConfigEnv() ?: emptyMap()
                // Load saved voice config
                _state.update { s ->
                    s.copy(
                        voiceS2sEnabled = env["voice_s2s_enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: s.voiceS2sEnabled,
                        voiceSttEnabled = env["voice_stt_enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: s.voiceSttEnabled,
                        voiceTtsEnabled = env["voice_tts_enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: s.voiceTtsEnabled,
                        voiceTranslateEnabled = env["voice_translate_enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: s.voiceTranslateEnabled,
                        voiceS2sProvider = env["voice_s2s_provider"]?.jsonPrimitive?.content ?: s.voiceS2sProvider,
                        voiceS2sModel = env["voice_s2s_model"]?.jsonPrimitive?.content ?: s.voiceS2sModel,
                        voiceS2sVoice = env["voice_s2s_voice"]?.jsonPrimitive?.content ?: s.voiceS2sVoice,
                        voiceTtsProvider = env["voice_tts_provider"]?.jsonPrimitive?.content ?: s.voiceTtsProvider,
                        voiceTtsModel = env["voice_tts_model"]?.jsonPrimitive?.content ?: s.voiceTtsModel,
                        voiceTtsVoice = env["voice_tts_voice"]?.jsonPrimitive?.content ?: s.voiceTtsVoice,
                        voiceSttProvider = env["voice_stt_provider"]?.jsonPrimitive?.content ?: s.voiceSttProvider,
                        voiceSttModel = env["voice_stt_model"]?.jsonPrimitive?.content ?: s.voiceSttModel,
                        voiceTranslateProvider = env["voice_translate_provider"]?.jsonPrimitive?.content ?: s.voiceTranslateProvider,
                        voiceTranslateModel = env["voice_translate_model"]?.jsonPrimitive?.content ?: s.voiceTranslateModel,
                        voiceTranslateTargetLang = env["voice_translate_target_lang"]?.jsonPrimitive?.content ?: s.voiceTranslateTargetLang,
                        voiceSystemInstructions = env["voice_system_instructions"]?.jsonPrimitive?.content ?: s.voiceSystemInstructions,
                    )
                }
                recordLoadedRemoteConfig(env, voiceRemoteConfigValues(_state.value).keys)
                // Fetch provider lists by capability
                fetchVoiceProviderLists()
                // Fetch models/voices for currently selected providers
                val st = _state.value
                if (st.voiceS2sProvider.isNotBlank()) fetchVoiceModelsAndVoices(st.voiceS2sProvider, "S2S")
                if (st.voiceTtsProvider.isNotBlank()) fetchVoiceModelsAndVoices(st.voiceTtsProvider, "TTS")
                if (st.voiceSttProvider.isNotBlank()) fetchVoiceModelsAndVoices(st.voiceSttProvider, "STT")
                if (st.voiceTranslateProvider.isNotBlank()) fetchVoiceModelsAndVoices(st.voiceTranslateProvider, "TRANSLATE")
                logger.debug { "Voice config loaded from gateway (dynamic)" }
            } catch (e: Exception) {
                logger.debug(e) { "Voice config not available (gateway may not be running)" }
            }
        }
    }

    private suspend fun fetchVoiceProviderLists() {
        try {
            val s2s = client?.getJsonList("/api/v1/voice/providers?cap=S2S") ?: emptyList()
            val tts = client?.getJsonList("/api/v1/voice/providers?cap=TTS") ?: emptyList()
            val stt = client?.getJsonList("/api/v1/voice/providers?cap=STT") ?: emptyList()
            val translate = client?.getJsonList("/api/v1/voice/providers?cap=TRANSLATE") ?: emptyList()
            logger.info { "Voice providers fetched — S2S: $s2s, TTS: $tts, STT: $stt, TRANSLATE: $translate" }
            _state.update { it.copy(availableS2sProviders = s2s, availableTtsProviders = tts, availableSttProviders = stt, availableTranslateProviders = translate) }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to fetch voice provider lists" }
        }
    }

    /** Fetch models + voices for a given provider and capability, updating the matching state lists. */
    fun fetchVoiceModelsAndVoices(
        provider: String,
        cap: String,
    ) {
        viewModelScope.launch {
            try {
                val models = client?.getJsonList("/api/v1/voice/models?provider=$provider&cap=$cap") ?: emptyList()
                val voices = client?.getJsonList("/api/v1/voice/voices?provider=$provider") ?: emptyList()
                // Also fetch voice infos with descriptions (for preview)
                val voiceInfos = client?.getVoiceInfoList("/api/v1/voice/voices?provider=$provider") ?: emptyList()
                _state.update { s ->
                    when (cap.uppercase()) {
                        "S2S" -> s.copy(voiceS2sModels = models, voiceS2sVoices = voices, voiceS2sVoiceInfos = voiceInfos)
                        "TTS" -> s.copy(voiceTtsModels = models, voiceTtsVoices = voices, voiceTtsVoiceInfos = voiceInfos)
                        "STT" -> s.copy(voiceSttModels = models)
                        "TRANSLATE" -> s.copy(voiceTranslateModels = models)
                        else -> s
                    }
                }
            } catch (e: Exception) {
                logger.debug(e) { "Failed to fetch models/voices for $provider/$cap" }
            }
        }
    }

    private suspend fun saveVoiceConfigToGateway(s: SettingsState) {
        saveConfigEnv(voiceRemoteConfigValues(s))
    }

    private suspend fun saveConfigEnv(values: Map<String, String>) {
        val activeClient = client ?: return
        val errors = mutableListOf<String>()
        for (mutation in planRemoteConfigMutations(values, loadedRemoteConfig)) {
            try {
                when (mutation) {
                    is RemoteConfigMutation.Put -> {
                        activeClient.putConfigEnv(mutation.key, mutation.value)
                        loadedRemoteConfig = loadedRemoteConfig + (mutation.key to mutation.value)
                    }

                    is RemoteConfigMutation.Delete -> {
                        activeClient.deleteConfigEnv(mutation.key)
                        loadedRemoteConfig = loadedRemoteConfig - mutation.key
                    }
                }
            } catch (e: Exception) {
                errors += "${mutation.key}: ${e.message ?: "unknown error"}"
            }
        }
        if (errors.isNotEmpty()) error(errors.joinToString(separator = "\n"))
    }

    private fun recordLoadedRemoteConfig(
        env: Map<String, kotlinx.serialization.json.JsonElement>,
        supportedKeys: Set<String>,
    ) {
        loadedRemoteConfig =
            loadedRemoteConfig + env
                .filterKeys(supportedKeys::contains)
                .mapValues { (_, value) -> value.jsonPrimitive.content }
    }

    /** Preview a voice — synthesize a short sample and play it via AudioEngine. */
    fun previewVoice(
        provider: String,
        voice: String,
    ) {
        if (_state.value.voicePreviewPlaying.isNotBlank()) return // Already playing
        _state.update { it.copy(voicePreviewPlaying = voice) }
        viewModelScope.launch {
            try {
                val result = client?.previewVoice(provider, voice) ?: run {
                    _state.update { it.copy(voicePreviewPlaying = "") }
                    return@launch
                }
                val (base64Data, sampleRate) = result
                // Play PCM audio via AudioEngine
                val engine = dev.promethe.app.audio.AudioEngine()
                engine.playChunk(base64Data, sampleRate)
                // Wait a moment for playback to finish, then release
                kotlinx.coroutines.delay(3000)
                engine.stopPlayback()
                engine.release()
            } catch (e: Exception) {
                logger.debug(e) { "Voice preview failed" }
            } finally {
                _state.update { it.copy(voicePreviewPlaying = "") }
            }
        }
    }

    companion object {
        /** Provider keys — derived from ProviderRegistry (single source of truth). */
        val PROVIDERS: List<String> = ProviderRegistry.providers.map { it.key }

        val MEMORY_PROVIDERS = listOf("embedded", "honcho", "tencent")
        val EXECUTION_BACKENDS = listOf("local", "docker", "ssh", "daytona")
        val TRACING_BACKENDS = listOf("console", "langfuse", "otlp")
        val APPROVAL_MODES = listOf("auto", "dangerous", "all")
        val THEMES = listOf("system", "dark", "light")
    }
}
