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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── UI Model ─────────────────────────────────────────────────────────────────

data class McpServerUiModel(
    val id: String,
    val name: String,
    val transport: String,
    val status: String,
    val tools: List<String>,
)

// ── UI State ─────────────────────────────────────────────────────────────────

data class McpUiState(
    val servers: List<McpServerUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    // Add-server dialog state
    val showAddDialog: Boolean = false,
    val dialogName: String = "",
    val dialogTransport: String = "stdio",
    val dialogCommand: String = "",
    val dialogUrl: String = "",
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the MCP screen.
 *
 * Manages MCP server listing, connect/disconnect lifecycle, and server registration.
 * Injected via Koin: `koinViewModel<McpViewModel>()`
 */
class McpViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(McpUiState())
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    init {
        load()
    }

    // ── State Mutation ───────────────────────────────────────────────────────

    fun showAddDialog() {
        _state.update {
            it.copy(
                showAddDialog = true,
                dialogName = "",
                dialogTransport = "stdio",
                dialogCommand = "",
                dialogUrl = "",
            )
        }
    }

    fun dismissAddDialog() {
        _state.update { it.copy(showAddDialog = false) }
    }

    fun updateDialogName(name: String) {
        _state.update { it.copy(dialogName = name) }
    }

    fun updateDialogTransport(transport: String) {
        _state.update { it.copy(dialogTransport = transport) }
    }

    fun updateDialogCommand(command: String) {
        _state.update { it.copy(dialogCommand = command) }
    }

    fun updateDialogUrl(url: String) {
        _state.update { it.copy(dialogUrl = url) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val json = client.getMcpServers()
                val servers = parseMcpServers(json)
                _state.update { it.copy(servers = servers, isLoading = false) }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load MCP servers" }
                _state.update { it.copy(error = e.message ?: "Failed to load MCP servers", isLoading = false) }
            }
        }
    }

    // ── Add Server ───────────────────────────────────────────────────────────

    fun addServer() {
        val s = _state.value
        viewModelScope.launch {
            try {
                val body = buildJsonObject {
                    put("name", s.dialogName.trim())
                    put("transport", s.dialogTransport)
                    if (s.dialogTransport == "stdio") put("command", s.dialogCommand.trim())
                    if (s.dialogTransport == "sse") put("url", s.dialogUrl.trim())
                }.toString()
                client.addMcpServer(body)
                _state.update { it.copy(showAddDialog = false) }
                load() // Reload to reflect new server
            } catch (e: Exception) {
                logger.warn(e) { "Failed to register MCP server" }
                _state.update { it.copy(error = "Failed to register server: ${e.message}") }
            }
        }
    }

    // ── Connect / Disconnect ─────────────────────────────────────────────────

    fun connect(serverId: String) {
        viewModelScope.launch {
            try {
                client.connectMcpServer(serverId)
                load() // Reload to reflect status change
            } catch (e: Exception) {
                logger.warn(e) { "Failed to connect MCP server $serverId" }
                _state.update { it.copy(error = "Failed to connect: ${e.message}") }
            }
        }
    }

    fun disconnect(serverId: String) {
        viewModelScope.launch {
            try {
                client.disconnectMcpServer(serverId)
                load() // Reload to reflect status change
            } catch (e: Exception) {
                logger.warn(e) { "Failed to disconnect MCP server $serverId" }
                _state.update { it.copy(error = "Failed to disconnect: ${e.message}") }
            }
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private fun parseMcpServers(json: JsonObject): List<McpServerUiModel> =
        try {
            val arr = json["servers"]?.jsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val obj = el.jsonObject
                McpServerUiModel(
                    id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    name = obj["name"]?.jsonPrimitive?.content ?: "unnamed",
                    transport = obj["transport"]?.jsonPrimitive?.content ?: "stdio",
                    status = obj["status"]?.jsonPrimitive?.content ?: "disconnected",
                    tools = obj["tools"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                )
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse MCP servers JSON" }
            emptyList()
        }
}
