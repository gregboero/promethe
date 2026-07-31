package dev.promethe.app.screens.setup.steps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.promethe.app.screens.setup.SetupViewModel
import dev.promethe.app.screens.setup.SetupUiState
import dev.promethe.app.screens.setup.components.IntegrationField
import dev.promethe.app.screens.setup.components.IntegrationSection
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

/**
 * Step 4: External service integrations and messaging channels.
 * All fields are optional — the user can configure them later.
 */
@Composable
fun IntegrationsStep(
    state: SetupUiState,
    viewModel: SetupViewModel,
) {
    val colors = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(Res.string.setup_integrations_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
        Text(
            stringResource(Res.string.setup_integrations_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))

        // ── GitHub ──
        IntegrationSection(title = "GitHub", icon = "\uD83D\uDC19") {
            IntegrationField(stringResource(Res.string.setup_integrations_github_token_label), state.githubToken, viewModel::updateGithubToken, "ghp_...", isSecret = true)
        }

        // ── Notion ──
        IntegrationSection(title = "Notion", icon = "\uD83D\uDCDD") {
            IntegrationField(stringResource(Res.string.setup_integrations_notion_token_label), state.notionKey, viewModel::updateNotionKey, "ntn_...", isSecret = true)
        }

        // ── Jira ──
        IntegrationSection(title = "Jira / Atlassian", icon = "\uD83D\uDCCB") {
            IntegrationField(stringResource(Res.string.setup_integrations_jira_url_label), state.jiraUrl, viewModel::updateJiraUrl, "https://company.atlassian.net")
            IntegrationField(stringResource(Res.string.setup_integrations_jira_email_label), state.jiraEmail, viewModel::updateJiraEmail, "you@company.com")
            IntegrationField(stringResource(Res.string.setup_integrations_jira_token_label), state.jiraToken, viewModel::updateJiraToken, "ATATT3...", isSecret = true)
        }

        // ── Email ──
        IntegrationSection(title = "Email (Resend / SendGrid / Mailgun)", icon = "✉\uFE0F") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("resend", "sendgrid", "mailgun").forEach { provider ->
                    FilterChip(
                        selected = state.emailProvider == provider,
                        onClick = { viewModel.updateEmailProvider(provider) },
                        label = { Text(provider.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            IntegrationField(stringResource(Res.string.setup_integrations_email_api_key_label), state.emailApiKey, viewModel::updateEmailApiKey, "re_...", isSecret = true)
        }

        // ── Twilio ──
        IntegrationSection(title = "Twilio (SMS / WhatsApp)", icon = "\uD83D\uDCF1") {
            IntegrationField(stringResource(Res.string.setup_integrations_twilio_sid_label), state.twilioSid, viewModel::updateTwilioSid, "AC...")
            IntegrationField(stringResource(Res.string.setup_integrations_twilio_auth_label), state.twilioAuth, viewModel::updateTwilioAuth, "...", isSecret = true)
            IntegrationField(stringResource(Res.string.setup_integrations_phone_label), state.twilioPhone, viewModel::updateTwilioPhone, "+1234567890")
        }

        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.setup_integrations_messaging_channels_title),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )

        // ── Telegram ──
        IntegrationSection(title = "Telegram", icon = "✈\uFE0F") {
            IntegrationField(stringResource(Res.string.setup_integrations_bot_token_label), state.telegramBotToken, viewModel::updateTelegramBotToken, "123456:ABC-DEF...", isSecret = true)
            IntegrationField(
                stringResource(Res.string.setup_integrations_telegram_secret_label),
                state.telegramSecretToken,
                viewModel::updateTelegramSecretToken,
                "webhook-secret",
                isSecret = true,
            )
        }

        // ── Discord ──
        IntegrationSection(title = "Discord", icon = "\uD83C\uDFAE") {
            IntegrationField(stringResource(Res.string.setup_integrations_bot_token_label), state.discordBotToken, viewModel::updateDiscordBotToken, "MTIz...", isSecret = true)
            IntegrationField(stringResource(Res.string.setup_integrations_discord_public_key_label), state.discordPublicKey, viewModel::updateDiscordPublicKey, "ed25519...")
        }

        // ── Slack ──
        IntegrationSection(title = "Slack", icon = "\uD83D\uDCAC") {
            IntegrationField(stringResource(Res.string.setup_integrations_bot_token_label), state.slackBotToken, viewModel::updateSlackBotToken, "xoxb-...", isSecret = true)
            IntegrationField(stringResource(Res.string.setup_integrations_slack_secret_label), state.slackSigningSecret, viewModel::updateSlackSigningSecret, "...", isSecret = true)
        }

        // ── WhatsApp ──
        IntegrationSection(title = "WhatsApp Cloud", icon = "\uD83D\uDCF2") {
            IntegrationField(stringResource(Res.string.setup_integrations_whatsapp_phone_id_label), state.whatsappPhoneId, viewModel::updateWhatsappPhoneId, "1234567890")
            IntegrationField(stringResource(Res.string.setup_integrations_access_token_label), state.whatsappAccessToken, viewModel::updateWhatsappAccessToken, "EAAx...", isSecret = true)
        }

        // ── Signal ──
        IntegrationSection(title = "Signal", icon = "\uD83D\uDD12") {
            IntegrationField(stringResource(Res.string.setup_integrations_signal_url_label), state.signalRestUrl, viewModel::updateSignalRestUrl, "http://localhost:8082")
            IntegrationField(stringResource(Res.string.setup_integrations_phone_label), state.signalPhoneNumber, viewModel::updateSignalPhoneNumber, "+1234567890")
        }

        // ── Matrix ──
        IntegrationSection(title = "Matrix", icon = "\uD83C\uDF10") {
            IntegrationField(stringResource(Res.string.setup_integrations_matrix_url_label), state.matrixHomeserver, viewModel::updateMatrixHomeserver, "https://matrix.org")
            IntegrationField(stringResource(Res.string.setup_integrations_access_token_label), state.matrixAccessToken, viewModel::updateMatrixAccessToken, "syt_...", isSecret = true)
        }
    }
}
