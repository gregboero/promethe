package dev.promethe.app.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * ViewModel for the Setup wizard.
 *
 * Manages the 6-step first-run configuration flow, holding all form fields
 * in a single [SetupUiState] exposed via [state]. Each field has a
 * dedicated update function so the UI stays declarative.
 *
 * Business logic (saving env tokens) lives in [saveIntegrationTokens],
 * keeping the Composable layer pure.
 */
class SetupViewModel : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    // ── Navigation ───────────────────────────────────────────────────────

    fun nextStep() {
        _state.update { it.copy(step = (it.step + 1).coerceAtMost(5)) }
    }

    fun previousStep() {
        _state.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }
    }

    // ── Step 0: Provider ─────────────────────────────────────────────────

    fun selectProvider(provider: ProviderOption) {
        _state.update {
            it.copy(
                selectedProvider = provider,
                model = provider.defaultModel,
                localUrl = if (provider.defaultUrl.isNotBlank()) provider.defaultUrl else it.localUrl,
            )
        }
    }

    // ── Step 1: Credentials ──────────────────────────────────────────────

    fun updateApiKey(key: String) = _state.update { it.copy(apiKey = key) }

    fun updateModel(model: String) = _state.update { it.copy(model = model) }

    fun updateLocalUrl(url: String) = _state.update { it.copy(localUrl = url) }

    // ── Step 2: Remote access ────────────────────────────────────────────

    fun updateRemoteUser(user: String) = _state.update { it.copy(remoteUser = user) }

    fun updateRemotePassword(pw: String) = _state.update { it.copy(remotePassword = pw) }

    fun updateRemotePasswordConfirm(pw: String) = _state.update { it.copy(remotePasswordConfirm = pw) }

    // ── Step 3: Context files ────────────────────────────────────────────

    fun updateSoulMd(value: String) = _state.update { it.copy(soulMd = value) }

    fun updateAgentsMd(value: String) = _state.update { it.copy(agentsMd = value) }

    fun updatePrometheMd(value: String) = _state.update { it.copy(prometheMd = value) }

    // ── Step 4: Integrations ─────────────────────────────────────────────

    fun updateGithubToken(v: String) = _state.update { it.copy(githubToken = v) }

    fun updateNotionKey(v: String) = _state.update { it.copy(notionKey = v) }

    fun updateJiraUrl(v: String) = _state.update { it.copy(jiraUrl = v) }

    fun updateJiraEmail(v: String) = _state.update { it.copy(jiraEmail = v) }

    fun updateJiraToken(v: String) = _state.update { it.copy(jiraToken = v) }

    fun updateTwilioSid(v: String) = _state.update { it.copy(twilioSid = v) }

    fun updateTwilioAuth(v: String) = _state.update { it.copy(twilioAuth = v) }

    fun updateTwilioPhone(v: String) = _state.update { it.copy(twilioPhone = v) }

    fun updateEmailApiKey(v: String) = _state.update { it.copy(emailApiKey = v) }

    fun updateEmailProvider(v: String) = _state.update { it.copy(emailProvider = v) }

    // ── Step 4: Messaging channels ───────────────────────────────────────

    fun updateTelegramBotToken(v: String) = _state.update { it.copy(telegramBotToken = v) }

    fun updateTelegramSecretToken(v: String) = _state.update { it.copy(telegramSecretToken = v) }

    fun updateDiscordBotToken(v: String) = _state.update { it.copy(discordBotToken = v) }

    fun updateDiscordPublicKey(v: String) = _state.update { it.copy(discordPublicKey = v) }

    fun updateSlackBotToken(v: String) = _state.update { it.copy(slackBotToken = v) }

    fun updateSlackSigningSecret(v: String) = _state.update { it.copy(slackSigningSecret = v) }

    fun updateWhatsappPhoneId(v: String) = _state.update { it.copy(whatsappPhoneId = v) }

    fun updateWhatsappAccessToken(v: String) = _state.update { it.copy(whatsappAccessToken = v) }

    fun updateSignalRestUrl(v: String) = _state.update { it.copy(signalRestUrl = v) }

    fun updateSignalPhoneNumber(v: String) = _state.update { it.copy(signalPhoneNumber = v) }

    fun updateMatrixHomeserver(v: String) = _state.update { it.copy(matrixHomeserver = v) }

    fun updateMatrixAccessToken(v: String) = _state.update { it.copy(matrixAccessToken = v) }

    // ── Business Logic ───────────────────────────────────────────────────

    /**
     * Saves all non-empty integration tokens to the gateway's config/env
     * endpoint. Called just before completing setup.
     *
     * This is best-effort — failures are logged but don't block setup.
     */
    fun saveIntegrationTokens(apiKey: String) {
        val entries = _state.value.envEntries
        if (entries.isEmpty()) return

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val client = PrometheClient(apiKey = apiKey)
                for ((k, v) in entries) {
                    val body = buildJsonObject { put("value", v) }.toString()
                    client.putJson("/api/v1/config/env/$k", body)
                }
            } catch (e: Exception) {
                logger.debug(e) { "Failed to save integration tokens to env" }
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }
}
