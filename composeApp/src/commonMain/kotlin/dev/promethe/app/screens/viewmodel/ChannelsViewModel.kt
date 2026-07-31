package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── UI Model ─────────────────────────────────────────────────────────────────

data class ChannelUiModel(
    val name: String,
    val configured: Boolean,
    val webhookPath: String,
    val requiredKeys: List<String>,
    val config: Map<String, String>,
)

// ── UI State ─────────────────────────────────────────────────────────────────

data class ChannelsUiState(
    val channels: List<ChannelUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val editingChannel: ChannelUiModel? = null,
    val testResult: String? = null,
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Channels screen.
 *
 * Manages channel listing, configuration, and test message sending.
 * Injected via Koin: `koinViewModel<ChannelsViewModel>()`
 */
class ChannelsViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(ChannelsUiState())
    val state: StateFlow<ChannelsUiState> = _state.asStateFlow()

    init {
        load()
    }

    // ── State Mutation ───────────────────────────────────────────────────────

    fun setEditingChannel(channel: ChannelUiModel?) {
        _state.update { it.copy(editingChannel = channel) }
    }

    fun clearTestResult() {
        _state.update { it.copy(testResult = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val json = client.getChannels()
                val channels = parseChannels(json)
                _state.update { it.copy(channels = channels, isLoading = false) }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load channels" }
                _state.update { it.copy(error = e.message ?: "Failed to load channels", isLoading = false) }
            }
        }
    }

    // ── Test Channel ─────────────────────────────────────────────────────────

    fun testChannel(channelName: String) {
        viewModelScope.launch {
            try {
                client.testChannel(channelName)
                _state.update { it.copy(testResult = "✅ $channelName: test sent") }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to test channel $channelName" }
                _state.update { it.copy(testResult = "❌ $channelName: ${e.message}") }
            }
        }
    }

    // ── Save Channel Config ──────────────────────────────────────────────────

    fun saveChannel(
        channelName: String,
        configMap: Map<String, String>,
    ) {
        viewModelScope.launch {
            try {
                val body = buildJsonObject {
                    configMap.forEach { (k, v) -> put(k, v) }
                }.toString()
                client.configureChannel(channelName, body)
                _state.update { it.copy(editingChannel = null) }
                load() // Reload to reflect changes
            } catch (e: Exception) {
                logger.warn(e) { "Failed to configure channel $channelName" }
                _state.update { it.copy(error = "Failed to configure $channelName: ${e.message}") }
            }
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private fun parseChannels(json: JsonObject): List<ChannelUiModel> =
        try {
            val arr = json["channels"]?.jsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val obj = el.jsonObject
                ChannelUiModel(
                    name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    configured = obj["configured"]?.jsonPrimitive?.booleanOrNull ?: false,
                    webhookPath = obj["webhookPath"]?.jsonPrimitive?.content ?: "",
                    requiredKeys = obj["requiredKeys"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    config = obj["config"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                )
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse channels JSON" }
            emptyList()
        }
}
