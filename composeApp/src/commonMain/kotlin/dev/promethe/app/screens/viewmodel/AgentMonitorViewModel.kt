package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.api.AgentExecutionEvent
import dev.promethe.api.AgentStatusDto
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── UI State ─────────────────────────────────────────────────────────────────

data class AgentMonitorUiState(
    val agents: List<AgentStatusDto> = emptyList(),
    val selectedAgentId: String? = null,
    val events: List<AgentExecutionEvent> = emptyList(),
    val isLoading: Boolean = true,
    val pendingProviderChoices: List<JsonObject> = emptyList(),
    val providerChoiceCount: Int = 0,
    val pendingMcpElicitations: List<JsonObject> = emptyList(),
    val mcpElicitationCount: Int = 0,
    val mcpElicitationDrafts: Map<String, Map<String, String>> = emptyMap(),
    val mcpElicitationErrors: Map<String, String> = emptyMap(),
    val mcpElicitationSubmitting: Set<String> = emptySet(),
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Agent Monitor screen.
 *
 * Manages agent listing, real-time SSE event streaming,
 * provider choice and MCP elicitation polling.
 */
class AgentMonitorViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(AgentMonitorUiState())
    val state: StateFlow<AgentMonitorUiState> = _state.asStateFlow()

    private var eventStreamJob: Job? = null
    private var providerPollJob: Job? = null
    private var mcpElicitationPollJob: Job? = null

    init {
        loadAgents()
        startEventStream()
        startProviderChoicePolling()
        startMcpElicitationPolling()
    }

    // ── State Mutation ───────────────────────────────────────────────────────

    fun selectAgent(agentId: String?) {
        _state.update { it.copy(selectedAgentId = agentId) }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    private fun loadAgents() {
        viewModelScope.launch {
            try {
                val loaded = client.getAgents()
                _state.update {
                    it.copy(
                        agents = loaded,
                        selectedAgentId = loaded.firstOrNull()?.id,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                logger.debug(e) { "No agents available" }
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    // ── Event Stream ─────────────────────────────────────────────────────────

    private fun startEventStream() {
        eventStreamJob?.cancel()
        eventStreamJob = viewModelScope.launch {
            try {
                client.agentEventsStream().collect { event ->
                    _state.update { s ->
                        s.copy(
                            events = s.events + event,
                            agents = s.agents.map { a ->
                                if (a.id == event.agentId) {
                                    a.copy(
                                        currentStep = event.content?.take(60),
                                        lastActivityAt = event.timestamp,
                                    )
                                } else {
                                    a
                                }
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                logger.debug(e) { "Agent event stream ended" }
            }
        }
    }

    // ── Provider Choice Polling ───────────────────────────────────────────

    private fun startProviderChoicePolling() {
        providerPollJob?.cancel()
        providerPollJob = viewModelScope.launch {
            while (true) {
                try {
                    val response = client.getPendingProviderChoices()
                    val items = response["pending"]?.jsonArray ?: JsonArray(emptyList())
                    val choices = items.map { it.jsonObject }
                    _state.update { it.copy(pendingProviderChoices = choices, providerChoiceCount = choices.size) }
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to poll pending provider choices" }
                }
                delay(2_000) // poll faster than approvals — provider choice is time-sensitive
            }
        }
    }

    // ── Provider Choice Actions ──────────────────────────────────────────

    fun selectProvider(
        requestId: String,
        providerId: String,
    ) {
        viewModelScope.launch {
            try {
                client.respondToProviderChoice(requestId, approved = true, selectedProviderId = providerId)
                _state.update {
                    it.copy(
                        pendingProviderChoices = it.pendingProviderChoices.filter { c ->
                            c["id"]?.toString()?.trim('"') != requestId
                        },
                    )
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to select provider for $requestId" }
            }
        }
    }

    fun rejectProviderChoice(requestId: String) {
        viewModelScope.launch {
            try {
                client.respondToProviderChoice(requestId, approved = false)
                _state.update {
                    it.copy(
                        pendingProviderChoices = it.pendingProviderChoices.filter { c ->
                            c["id"]?.toString()?.trim('"') != requestId
                        },
                    )
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to reject provider choice $requestId" }
            }
        }
    }

    private fun startMcpElicitationPolling() {
        mcpElicitationPollJob?.cancel()
        mcpElicitationPollJob = viewModelScope.launch {
            while (true) {
                try {
                    val response = client.getPendingMcpElicitations()
                    val items = response["pending"]?.jsonArray?.map { it.jsonObject }.orEmpty()
                    val activeIds = items.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.toSet()
                    _state.update { current ->
                        val drafts = current.mcpElicitationDrafts.filterKeys(activeIds::contains).toMutableMap()
                        items.forEach { request ->
                            val id = request["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            if (id !in drafts) drafts[id] = defaultMcpDraft(request)
                        }
                        current.copy(
                            pendingMcpElicitations = items,
                            mcpElicitationCount = items.size,
                            mcpElicitationDrafts = drafts,
                            mcpElicitationErrors = current.mcpElicitationErrors.filterKeys(activeIds::contains),
                        )
                    }
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to poll pending MCP input requests" }
                }
                delay(2_000)
            }
        }
    }

    fun updateMcpElicitationField(
        requestId: String,
        field: String,
        value: String,
    ) {
        _state.update { current ->
            current.copy(
                mcpElicitationDrafts =
                    current.mcpElicitationDrafts +
                        (requestId to (current.mcpElicitationDrafts[requestId].orEmpty() + (field to value))),
                mcpElicitationErrors = current.mcpElicitationErrors - requestId,
            )
        }
    }

    fun submitMcpElicitation(requestId: String) {
        if (requestId in _state.value.mcpElicitationSubmitting) return
        _state.update { it.copy(mcpElicitationSubmitting = it.mcpElicitationSubmitting + requestId) }
        viewModelScope.launch {
            try {
                val request = _state.value.pendingMcpElicitations.firstOrNull {
                    it["id"]?.jsonPrimitive?.contentOrNull == requestId
                } ?: return@launch
                val content = buildMcpContent(request, _state.value.mcpElicitationDrafts[requestId].orEmpty())
                client.respondToMcpElicitation(requestId, action = "accept", content = content)
                removeMcpElicitation(requestId)
            } catch (e: Exception) {
                logger.warn { "Failed to answer MCP input request $requestId" }
                _state.update { current ->
                    current.copy(
                        mcpElicitationErrors = current.mcpElicitationErrors + (requestId to (e.message ?: "Invalid response")),
                    )
                }
            } finally {
                _state.update { it.copy(mcpElicitationSubmitting = it.mcpElicitationSubmitting - requestId) }
            }
        }
    }

    fun declineMcpElicitation(requestId: String) {
        if (requestId in _state.value.mcpElicitationSubmitting) return
        _state.update { it.copy(mcpElicitationSubmitting = it.mcpElicitationSubmitting + requestId) }
        viewModelScope.launch {
            try {
                client.respondToMcpElicitation(requestId, action = "decline")
                removeMcpElicitation(requestId)
            } catch (e: Exception) {
                logger.warn { "Failed to decline MCP input request $requestId" }
                _state.update { current ->
                    current.copy(
                        mcpElicitationErrors = current.mcpElicitationErrors + (requestId to (e.message ?: "Request failed")),
                    )
                }
            } finally {
                _state.update { it.copy(mcpElicitationSubmitting = it.mcpElicitationSubmitting - requestId) }
            }
        }
    }

    private fun removeMcpElicitation(requestId: String) {
        _state.update { current ->
            current.copy(
                pendingMcpElicitations = current.pendingMcpElicitations.filterNot {
                    it["id"]?.jsonPrimitive?.contentOrNull == requestId
                },
                mcpElicitationCount = (current.mcpElicitationCount - 1).coerceAtLeast(0),
                mcpElicitationDrafts = current.mcpElicitationDrafts - requestId,
                mcpElicitationErrors = current.mcpElicitationErrors - requestId,
                mcpElicitationSubmitting = current.mcpElicitationSubmitting - requestId,
            )
        }
    }

    private fun defaultMcpDraft(request: JsonObject): Map<String, String> {
        val schema = request["requestedSchema"] as? JsonObject ?: return emptyMap()
        val properties = schema["properties"] as? JsonObject ?: return emptyMap()
        return properties.mapNotNull { (name, definitionValue) ->
            val definition = definitionValue as? JsonObject ?: return@mapNotNull null
            if (definition["type"]?.jsonPrimitive?.contentOrNull == "boolean") name to "false" else null
        }.toMap()
    }

    private fun buildMcpContent(
        request: JsonObject,
        draft: Map<String, String>,
    ): JsonObject {
        val schema = request["requestedSchema"] as? JsonObject ?: error("Missing requested schema")
        val properties = schema["properties"] as? JsonObject ?: error("Missing schema properties")
        val required =
            (schema["required"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                .orEmpty()
        return buildJsonObject {
            properties.forEach { (name, definitionValue) ->
                val definition = definitionValue as? JsonObject ?: error("Invalid field definition")
                val raw = draft[name]
                if (raw == null || (raw.isBlank() && definition["type"]?.jsonPrimitive?.contentOrNull != "string")) {
                    require(name !in required) { "A value is required for $name" }
                    return@forEach
                }
                when (definition["type"]?.jsonPrimitive?.contentOrNull) {
                    "string" -> put(name, raw)
                    "number" -> put(name, raw.toDoubleOrNull() ?: error("$name must be a number"))
                    "integer" -> put(name, raw.toLongOrNull() ?: error("$name must be an integer"))
                    "boolean" -> put(name, raw.toBooleanStrictOrNull() ?: error("$name must be true or false"))
                    else -> error("Unsupported field type for $name")
                }
            }
        }
    }

    override fun onCleared() {
        eventStreamJob?.cancel()
        providerPollJob?.cancel()
        mcpElicitationPollJob?.cancel()
        super.onCleared()
    }
}
