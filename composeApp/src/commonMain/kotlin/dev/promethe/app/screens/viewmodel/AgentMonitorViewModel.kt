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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── UI State ─────────────────────────────────────────────────────────────────

data class AgentMonitorUiState(
    val agents: List<AgentStatusDto> = emptyList(),
    val selectedAgentId: String? = null,
    val events: List<AgentExecutionEvent> = emptyList(),
    val isLoading: Boolean = true,
    val pendingApprovals: List<JsonObject> = emptyList(),
    val approvalCount: Int = 0,
    val pendingProviderChoices: List<JsonObject> = emptyList(),
    val providerChoiceCount: Int = 0,
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Agent Monitor screen.
 *
 * Manages agent listing, real-time SSE event streaming,
 * pending approval polling, and approval/rejection actions.
 */
class AgentMonitorViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(AgentMonitorUiState())
    val state: StateFlow<AgentMonitorUiState> = _state.asStateFlow()

    private var eventStreamJob: Job? = null
    private var approvalPollJob: Job? = null
    private var providerPollJob: Job? = null

    init {
        loadAgents()
        startEventStream()
        startApprovalPolling()
        startProviderChoicePolling()
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

    // ── Approval Polling ─────────────────────────────────────────────────────

    private fun startApprovalPolling() {
        approvalPollJob?.cancel()
        approvalPollJob = viewModelScope.launch {
            while (true) {
                try {
                    val response = client.getPendingApprovals()
                    val items = response["pending"]?.jsonArray ?: JsonArray(emptyList())
                    val approvals = items.map { it.jsonObject }
                    _state.update { it.copy(pendingApprovals = approvals, approvalCount = approvals.size) }
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to poll pending approvals" }
                }
                delay(15_000)
            }
        }
    }

    // ── Approval Actions ─────────────────────────────────────────────────────

    fun approveAction(approvalId: String) {
        viewModelScope.launch {
            try {
                client.respondToApproval(approvalId, approved = true)
                _state.update {
                    it.copy(
                        pendingApprovals = it.pendingApprovals.filter { a ->
                            a["id"]?.toString()?.trim('"') != approvalId
                        },
                    )
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to approve action $approvalId" }
            }
        }
    }

    fun rejectAction(approvalId: String) {
        viewModelScope.launch {
            try {
                client.respondToApproval(approvalId, approved = false)
                _state.update {
                    it.copy(
                        pendingApprovals = it.pendingApprovals.filter { a ->
                            a["id"]?.toString()?.trim('"') != approvalId
                        },
                    )
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to reject action $approvalId" }
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

    override fun onCleared() {
        eventStreamJob?.cancel()
        approvalPollJob?.cancel()
        providerPollJob?.cancel()
        super.onCleared()
    }
}
