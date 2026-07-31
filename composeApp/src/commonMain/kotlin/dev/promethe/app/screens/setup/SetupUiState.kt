package dev.promethe.app.screens.setup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import dev.promethe.api.ProviderRegistry

// ── Provider Option ─────────────────────────────────────────────────────────

/**
 * Thin UI wrapper — maps core [ProviderRegistry.ProviderInfo] to an icon for display.
 */
data class ProviderOption(
    val info: ProviderRegistry.ProviderInfo,
    val icon: ImageVector,
) {
    val key get() = info.key
    val name get() = info.name
    val category get() = info.category.name // "LOCAL", "PROXY", "CLOUD"
    val defaultModel get() = info.defaultModel
    val models get() = info.fallbackModels
    val defaultUrl get() = info.defaultUrl
    val needsKey get() = info.needsKey
}

private val PROVIDER_ICONS =
    mapOf(
        "ollama" to Icons.Default.Computer,
        "litellm" to Icons.Default.Hub,
        "openai" to Icons.Default.AutoAwesome,
        "anthropic" to Icons.Default.Psychology,
        "google" to Icons.Default.Star,
        "openrouter" to Icons.Default.Cloud,
    )

/** All available providers, derived from [ProviderRegistry]. */
val PROVIDERS: List<ProviderOption> =
    ProviderRegistry.providers.map { info ->
        ProviderOption(
            info = info,
            icon = PROVIDER_ICONS[info.key] ?: Icons.Default.Cloud,
        )
    }

// ── UI State ────────────────────────────────────────────────────────────────

/**
 * Immutable state for the Setup wizard.
 *
 * Contains every field the user can fill across all 6 steps.
 * Derived properties ([canProceed], [activeIntegrations]) are computed
 * from the current field values so the UI never holds stale validation.
 */
data class SetupUiState(
    // Navigation
    val step: Int = 0,
    // Step 0 — Provider
    val selectedProvider: ProviderOption? = null,
    val apiKey: String = "",
    val model: String = "",
    val localUrl: String = "",
    // Step 2 — Remote access
    val remoteUser: String = "admin",
    val remotePassword: String = "",
    val remotePasswordConfirm: String = "",
    // Step 3 — Context files
    val soulMd: String = "",
    val agentsMd: String = "",
    val prometheMd: String = "",
    // Step 4 — Integrations
    val githubToken: String = "",
    val notionKey: String = "",
    val jiraUrl: String = "",
    val jiraEmail: String = "",
    val jiraToken: String = "",
    val twilioSid: String = "",
    val twilioAuth: String = "",
    val twilioPhone: String = "",
    val emailApiKey: String = "",
    val emailProvider: String = "resend",
    // Step 4 — Messaging channels
    val telegramBotToken: String = "",
    val telegramSecretToken: String = "",
    val discordBotToken: String = "",
    val discordPublicKey: String = "",
    val slackBotToken: String = "",
    val slackSigningSecret: String = "",
    val whatsappPhoneId: String = "",
    val whatsappAccessToken: String = "",
    val signalRestUrl: String = "",
    val signalPhoneNumber: String = "",
    val matrixHomeserver: String = "",
    val matrixAccessToken: String = "",
    // Transient
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    /** Whether the "Next" button should be enabled for the current step. */
    val canProceed: Boolean
        get() = when (step) {
            0 -> selectedProvider != null

            1 -> model.isNotBlank() && (selectedProvider?.needsKey != true || apiKey.isNotBlank())

            2 -> (remotePassword.isBlank() && remotePasswordConfirm.isBlank()) ||
                (remoteUser.isNotBlank() && remotePassword.length >= 12 && remotePassword == remotePasswordConfirm)

            else -> true
        }

    /** List of non-empty integration names for the confirmation screen. */
    val activeIntegrations: List<String>
        get() = buildList {
            if (githubToken.isNotBlank()) add("GitHub")
            if (notionKey.isNotBlank()) add("Notion")
            if (jiraUrl.isNotBlank()) add("Jira")
            if (twilioSid.isNotBlank()) add("Twilio")
            if (emailApiKey.isNotBlank()) add("Email")
            if (telegramBotToken.isNotBlank()) add("Telegram")
            if (discordBotToken.isNotBlank()) add("Discord")
            if (slackBotToken.isNotBlank()) add("Slack")
            if (whatsappPhoneId.isNotBlank()) add("WhatsApp")
            if (signalRestUrl.isNotBlank()) add("Signal")
            if (matrixHomeserver.isNotBlank()) add("Matrix")
        }

    /** Non-empty context files to save. */
    val contextFiles: Map<String, String>
        get() = buildMap {
            if (soulMd.isNotBlank()) put("SOUL.md", soulMd)
            if (agentsMd.isNotBlank()) put("AGENTS.md", agentsMd)
            if (prometheMd.isNotBlank()) put(".promethe.md", prometheMd)
        }

    /** All integration env entries to persist. */
    val envEntries: Map<String, String>
        get() = buildMap {
            if (githubToken.isNotBlank()) put("GITHUB_TOKEN", githubToken)
            if (notionKey.isNotBlank()) put("NOTION_API_KEY", notionKey)
            if (jiraUrl.isNotBlank()) put("JIRA_URL", jiraUrl)
            if (jiraEmail.isNotBlank()) put("JIRA_EMAIL", jiraEmail)
            if (jiraToken.isNotBlank()) put("JIRA_API_TOKEN", jiraToken)
            if (twilioSid.isNotBlank()) put("TWILIO_ACCOUNT_SID", twilioSid)
            if (twilioAuth.isNotBlank()) put("TWILIO_AUTH_TOKEN", twilioAuth)
            if (twilioPhone.isNotBlank()) put("TWILIO_PHONE_NUMBER", twilioPhone)
            if (emailApiKey.isNotBlank()) {
                put("EMAIL_API_KEY", emailApiKey)
                put("EMAIL_PROVIDER", emailProvider)
            }
            if (telegramBotToken.isNotBlank()) put("TELEGRAM_BOT_TOKEN", telegramBotToken)
            if (telegramSecretToken.isNotBlank()) put("TELEGRAM_SECRET_TOKEN", telegramSecretToken)
            if (discordBotToken.isNotBlank()) put("DISCORD_BOT_TOKEN", discordBotToken)
            if (discordPublicKey.isNotBlank()) put("DISCORD_PUBLIC_KEY", discordPublicKey)
            if (slackBotToken.isNotBlank()) put("SLACK_BOT_TOKEN", slackBotToken)
            if (slackSigningSecret.isNotBlank()) put("SLACK_SIGNING_SECRET", slackSigningSecret)
            if (whatsappPhoneId.isNotBlank()) put("WHATSAPP_PHONE_NUMBER_ID", whatsappPhoneId)
            if (whatsappAccessToken.isNotBlank()) put("WHATSAPP_ACCESS_TOKEN", whatsappAccessToken)
            if (signalRestUrl.isNotBlank()) put("SIGNAL_CLI_REST_URL", signalRestUrl)
            if (signalPhoneNumber.isNotBlank()) put("SIGNAL_PHONE_NUMBER", signalPhoneNumber)
            if (matrixHomeserver.isNotBlank()) put("MATRIX_HOMESERVER_URL", matrixHomeserver)
            if (matrixAccessToken.isNotBlank()) put("MATRIX_ACCESS_TOKEN", matrixAccessToken)
        }
}
