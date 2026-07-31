package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.api.PluginListResponse
import dev.promethe.api.PluginToggleRequest
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── UI State ─────────────────────────────────────────────────────────────────

data class PluginsUiState(
    val pluginListResponse: PluginListResponse? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Plugins screen.
 *
 * Manages plugin list loading and toggle (enable/disable) actions.
 * Injected via Koin: `koinViewModel<PluginsViewModel>()`
 */
class PluginsViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(PluginsUiState())
    val state: StateFlow<PluginsUiState> = _state.asStateFlow()

    init {
        load()
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val response = client.getPlugins()
                _state.update { it.copy(pluginListResponse = response, isLoading = false) }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load plugins" }
                _state.update { it.copy(error = e.message ?: "Failed to load plugins", isLoading = false) }
            }
        }
    }

    // ── Toggle Plugin ────────────────────────────────────────────────────────

    fun togglePlugin(
        pluginName: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            try {
                val body = kotlinx.serialization.json.Json.encodeToString(
                    PluginToggleRequest.serializer(),
                    PluginToggleRequest(enabled = enabled),
                )
                client.putJson("/api/v1/plugins/$pluginName/toggle", body)
                load() // Reload to reflect changes
            } catch (e: Exception) {
                logger.warn(e) { "Failed to toggle plugin $pluginName" }
                _state.update { it.copy(error = "Failed to toggle $pluginName: ${e.message}") }
            }
        }
    }
}
