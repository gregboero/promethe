package dev.promethe.app.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.promethe.api.ProviderRegistry
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

// ── Constants — single source of truth from ProviderRegistry ────────────────
// Shared with Setup (SetupUiState) and Gateway (PrometheA2AExecutor)

internal val PROVIDERS: List<String> = ProviderRegistry.providers.map { it.key }

internal val PROVIDER_LABELS: Map<String, String> =
    ProviderRegistry.providers.associate { it.key to it.name }

internal fun shouldShowProviderUrl(providerKey: String): Boolean =
    ProviderRegistry.get(providerKey)?.let { provider ->
        provider.category != ProviderRegistry.ProviderCategory.CLOUD && provider.defaultUrl.isNotEmpty()
    } == true

// UI-only accent colors — Compose concern, not in :api
private val PROVIDER_ACCENT_COLORS = mapOf(
    "openai" to Color(0xFF10A37F),
    "anthropic" to Color(0xFFD4A574),
    "google" to Color(0xFF4285F4),
    "deepseek" to Color(0xFF4FC3F7),
    "openrouter" to Color(0xFF6366F1),
    "litellm" to Color(0xFFEC4899),
    "nvidia" to Color(0xFF76B900),
    "ollama" to Color(0xFFE5E7EB),
)

// ── Composable ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LlmSection(
    state: SettingsState,
    onUpdate: (SettingsState.() -> SettingsState) -> Unit,
    client: PrometheClient?,
    scope: CoroutineScope,
    onFetchVoice: (provider: String, cap: String) -> Unit = { _, _ -> },
    onPreviewVoice: (provider: String, voice: String) -> Unit = { _, _ -> },
) {
    val colors = MaterialTheme.colorScheme

    val currentProviderKey = PROVIDERS.getOrNull(state.selectedProvider) ?: "openai"
    val currentApiKey = state.llmApiKeys[currentProviderKey] ?: state.llmApiKey
    val currentModel = state.llmModels[currentProviderKey] ?: state.llmModel

    val statusColor =
        if (currentApiKey.isNotBlank() || ProviderRegistry.get(currentProviderKey)?.needsKey == false) {
            Color(0xFF22C55E)
        } else {
            Color(0xFF9E9E9E)
        }

    SectionHeader(stringResource(Res.string.settings_llm_title), state.llmExpanded, stringResource(Res.string.settings_llm_description), statusColor = statusColor) {
        onUpdate { copy(llmExpanded = !llmExpanded) }
    }

    // Pre-resolve strings for use in semantics{} block (not @Composable)
    val selectedText = stringResource(Res.string.settings_llm_selected)
    val apiKeyRequiredText = stringResource(Res.string.settings_llm_api_key_required)
    val localUrlText = stringResource(Res.string.settings_llm_local_url)
    val hideKeyText = stringResource(Res.string.settings_llm_hide_api_key)
    val showKeyText = stringResource(Res.string.settings_llm_show_api_key)
    val testingText = stringResource(Res.string.settings_llm_testing)
    val testConnectionText = stringResource(Res.string.settings_llm_test_connection)
    val connectionSuccessText = stringResource(Res.string.settings_llm_connection_success)
    val providerUnreachableText = stringResource(Res.string.settings_llm_provider_unreachable)
    val noProviderText = stringResource(Res.string.settings_llm_voice_no_provider)
    val noVoiceText = stringResource(Res.string.settings_llm_voice_no_voice)

    AnimatedVisibility(state.llmExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // ── Provider cards grid ───────────────────────────────────────────
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ProviderRegistry.providers.forEach { info ->
                    val providerIndex = PROVIDERS.indexOf(info.key)
                    val accentColor = PROVIDER_ACCENT_COLORS[info.key] ?: colors.primary
                    val isSelected = state.selectedProvider == providerIndex
                    val providerA11y = "Provider ${info.name}${if (isSelected) " - $selectedText" else ""}"
                    Surface(
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .testTag("settings_provider_${info.key}")
                            .semantics {
                                role = Role.Button
                                contentDescription = providerA11y
                            }
                            .clickable {
                                onUpdate {
                                    copy(
                                        selectedProvider = providerIndex,
                                        llmApiKey = llmApiKeys[info.key] ?: "",
                                        llmModel = llmModels[info.key] ?: "",
                                    )
                                }
                            }
                            .then(
                                if (isSelected) {
                                    Modifier.border(2.dp, accentColor, RoundedCornerShape(14.dp))
                                } else {
                                    Modifier
                                },
                            ),
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) {
                            accentColor.copy(alpha = 0.08f)
                        } else {
                            colors.surfaceVariant.copy(alpha = 0.4f)
                        },
                        tonalElevation = if (isSelected) 2.dp else 0.dp,
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                info.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) accentColor else colors.onSurface,
                            )
                            Text(
                                info.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                            )
                            Text(
                                if (info.needsKey) apiKeyRequiredText else localUrlText,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }

            val currentProvider = PROVIDERS.getOrElse(state.selectedProvider) { "openai" }

            // ── API Key field (providers with needsKey = true) ────────────────
            if (ProviderRegistry.get(currentProvider)?.needsKey != false) {
                var keyVisible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = currentApiKey,
                    onValueChange = { newKey ->
                        onUpdate {
                            copy(
                                llmApiKey = newKey,
                                llmApiKeys = llmApiKeys + (currentProviderKey to newKey),
                            )
                        }
                    },
                    label = { Text(stringResource(Res.string.settings_llm_api_key_label, PROVIDER_LABELS[currentProvider] ?: "")) },
                    singleLine = true,
                    visualTransformation =
                        if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (keyVisible) hideKeyText else showKeyText,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("settings_llm_api_key"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                )
            }

            // ── URL field (LOCAL + PROXY providers) ───────────────────────────
            if (shouldShowProviderUrl(currentProvider)) {
                OutlinedTextField(
                    value = state.ollamaUrl,
                    onValueChange = { onUpdate { copy(ollamaUrl = it) } },
                    label = { Text(stringResource(Res.string.settings_llm_url_label, PROVIDER_LABELS[currentProvider] ?: "")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_ollama_url"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                )
            }

            // ── Test connexion ────────────────────────────────────────────────
            var connectionTestState by remember {
                mutableStateOf<ConnectionTestState>(ConnectionTestState.Idle)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        connectionTestState = ConnectionTestState.Testing
                        scope.launch {
                            try {
                                val body = buildString {
                                    append("{")
                                    append("\"provider\":\"$currentProviderKey\",")
                                    append("\"apiKey\":\"$currentApiKey\",")
                                    append("\"model\":\"$currentModel\"")
                                    append("}")
                                }
                                val response = client?.postJson("/api/v1/settings/test-llm", body)
                                val ok = response?.contains("\"ok\":true") == true
                                connectionTestState = if (ok) {
                                    ConnectionTestState.Success
                                } else {
                                    ConnectionTestState.Error(providerUnreachableText)
                                }
                            } catch (e: Exception) {
                                connectionTestState = ConnectionTestState.Error(e.message ?: "Error")
                            }
                            delay(5000)
                            connectionTestState = ConnectionTestState.Idle
                        }
                    },
                    enabled = connectionTestState !is ConnectionTestState.Testing,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("settings_test_connection"),
                ) {
                    when (connectionTestState) {
                        is ConnectionTestState.Testing -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = colors.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(testingText)
                        }

                        else -> {
                            Icon(
                                Icons.Default.Wifi,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(testConnectionText)
                        }
                    }
                }

                when (val testState = connectionTestState) {
                    is ConnectionTestState.Success -> {
                        Text(
                            connectionSuccessText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF22C55E),
                        )
                    }

                    is ConnectionTestState.Error -> {
                        Text(
                            "❌ ${testState.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error,
                            maxLines = 1,
                        )
                    }

                    else -> {}
                }
            }

            // ── Media & Audio providers (media-only keys) ──────────────────
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(Res.string.settings_llm_media_audio),
                style = MaterialTheme.typography.titleSmall,
                color = colors.primary,
            )
            Text(
                stringResource(Res.string.settings_llm_media_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )

            IntegrationCard(
                title = "Stability AI",
                icon = Icons.Default.Image,
                configured = state.mediaStabilityKey.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_llm_stability_desc),
                accentColor = Color(0xFFFF6B35),
            ) {
                OutlinedTextField(
                    value = state.mediaStabilityKey,
                    onValueChange = { onUpdate { copy(mediaStabilityKey = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_api_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_media_stability_key"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_llm_stability_hint)) },
                )
            }

            IntegrationCard(
                title = "ElevenLabs",
                icon = Icons.Default.Mic,
                configured = state.mediaElevenlabsKey.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_llm_elevenlabs_desc),
                accentColor = Color(0xFF6366F1),
            ) {
                OutlinedTextField(
                    value = state.mediaElevenlabsKey,
                    onValueChange = { onUpdate { copy(mediaElevenlabsKey = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_api_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_media_elevenlabs_key"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_llm_elevenlabs_hint)) },
                )
            }

            IntegrationCard(
                title = "Deepgram",
                icon = Icons.Default.Mic,
                configured = state.mediaDeepgramKey.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_llm_deepgram_desc),
                accentColor = Color(0xFF13EF93),
            ) {
                OutlinedTextField(
                    value = state.mediaDeepgramKey,
                    onValueChange = { onUpdate { copy(mediaDeepgramKey = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_api_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_media_deepgram_key"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_llm_deepgram_hint)) },
                )
            }

            IntegrationCard(
                title = "Replicate",
                icon = Icons.Default.Videocam,
                configured = state.mediaReplicateKey.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_llm_replicate_desc),
                accentColor = Color(0xFFFF4500),
            ) {
                OutlinedTextField(
                    value = state.mediaReplicateKey,
                    onValueChange = { onUpdate { copy(mediaReplicateKey = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_api_token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_media_replicate_key"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_llm_replicate_hint)) },
                )
            }

            IntegrationCard(
                title = "FAL",
                icon = Icons.Default.Videocam,
                configured = state.mediaFalKey.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_llm_fal_desc),
                accentColor = Color(0xFFF97316),
            ) {
                OutlinedTextField(
                    value = state.mediaFalKey,
                    onValueChange = { onUpdate { copy(mediaFalKey = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_api_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_media_fal_key"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_llm_fal_hint)) },
                )
            }

            IntegrationCard(
                title = "Runway",
                icon = Icons.Default.Videocam,
                configured = state.mediaRunwayKey.isNotBlank(),
                colors = colors,
                description = stringResource(Res.string.settings_llm_runway_desc),
                accentColor = Color(0xFF000000),
            ) {
                OutlinedTextField(
                    value = state.mediaRunwayKey,
                    onValueChange = { onUpdate { copy(mediaRunwayKey = it) } },
                    label = { Text(stringResource(Res.string.settings_integrations_api_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_media_runway_key"),
                    shape = RoundedCornerShape(12.dp),
                    colors = settingsFieldColors(colors),
                    supportingText = { Text(stringResource(Res.string.settings_llm_runway_hint)) },
                )
            }

            IntegrationCard(
                title = "Veo 3 (Google)",
                icon = Icons.Default.Videocam,
                configured = false,
                available = false,
                colors = colors,
                description = stringResource(Res.string.settings_llm_veo_unavailable_desc),
                accentColor = colors.outline,
            ) {}

            // ── Voice Assistant ──────────────────────────────────────────────
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(Res.string.settings_llm_voice_title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.primary,
            )
            Text(
                stringResource(Res.string.settings_llm_voice_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )

            // ── S2S — Agent vocal ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(Res.string.settings_llm_voice_s2s_title), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                    Text(stringResource(Res.string.settings_llm_voice_s2s_desc), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Switch(
                    checked = state.voiceS2sEnabled,
                    onCheckedChange = { onUpdate { copy(voiceS2sEnabled = it) } },
                    modifier = Modifier.testTag("settings_voice_s2s_enabled"),
                )
            }
            VoiceDropdown(
                label = stringResource(Res.string.settings_llm_voice_provider_label),
                value = state.voiceS2sProvider,
                options = state.availableS2sProviders,
                onValueChange = {
                    onUpdate { copy(voiceS2sProvider = it, voiceS2sModel = "", voiceS2sVoice = "") }
                    onFetchVoice(it, "S2S")
                },
                testTag = "settings_voice_s2s_provider",
                enabled = state.voiceS2sEnabled,
                noOptionsText = noProviderText,
            )
            // Cascade: fetch models/voices when provider is set
            LaunchedEffect(state.voiceS2sProvider) {
                if (state.voiceS2sProvider.isNotBlank()) onFetchVoice(state.voiceS2sProvider, "S2S")
            }
            if (state.voiceS2sModels.isNotEmpty()) {
                VoiceDropdown(stringResource(Res.string.settings_llm_voice_model_label), state.voiceS2sModel, state.voiceS2sModels, { onUpdate { copy(voiceS2sModel = it) } }, "settings_voice_s2s_model", enabled = state.voiceS2sEnabled, noOptionsText = noProviderText)
            }
            if (state.voiceS2sVoices.isNotEmpty()) {
                VoiceDropdownWithPreview(
                    label = stringResource(Res.string.settings_llm_voice_voice_label),
                    value = state.voiceS2sVoice,
                    voiceInfos = state.voiceS2sVoiceInfos,
                    onValueChange = { onUpdate { copy(voiceS2sVoice = it) } },
                    onPreview = { onPreviewVoice(state.voiceS2sProvider, it) },
                    previewPlaying = state.voicePreviewPlaying,
                    testTag = "settings_voice_s2s_voice",
                    enabled = state.voiceS2sEnabled,
                    noVoiceText = noVoiceText,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── TTS — Lecture vocale ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(Res.string.settings_llm_voice_tts_title), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                    Text(stringResource(Res.string.settings_llm_voice_tts_desc), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Switch(
                    checked = state.voiceTtsEnabled,
                    onCheckedChange = { onUpdate { copy(voiceTtsEnabled = it) } },
                    modifier = Modifier.testTag("settings_voice_tts_enabled"),
                )
            }
            VoiceDropdown(
                label = stringResource(Res.string.settings_llm_voice_provider_label),
                value = state.voiceTtsProvider,
                options = state.availableTtsProviders,
                onValueChange = {
                    onUpdate { copy(voiceTtsProvider = it, voiceTtsModel = "", voiceTtsVoice = "") }
                    onFetchVoice(it, "TTS")
                },
                testTag = "settings_voice_tts_provider",
                enabled = state.voiceTtsEnabled,
                noOptionsText = noProviderText,
            )
            LaunchedEffect(state.voiceTtsProvider) {
                if (state.voiceTtsProvider.isNotBlank()) onFetchVoice(state.voiceTtsProvider, "TTS")
            }
            if (state.voiceTtsModels.isNotEmpty()) {
                VoiceDropdown(stringResource(Res.string.settings_llm_voice_model_label), state.voiceTtsModel, state.voiceTtsModels, { onUpdate { copy(voiceTtsModel = it) } }, "settings_voice_tts_model", enabled = state.voiceTtsEnabled, noOptionsText = noProviderText)
            }
            if (state.voiceTtsVoices.isNotEmpty()) {
                VoiceDropdownWithPreview(
                    label = stringResource(Res.string.settings_llm_voice_voice_label),
                    value = state.voiceTtsVoice,
                    voiceInfos = state.voiceTtsVoiceInfos,
                    onValueChange = { onUpdate { copy(voiceTtsVoice = it) } },
                    onPreview = { onPreviewVoice(state.voiceTtsProvider, it) },
                    previewPlaying = state.voicePreviewPlaying,
                    testTag = "settings_voice_tts_voice",
                    enabled = state.voiceTtsEnabled,
                    noVoiceText = noVoiceText,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── STT — Dictée vocale ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(Res.string.settings_llm_voice_stt_title), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                    Text(stringResource(Res.string.settings_llm_voice_stt_desc), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Switch(
                    checked = state.voiceSttEnabled,
                    onCheckedChange = { onUpdate { copy(voiceSttEnabled = it) } },
                    modifier = Modifier.testTag("settings_voice_stt_enabled"),
                )
            }
            VoiceDropdown(
                label = stringResource(Res.string.settings_llm_voice_provider_label),
                value = state.voiceSttProvider,
                options = state.availableSttProviders,
                onValueChange = {
                    onUpdate { copy(voiceSttProvider = it, voiceSttModel = "") }
                    onFetchVoice(it, "STT")
                },
                testTag = "settings_voice_stt_provider",
                enabled = state.voiceSttEnabled,
                noOptionsText = noProviderText,
            )
            LaunchedEffect(state.voiceSttProvider) {
                if (state.voiceSttProvider.isNotBlank()) onFetchVoice(state.voiceSttProvider, "STT")
            }
            if (state.voiceSttModels.isNotEmpty()) {
                VoiceDropdown(stringResource(Res.string.settings_llm_voice_model_label), state.voiceSttModel, state.voiceSttModels, { onUpdate { copy(voiceSttModel = it) } }, "settings_voice_stt_model", enabled = state.voiceSttEnabled, noOptionsText = noProviderText)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── TRANSLATE — Traduction ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(stringResource(Res.string.settings_llm_voice_translate_title), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
                    Text(stringResource(Res.string.settings_llm_voice_translate_desc), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Switch(
                    checked = state.voiceTranslateEnabled,
                    onCheckedChange = { onUpdate { copy(voiceTranslateEnabled = it) } },
                    modifier = Modifier.testTag("settings_voice_translate_enabled"),
                )
            }
            VoiceDropdown(
                label = stringResource(Res.string.settings_llm_voice_provider_label),
                value = state.voiceTranslateProvider,
                options = state.availableTranslateProviders,
                onValueChange = {
                    onUpdate { copy(voiceTranslateProvider = it, voiceTranslateModel = "") }
                    onFetchVoice(it, "TRANSLATE")
                },
                testTag = "settings_voice_translate_provider",
                enabled = state.voiceTranslateEnabled,
                noOptionsText = noProviderText,
            )
            LaunchedEffect(state.voiceTranslateProvider) {
                if (state.voiceTranslateProvider.isNotBlank()) onFetchVoice(state.voiceTranslateProvider, "TRANSLATE")
            }
            if (state.voiceTranslateModels.isNotEmpty()) {
                VoiceDropdown(stringResource(Res.string.settings_llm_voice_model_label), state.voiceTranslateModel, state.voiceTranslateModels, { onUpdate { copy(voiceTranslateModel = it) } }, "settings_voice_translate_model", enabled = state.voiceTranslateEnabled, noOptionsText = noProviderText)
            }
            // Target language selector
            VoiceDropdown(
                label = stringResource(Res.string.settings_llm_voice_target_lang),
                value = state.voiceTranslateTargetLang,
                options = listOf("fr", "en", "es", "de", "it", "pt", "ja", "ko", "zh", "ar", "hi", "ru", "nl"),
                onValueChange = { onUpdate { copy(voiceTranslateTargetLang = it) } },
                testTag = "settings_voice_translate_lang",
                enabled = state.voiceTranslateEnabled,
                noOptionsText = noProviderText,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── System instructions ──
            OutlinedTextField(
                value = state.voiceSystemInstructions,
                onValueChange = { onUpdate { copy(voiceSystemInstructions = it) } },
                label = { Text(stringResource(Res.string.settings_llm_voice_instructions_label)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("settings_voice_instructions"),
                shape = RoundedCornerShape(12.dp),
                colors = settingsFieldColors(colors),
                supportingText = { Text(stringResource(Res.string.settings_llm_voice_instructions_hint)) },
            )
        }
    }
}

// ── Voice dropdown (reusable) ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceDropdown(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    testTag: String,
    enabled: Boolean = true,
    noOptionsText: String = "",
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = value.ifEmpty { "(auto)" },
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier.fillMaxWidth().menuAnchor().testTag(testTag),
            shape = RoundedCornerShape(12.dp),
            colors = settingsFieldColors(colors),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(noOptionsText, color = colors.onSurfaceVariant) },
                    onClick = { expanded = false },
                    enabled = false,
                )
            } else {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

// ── Voice dropdown with preview ▶️ ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceDropdownWithPreview(
    label: String,
    value: String,
    voiceInfos: List<Pair<String, String>>,
    onValueChange: (String) -> Unit,
    onPreview: (String) -> Unit,
    previewPlaying: String,
    testTag: String,
    enabled: Boolean = true,
    noVoiceText: String = "",
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val listenText = stringResource(Res.string.settings_llm_voice_listen, "%s")

    // Find description for current value
    val currentDesc = voiceInfos.find { it.first == value }?.second ?: ""
    val displayValue = if (value.isNotEmpty()) {
        if (currentDesc.isNotEmpty()) "$value — $currentDesc" else value
    } else {
        "(auto)"
    }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier.fillMaxWidth().menuAnchor().testTag(testTag),
            shape = RoundedCornerShape(12.dp),
            colors = settingsFieldColors(colors),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (voiceInfos.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(noVoiceText, color = colors.onSurfaceVariant) },
                    onClick = { expanded = false },
                    enabled = false,
                )
            } else {
                voiceInfos.forEach { (id, description) ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(id, style = MaterialTheme.typography.bodyMedium)
                                    if (description.isNotBlank()) {
                                        Text(
                                            description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                }
                                // Preview play button
                                val isPlaying = previewPlaying == id
                                IconButton(
                                    onClick = { onPreview(id) },
                                    modifier = Modifier.size(32.dp),
                                    enabled = previewPlaying.isBlank(),
                                ) {
                                    if (isPlaying) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = colors.primary,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = listenText.replace("%s", id),
                                            modifier = Modifier.size(20.dp),
                                            tint = colors.primary,
                                        )
                                    }
                                }
                            }
                        },
                        onClick = {
                            onValueChange(id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
