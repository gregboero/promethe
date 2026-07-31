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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── UI Model ─────────────────────────────────────────────────────────────────

data class ToolUiModel(
    val name: String,
    val description: String,
    val source: String,
)

// ── UI State ─────────────────────────────────────────────────────────────────

data class ToolsUiState(
    val tools: List<ToolUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
) {
    /** Tools filtered by the current search query. */
    val filteredTools: List<ToolUiModel>
        get() = if (searchQuery.isBlank()) {
            tools
        } else {
            tools.filter { tool ->
                tool.name.contains(searchQuery, ignoreCase = true) ||
                    tool.description.contains(searchQuery, ignoreCase = true)
            }
        }

    /** Filtered tools grouped by source (Built-in / MCP). */
    val grouped: Map<String, List<ToolUiModel>>
        get() = filteredTools.groupBy { it.source }
}

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Tools screen.
 *
 * Loads the MCP tool inventory and provides search/filter capabilities.
 * Injected via Koin: `koinViewModel<ToolsViewModel>()`
 */
class ToolsViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

    init {
        load()
    }

    // ── State Mutation ───────────────────────────────────────────────────────

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun toggleSearch() {
        _state.update {
            val newActive = !it.isSearchActive
            it.copy(isSearchActive = newActive, searchQuery = if (!newActive) "" else it.searchQuery)
        }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val json = client.getMcpTools()
                val tools = parseTools(json)
                _state.update { it.copy(tools = tools, isLoading = false) }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load tools" }
                _state.update { it.copy(error = e.message ?: "Failed to load tools", isLoading = false) }
            }
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private fun parseTools(json: JsonObject): List<ToolUiModel> =
        try {
            val arr = json["tools"]?.jsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val obj = el.jsonObject
                ToolUiModel(
                    name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    description = obj["description"]?.jsonPrimitive?.content ?: "",
                    source = obj["source"]?.jsonPrimitive?.content ?: "MCP",
                )
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse tools JSON" }
            emptyList()
        }
}
