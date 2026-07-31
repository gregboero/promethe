package dev.promethe.core

/** Canonical names for all provider secrets supported by Promethe. */
object ProviderSecretRegistry {
    data class Definition(
        val id: String,
        val envKey: String,
    )

    val definitions =
        listOf(
            Definition("openai", "OPENAI_API_KEY"),
            Definition("google", "GOOGLE_API_KEY"),
            Definition("anthropic", "ANTHROPIC_API_KEY"),
            Definition("deepseek", "DEEPSEEK_API_KEY"),
            Definition("nvidia", "NVIDIA_NIM_API_KEY"),
            Definition("litellm", "LITELLM_API_KEY"),
            Definition("openrouter", "OPENROUTER_API_KEY"),
            Definition("stability", "STABILITY_API_KEY"),
            Definition("replicate", "REPLICATE_API_TOKEN"),
            Definition("fal", "FAL_KEY"),
            Definition("runway", "RUNWAY_API_KEY"),
            Definition("elevenlabs", "ELEVENLABS_API_KEY"),
            Definition("deepgram", "DEEPGRAM_API_KEY"),
            Definition("cohere", "COHERE_API_KEY"),
            Definition("voyage", "VOYAGE_API_KEY"),
            Definition("mistral", "MISTRAL_API_KEY"),
            Definition("kimi", "MOONSHOT_API_KEY"),
            Definition("xai", "XAI_API_KEY"),
        )

    private val byEnvKey = definitions.associateBy { it.envKey }

    fun definitionForEnvKey(key: String): Definition? = byEnvKey[key.uppercase()]

    fun resolve(credentials: CredentialsStore.Credentials): Map<String, String> =
        definitions.mapNotNull { definition ->
            val value =
                credentials.providerSecrets[definition.id]
                    .orEmpty()
                    .ifBlank { credentials.llmApiKeys[definition.id].orEmpty() }
                    .ifBlank {
                        when (definition.id) {
                            "openai", "anthropic" -> {
                                if (credentials.llmProvider == definition.id) credentials.llmApiKey else ""
                            }

                            "google" -> {
                                if (credentials.llmProvider == "google") credentials.llmApiKey else credentials.googleAiKey
                            }

                            "stability" -> {
                                credentials.stabilityApiKey
                            }

                            "replicate" -> {
                                credentials.replicateApiToken
                            }

                            "fal" -> {
                                credentials.falKey
                            }

                            "runway" -> {
                                credentials.runwayApiKey
                            }

                            "elevenlabs" -> {
                                credentials.elevenlabsApiKey
                            }

                            "deepgram" -> {
                                credentials.deepgramApiKey
                            }

                            else -> {
                                ""
                            }
                        }
                    }
            definition.id.takeIf { value.isNotBlank() }?.let { it to value }
        }.toMap()
}

/** Closed registry for non-provider settings accepted by the runtime config API. */
object RuntimeConfigRegistry {
    val keys: Set<String> =
        setOf(
            "TAVILY_API_KEY",
            "SEARXNG_URL",
            "TWITTER_BEARER_TOKEN",
            "BROWSER_BACKEND",
            "BROWSERBASE_API_KEY",
            "BROWSERBASE_PROJECT_ID",
            "BROWSER_CDP_HOST",
            "BROWSER_CDP_PORT",
            "HA_URL",
            "HA_TOKEN",
            "GITHUB_TOKEN",
            "NOTION_API_KEY",
            "JIRA_URL",
            "JIRA_EMAIL",
            "JIRA_API_TOKEN",
            "TWILIO_ACCOUNT_SID",
            "TWILIO_AUTH_TOKEN",
            "TWILIO_PHONE_NUMBER",
            "EMAIL_API_KEY",
            "EMAIL_PROVIDER",
            "EMAIL_FROM",
            "TELEGRAM_BOT_TOKEN",
            "TELEGRAM_SECRET_TOKEN",
            "DISCORD_BOT_TOKEN",
            "DISCORD_PUBLIC_KEY",
            "DISCORD_WEBHOOK_URL",
            "SLACK_BOT_TOKEN",
            "SLACK_SIGNING_SECRET",
            "WHATSAPP_PHONE_NUMBER_ID",
            "WHATSAPP_ACCESS_TOKEN",
            "SIGNAL_CLI_REST_URL",
            "SIGNAL_PHONE_NUMBER",
            "MATRIX_HOMESERVER_URL",
            "MATRIX_ACCESS_TOKEN",
            "GOOGLE_CALENDAR_TOKEN",
            "PROMETHE_WEBHOOK_URL",
            "MOONSHOT_BASE_URL",
            "XAI_BASE_URL",
            "XAI_API_MODE",
            "LITELLM_BASE_URL",
            "voice_s2s_enabled",
            "voice_s2s_provider",
            "voice_s2s_model",
            "voice_s2s_voice",
            "voice_stt_enabled",
            "voice_stt_provider",
            "voice_stt_model",
            "voice_tts_enabled",
            "voice_tts_provider",
            "voice_tts_model",
            "voice_tts_voice",
            "voice_translate_enabled",
            "voice_translate_provider",
            "voice_translate_model",
            "voice_translate_target_lang",
            "voice_system_instructions",
        )

    private val canonicalByNormalized = keys.associateBy(String::uppercase)

    fun canonicalKey(key: String): String? = canonicalByNormalized[key.uppercase()]
}

/** Mutable snapshot shared by long-lived media tools and the capability router. */
object LiveProviderKeys : Map<String, String> {
    @Volatile
    private var snapshot: Map<String, String> = emptyMap()

    fun replace(values: Map<String, String>) {
        snapshot = values.filterValues { it.isNotBlank() }.toMap()
    }

    override val entries: Set<Map.Entry<String, String>> get() = snapshot.entries
    override val keys: Set<String> get() = snapshot.keys
    override val size: Int get() = snapshot.size
    override val values: Collection<String> get() = snapshot.values

    override fun containsKey(key: String): Boolean = snapshot.containsKey(key)

    override fun containsValue(value: String): Boolean = snapshot.containsValue(value)

    override fun get(key: String): String? = snapshot[key]

    override fun isEmpty(): Boolean = snapshot.isEmpty()
}
