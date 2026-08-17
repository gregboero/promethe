package dev.promethe.app.screens.settings

import dev.promethe.api.CapabilityDescriptor
import dev.promethe.api.SandboxPermissionProfile
import dev.promethe.api.SandboxStatus

/**
 * Centralized settings state model.
 *
 * Each group of related fields is represented by a nested data class
 * so that sections can receive only the subset of state they need.
 */
data class SettingsState(
    // ── Server ──
    val gatewayUrl: String = "http://localhost:8080",
    val gatewayUrlError: Boolean = false,
    // ── LLM Provider ──
    val selectedProvider: Int = 0,
    val llmApiKey: String = "",
    val llmModel: String = "",
    val llmApiKeys: Map<String, String> = emptyMap(),
    val llmModels: Map<String, String> = emptyMap(),
    val ollamaUrl: String = "http://localhost:11434",
    val keyVisible: Boolean = false,
    // ── Agent Parameters ──
    val temperature: Float = 0.7f,
    val maxTokens: String = "4096",
    val maxIterations: Float = 10f,
    // ── Memory ──
    val memoryEnabled: Boolean = false,
    val selectedMemoryProvider: Int = 0,
    val honchoBaseUrl: String = "",
    val honchoApiKey: String = "",
    val tencentMemoryUrl: String = "",
    val tencentMemoryServiceId: String = "",
    val tencentMemoryApiKey: String = "",
    // ── Execution Backend ──
    val selectedExecutionBackend: Int = 0,
    val executionTimeoutMs: Float = 30000f,
    val maxOutputBytes: String = "1048576",
    val dockerImage: String = "",
    val sshHost: String = "",
    val sshUser: String = "",
    val sshKeyPath: String = "",
    val sshPort: String = "22",
    // ── Context Window ──
    val maxContextTokens: Float = 128000f,
    val compressionThreshold: Float = 0.8f,
    // ── Observability ──
    val selectedTracingBackend: Int = 0,
    val langfusePublicKey: String = "",
    val langfuseSecretKey: String = "",
    val langfuseHost: String = "",
    val otlpEndpoint: String = "",
    // ── Security ──
    val selectedApprovalMode: Int = 0,
    val approvalTimeoutMs: Float = 30000f,
    // ── Remote Access ──
    val remoteUser: String = "",
    val remoteNewPassword: String = "",
    val remoteNewPasswordConfirm: String = "",
    val remoteAccessSaved: Boolean = false,
    val remoteAccessError: String? = null,
    // ── GEPA ──
    val gepaEnabled: Boolean = false,
    val gepaIntervalMinutes: Float = 60f,
    val gepaAutoApply: Boolean = false,
    // ── Personality ──
    val systemPrompt: String = "",
    // ── Theme ──
    val selectedTheme: Int = 0,
    // ── RAG ──
    val ragEnabled: Boolean = false,
    val ragEmbeddingProvider: String = "OLLAMA",
    val ragEmbeddingModel: String = "nomic-embed-text",
    val ragEmbeddingBaseUrl: String = "http://localhost:11434",
    val ragEmbeddingApiKey: String = "",
    val ragEmbeddingDimensions: String = "768",
    val ragVectorStoreType: String = "SQLITE_VEC",
    val ragVectorStoreUrl: String = "",
    val ragVectorStoreApiKey: String = "",
    val ragVectorStorePath: String = "",
    val ragChunkSize: Float = 512f,
    val ragChunkOverlap: Float = 50f,
    // ── Active Profile ──
    val profiles: List<dev.promethe.api.AgentProfile> = emptyList(),
    val activeProfileId: String = "developer",
    val profilesLoading: Boolean = true,
    // ── Integrations ──
    val intGithubToken: String = "",
    val intNotionKey: String = "",
    val intJiraUrl: String = "",
    val intJiraEmail: String = "",
    val intJiraToken: String = "",
    val intTwilioSid: String = "",
    val intTwilioAuth: String = "",
    val intTwilioPhone: String = "",
    val intEmailApiKey: String = "",
    val intEmailProvider: String = "",
    val intTelegramBotToken: String = "",
    val intTelegramSecretToken: String = "",
    val intDiscordBotToken: String = "",
    val intDiscordPublicKey: String = "",
    val intDiscordMessageContentEnabled: Boolean = false,
    val intDiscordAllowedUserIds: String = "",
    val intDiscordKnowledgeChannelIds: String = "",
    val intSlackBotToken: String = "",
    val intSlackSigningSecret: String = "",
    val intWhatsappPhoneId: String = "",
    val intWhatsappAccessToken: String = "",
    val intSignalRestUrl: String = "",
    val intSignalPhoneNumber: String = "",
    val intMatrixHomeserver: String = "",
    val intMatrixAccessToken: String = "",
    val integrationsLoaded: Boolean = false,
    // ── AI Media Providers (media-only, not shared with LLM) ──
    // Note: OpenAI, Google, Anthropic keys come from llmApiKeys — no duplication
    val mediaStabilityKey: String = "",
    val mediaElevenlabsKey: String = "",
    val mediaReplicateKey: String = "",
    val mediaDeepgramKey: String = "",
    val mediaFalKey: String = "",
    val mediaRunwayKey: String = "",
    val mediaProvidersLoaded: Boolean = false,
    // ── Web Search ──
    val tavilyApiKey: String = "",
    val searxngUrl: String = "",
    val twitterBearerToken: String = "",
    // ── Browser Automation ──
    val browserBackend: String = "", // "" | "browserbase" | "cdp"
    val browserbasApiKey: String = "",
    val browserbaseProjectId: String = "",
    val browserCdpHost: String = "localhost",
    val browserCdpPort: String = "",
    // ── Home Assistant ──
    val haUrl: String = "",
    val haToken: String = "",
    // ── Extended Integrations ──
    val intGoogleCalendarToken: String = "",
    val intEmailFrom: String = "",
    val intPrometheWebhookUrl: String = "",
    val intDiscordWebhookUrl: String = "",
    // ── Voice Assistant ──
    // Mode activation switches
    val voiceS2sEnabled: Boolean = true,
    val voiceSttEnabled: Boolean = false,
    val voiceTtsEnabled: Boolean = false,
    val voiceTranslateEnabled: Boolean = false,
    // S2S — conversation mode (audio ↔ audio)
    val voiceS2sProvider: String = "gemini_live",
    val voiceS2sModel: String = "",
    val voiceS2sVoice: String = "Puck",
    // TTS — read-aloud mode (text → audio)
    val voiceTtsProvider: String = "",
    val voiceTtsModel: String = "",
    val voiceTtsVoice: String = "",
    // STT — dictation mode (audio → text)
    val voiceSttProvider: String = "",
    val voiceSttModel: String = "",
    // TRANSLATE — real-time translation (audio language A → audio language B)
    val voiceTranslateProvider: String = "",
    val voiceTranslateModel: String = "",
    val voiceTranslateTargetLang: String = "fr",
    // Shared
    val voiceSystemInstructions: String = "",
    // Dynamic lists (loaded from /api/v1/voice/providers, models, voices)
    val availableS2sProviders: List<String> = emptyList(),
    val availableTtsProviders: List<String> = emptyList(),
    val availableSttProviders: List<String> = emptyList(),
    val availableTranslateProviders: List<String> = emptyList(),
    // Per-provider cascading lists (fetched when provider changes)
    val voiceS2sModels: List<String> = emptyList(),
    val voiceS2sVoices: List<String> = emptyList(),
    val voiceTtsModels: List<String> = emptyList(),
    val voiceTtsVoices: List<String> = emptyList(),
    val voiceSttModels: List<String> = emptyList(),
    val voiceTranslateModels: List<String> = emptyList(),
    // Voice info with descriptions (for preview UI)
    val voiceS2sVoiceInfos: List<Pair<String, String>> = emptyList(),
    val voiceTtsVoiceInfos: List<Pair<String, String>> = emptyList(),
    // Voice preview playback state
    val voicePreviewPlaying: String = "", // voiceId currently playing, "" if none
    // ── Context Files ──
    val contextFilesLoaded: Boolean = false,
    val prometheContent: String = "",
    val prometheExists: Boolean = false,
    val soulContent: String = "",
    val soulExists: Boolean = false,
    val agentsContent: String = "",
    val agentsExists: Boolean = false,
    val contextContent: String = "",
    val contextExists: Boolean = false,
    // ── Section expansion ──
    val serverExpanded: Boolean = true,
    val llmExpanded: Boolean = true,
    val agentExpanded: Boolean = false,
    val memoryExpanded: Boolean = false,
    val personalityExpanded: Boolean = false,
    val contextFilesExpanded: Boolean = false,
    val advancedExpanded: Boolean = false,
    val executionExpanded: Boolean = false,
    val contextWindowExpanded: Boolean = false,
    val observabilityExpanded: Boolean = false,
    val securityExpanded: Boolean = false,
    val remoteAccessExpanded: Boolean = false,
    val gepaSettingsExpanded: Boolean = false,
    val integrationsExpanded: Boolean = false,
    val voiceExpanded: Boolean = false,
    val profileExpanded: Boolean = true,
    val ragExpanded: Boolean = false,
    val webSearchExpanded: Boolean = false,
    val browserExpanded: Boolean = false,
    val haExpanded: Boolean = false,
    val sandboxExpanded: Boolean = false,
    val localCodingAgentsExpanded: Boolean = false,
    // ── Save status ──
    val isSaving: Boolean = false,
    val saveError: String? = null,
    // ── Sandbox ──
    val sandboxStatus: SandboxStatus? = null,
    val sandboxProfile: SandboxPermissionProfile? = null,
    val sandboxLoading: Boolean = false,
    val sandboxSelfTestRunning: Boolean = false,
    val sandboxSetupRunning: Boolean = false,
    val sandboxError: String? = null,
    // ── Local coding agents ──
    val localCodingAgents: List<CapabilityDescriptor> = emptyList(),
    val localCodingAgentsLoading: Boolean = false,
    val localCodingAgentsError: String? = null,
)
