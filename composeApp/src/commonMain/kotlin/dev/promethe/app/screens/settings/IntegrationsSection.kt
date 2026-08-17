package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@Composable
fun IntegrationsSection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    // Count configured integrations for status color
    val intConfiguredCount = listOf(
        state.intGithubToken,
        state.intNotionKey,
        state.intJiraToken,
        state.intTwilioSid,
        state.intEmailApiKey,
    ).count { it.isNotBlank() }

    val channelConfiguredCount = listOf(
        state.intTelegramBotToken,
        state.intDiscordBotToken,
        state.intSlackBotToken,
        state.intWhatsappPhoneId,
        state.intSignalRestUrl,
        state.intMatrixHomeserver,
    ).count { it.isNotBlank() }

    val totalIntegrations = intConfiguredCount + channelConfiguredCount

    SectionHeader(
        stringResource(Res.string.settings_integrations_title),
        state.integrationsExpanded,
        stringResource(Res.string.settings_integrations_description),
        statusColor = when {
            totalIntegrations >= 3 -> Color(0xFF22C55E)
            totalIntegrations >= 1 -> Color(0xFFF59E0B)
            else -> Color(0xFF9E9E9E)
        },
    ) { onUpdate { copy(integrationsExpanded = !integrationsExpanded) } }

    AnimatedVisibility(state.integrationsExpanded) {
        if (!state.integrationsLoaded) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = colors.primary)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(Res.string.settings_integrations_tokens_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )

                // ── Développement ──
                Text(stringResource(Res.string.settings_integrations_dev), style = MaterialTheme.typography.labelLarge, color = colors.primary)

                IntegrationCard(
                    title = "GitHub",
                    icon = Icons.Default.Code,
                    configured = state.intGithubToken.isNotBlank(),
                    colors = colors,
                    description = stringResource(Res.string.settings_integrations_github_desc),
                    accentColor = Color(0xFF238636),
                ) {
                    OutlinedTextField(
                        value = state.intGithubToken,
                        onValueChange = { onUpdate { copy(intGithubToken = it) } },
                        label = { Text(stringResource(Res.string.settings_integrations_token_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("settings_github_token"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                        supportingText = { Text(stringResource(Res.string.settings_integrations_github_hint)) },
                    )
                }

                IntegrationCard(
                    title = "Jira / Atlassian",
                    icon = Icons.Default.BugReport,
                    configured = state.intJiraUrl.isNotBlank() && state.intJiraToken.isNotBlank(),
                    colors = colors,
                    description = stringResource(Res.string.settings_integrations_jira_desc),
                    accentColor = Color(0xFF0052CC),
                ) {
                    OutlinedTextField(
                        value = state.intJiraUrl,
                        onValueChange = { onUpdate { copy(intJiraUrl = it) } },
                        label = { Text(stringResource(Res.string.settings_integrations_url_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_jira_url"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                        supportingText = { Text(stringResource(Res.string.settings_integrations_jira_url_hint)) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.intJiraEmail,
                            onValueChange = { onUpdate { copy(intJiraEmail = it) } },
                            label = { Text(stringResource(Res.string.settings_integrations_email_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("settings_jira_email"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                        )
                        OutlinedTextField(
                            value = state.intJiraToken,
                            onValueChange = { onUpdate { copy(intJiraToken = it) } },
                            label = { Text(stringResource(Res.string.settings_integrations_api_token_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f).testTag("settings_jira_token"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                        )
                    }
                }

                // ── Productivité ──
                Text(stringResource(Res.string.settings_integrations_productivity), style = MaterialTheme.typography.labelLarge, color = colors.primary)

                IntegrationCard(
                    title = "Notion",
                    icon = Icons.Default.Description,
                    configured = state.intNotionKey.isNotBlank(),
                    colors = colors,
                    description = stringResource(Res.string.settings_integrations_notion_desc),
                    accentColor = Color(0xFF000000),
                ) {
                    OutlinedTextField(
                        value = state.intNotionKey,
                        onValueChange = { onUpdate { copy(intNotionKey = it) } },
                        label = { Text(stringResource(Res.string.settings_integrations_api_key_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("settings_notion_key"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                    )
                }

                // ── Communication ──
                Text(stringResource(Res.string.settings_integrations_communication), style = MaterialTheme.typography.labelLarge, color = colors.primary)

                IntegrationCard(
                    title = "Email",
                    icon = Icons.Default.Email,
                    configured = state.intEmailApiKey.isNotBlank(),
                    colors = colors,
                    description = stringResource(Res.string.settings_integrations_email_desc),
                    accentColor = Color(0xFFEA4335),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.intEmailApiKey,
                            onValueChange = { onUpdate { copy(intEmailApiKey = it) } },
                            label = { Text(stringResource(Res.string.settings_integrations_api_key_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f).testTag("settings_email_api_key"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                        )
                        OutlinedTextField(
                            value = state.intEmailProvider,
                            onValueChange = { onUpdate { copy(intEmailProvider = it) } },
                            label = { Text(stringResource(Res.string.settings_integrations_provider_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.5f).testTag("settings_email_provider"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                            supportingText = { Text(stringResource(Res.string.settings_integrations_email_provider_hint)) },
                        )
                    }
                }

                IntegrationCard(
                    title = "Twilio",
                    icon = Icons.Default.Phone,
                    configured = state.intTwilioSid.isNotBlank() && state.intTwilioAuth.isNotBlank(),
                    colors = colors,
                    description = stringResource(Res.string.settings_integrations_twilio_desc),
                    accentColor = Color(0xFFF22F46),
                ) {
                    OutlinedTextField(
                        value = state.intTwilioSid,
                        onValueChange = { onUpdate { copy(intTwilioSid = it) } },
                        label = { Text(stringResource(Res.string.settings_integrations_account_sid_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_twilio_sid"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.intTwilioAuth,
                            onValueChange = { onUpdate { copy(intTwilioAuth = it) } },
                            label = { Text(stringResource(Res.string.settings_integrations_auth_token_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f).testTag("settings_twilio_auth"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                        )
                        OutlinedTextField(
                            value = state.intTwilioPhone,
                            onValueChange = { onUpdate { copy(intTwilioPhone = it) } },
                            label = { Text(stringResource(Res.string.settings_integrations_phone_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(0.6f).testTag("settings_twilio_phone"),
                            shape = RoundedCornerShape(12.dp),
                            colors = settingsFieldColors(colors),
                        )
                    }
                }

                IntegrationCard(
                    title = "Google Calendar",
                    icon = Icons.Default.CalendarMonth,
                    configured = state.intGoogleCalendarToken.isNotBlank(),
                    colors = colors,
                    description = stringResource(Res.string.settings_integrations_calendar_desc),
                    accentColor = Color(0xFF4285F4),
                ) {
                    OutlinedTextField(
                        value = state.intGoogleCalendarToken,
                        onValueChange = { onUpdate { copy(intGoogleCalendarToken = it) } },
                        label = { Text(stringResource(Res.string.settings_integrations_oauth_token_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("settings_calendar_token"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                    )
                }

                // ── Webhooks ──
                Text(stringResource(Res.string.settings_integrations_webhooks), style = MaterialTheme.typography.labelLarge, color = colors.primary)

                IntegrationCard(
                    title = "Promethe Webhook",
                    icon = Icons.Default.Webhook,
                    configured = state.intPrometheWebhookUrl.isNotBlank(),
                    colors = colors,
                    description = stringResource(Res.string.settings_integrations_promethe_webhook_desc),
                    accentColor = Color(0xFF6366F1),
                ) {
                    OutlinedTextField(
                        value = state.intPrometheWebhookUrl,
                        onValueChange = { onUpdate { copy(intPrometheWebhookUrl = it) } },
                        label = { Text(stringResource(Res.string.settings_integrations_webhook_url_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_promethe_webhook"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                        supportingText = { Text(stringResource(Res.string.settings_integrations_promethe_webhook_hint)) },
                    )
                }

                IntegrationCard(
                    title = "Discord Webhook",
                    icon = Icons.Default.Forum,
                    configured = state.intDiscordWebhookUrl.isNotBlank(),
                    colors = colors,
                    description = stringResource(Res.string.settings_integrations_discord_webhook_desc),
                    accentColor = Color(0xFF5865F2),
                ) {
                    OutlinedTextField(
                        value = state.intDiscordWebhookUrl,
                        onValueChange = { onUpdate { copy(intDiscordWebhookUrl = it) } },
                        label = { Text(stringResource(Res.string.settings_integrations_webhook_url_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("settings_discord_webhook"),
                        shape = RoundedCornerShape(12.dp),
                        colors = settingsFieldColors(colors),
                        supportingText = { Text(stringResource(Res.string.settings_integrations_discord_webhook_hint)) },
                    )
                }
            }
        }
    }

    // Messaging channels (also visible when integrations expanded)
    AnimatedVisibility(state.integrationsExpanded) {
        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.settings_integrations_messaging), style = MaterialTheme.typography.labelLarge, color = colors.primary)

            IntegrationCard(
                title = "Telegram",
                icon = Icons.AutoMirrored.Filled.Send,
                configured = state.intTelegramBotToken.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_integrations_telegram_desc),
                accentColor = Color(0xFF0088CC),
            ) {
                OutlinedTextField(
                    value = state.intTelegramBotToken,
                    onValueChange = { onUpdate { copy(intTelegramBotToken = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_bot_token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_telegram_token"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_integrations_telegram_hint)) },
                )
                OutlinedTextField(
                    value = state.intTelegramSecretToken,
                    onValueChange = { onUpdate { copy(intTelegramSecretToken = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_secret_token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_telegram_secret"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                )
            }

            IntegrationCard(
                title = "Discord",
                icon = Icons.Default.Forum,
                configured = state.intDiscordBotToken.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_integrations_discord_desc),
                accentColor = Color(0xFF5865F2),
            ) {
                OutlinedTextField(
                    value = state.intDiscordBotToken,
                    onValueChange = { onUpdate { copy(intDiscordBotToken = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_bot_token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_discord_token"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                )
                OutlinedTextField(
                    value = state.intDiscordPublicKey,
                    onValueChange = { onUpdate { copy(intDiscordPublicKey = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_app_public_key_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_discord_public_key"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                )
                OutlinedTextField(
                    value = state.intDiscordAllowedUserIds,
                    onValueChange = { onUpdate { copy(intDiscordAllowedUserIds = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_discord_allowed_users_label)) },
                    modifier = Modifier.fillMaxWidth().testTag("settings_discord_allowed_users"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = {
                        Text(stringResource(Res.string.settings_integrations_discord_allowed_users_hint))
                    },
                )
                OutlinedTextField(
                    value = state.intDiscordKnowledgeChannelIds,
                    onValueChange = { onUpdate { copy(intDiscordKnowledgeChannelIds = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_discord_knowledge_channels_label)) },
                    modifier = Modifier.fillMaxWidth().testTag("settings_discord_knowledge_channels"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = {
                        Text(stringResource(Res.string.settings_integrations_discord_knowledge_channels_hint))
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.settings_integrations_discord_message_content_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.intDiscordMessageContentEnabled,
                        onCheckedChange = { enabled ->
                            onUpdate { copy(intDiscordMessageContentEnabled = enabled) }
                        },
                        modifier = Modifier.testTag("settings_discord_message_content"),
                    )
                }
            }

            IntegrationCard(
                title = "Slack",
                icon = Icons.AutoMirrored.Filled.Chat,
                configured = state.intSlackBotToken.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_integrations_slack_desc),
                accentColor = Color(0xFF4A154B),
            ) {
                OutlinedTextField(
                    value = state.intSlackBotToken,
                    onValueChange = { onUpdate { copy(intSlackBotToken = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_bot_token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_slack_token"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_integrations_slack_hint)) },
                )
                OutlinedTextField(
                    value = state.intSlackSigningSecret,
                    onValueChange = { onUpdate { copy(intSlackSigningSecret = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_signing_secret_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_slack_signing_secret"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                )
            }

            IntegrationCard(
                title = "WhatsApp Cloud",
                icon = Icons.Default.Phone,
                configured = state.intWhatsappPhoneId.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_integrations_whatsapp_desc),
                accentColor = Color(0xFF25D366),
            ) {
                OutlinedTextField(
                    value = state.intWhatsappPhoneId,
                    onValueChange = { onUpdate { copy(intWhatsappPhoneId = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_phone_number_id_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_whatsapp_phone_id"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                )
                OutlinedTextField(
                    value = state.intWhatsappAccessToken,
                    onValueChange = { onUpdate { copy(intWhatsappAccessToken = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_access_token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_whatsapp_token"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                )
            }

            IntegrationCard(
                title = "Signal",
                icon = Icons.Default.Lock,
                configured = state.intSignalRestUrl.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_integrations_signal_desc),
                accentColor = Color(0xFF3A76F0),
            ) {
                OutlinedTextField(
                    value = state.intSignalRestUrl,
                    onValueChange = { onUpdate { copy(intSignalRestUrl = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_rest_api_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_signal_url"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_integrations_signal_url_hint)) },
                )
                OutlinedTextField(
                    value = state.intSignalPhoneNumber,
                    onValueChange = { onUpdate { copy(intSignalPhoneNumber = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_phone_number_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_signal_phone"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_integrations_signal_phone_hint)) },
                )
            }

            IntegrationCard(
                title = "Matrix",
                icon = Icons.Default.Language,
                configured = state.intMatrixHomeserver.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_integrations_matrix_desc),
                accentColor = Color(0xFF0DBD8B),
            ) {
                OutlinedTextField(
                    value = state.intMatrixHomeserver,
                    onValueChange = { onUpdate { copy(intMatrixHomeserver = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_homeserver_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_matrix_homeserver"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_integrations_matrix_url_hint)) },
                )
                OutlinedTextField(
                    value = state.intMatrixAccessToken,
                    onValueChange = { onUpdate { copy(intMatrixAccessToken = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_access_token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_matrix_token"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                )
            }
        }
    }
}
