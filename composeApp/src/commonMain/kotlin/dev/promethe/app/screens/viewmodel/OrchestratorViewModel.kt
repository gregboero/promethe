package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.app.network.PrometheClient
import dev.promethe.app.util.PrometheJson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.compose.resources.getString
import promethe.composeapp.generated.resources.Res
import promethe.composeapp.generated.resources.orchestrator_delegation_failed

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── DTOs ─────────────────────────────────────────────────────────────────────

@Serializable
data class OrchestratorDashboardDto(
    val activeCount: Int = 0,
    val completedCount: Int = 0,
    val subAgents: List<SubAgentDto> = emptyList(),
)

@Serializable
data class SubAgentDto(
    val sessionId: String = "",
    val profileId: String? = null,
    val task: String = "",
    val status: String = "",
    val response: String? = null,
    val durationMs: Long? = null,
)

// ── UI State ─────────────────────────────────────────────────────────────────

data class OrchestratorUiState(
    val dashboard: OrchestratorDashboardDto = OrchestratorDashboardDto(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val showDelegateDialog: Boolean = false,
    val autoRefresh: Boolean = true,
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Orchestrator (multi-agent) screen.
 *
 * Manages dashboard data, auto-refresh polling every 3s,
 * task delegation, and sub-agent cancellation.
 */
class OrchestratorViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(OrchestratorUiState())
    val state: StateFlow<OrchestratorUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        startAutoRefresh()
    }

    // ── State Mutation ───────────────────────────────────────────────────────

    fun showDelegateDialog() {
        _state.update { it.copy(showDelegateDialog = true) }
    }

    fun dismissDelegateDialog() {
        _state.update { it.copy(showDelegateDialog = false) }
    }

    fun toggleAutoRefresh() {
        val newValue = !_state.value.autoRefresh
        _state.update { it.copy(autoRefresh = newValue) }
        if (newValue) startAutoRefresh() else stopAutoRefresh()
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    private fun loadDashboard() {
        viewModelScope.launch {
            try {
                val resp = client.getJson("/api/v1/orchestrator/dashboard")
                val dashboard = PrometheJson.decodeFromString<OrchestratorDashboardDto>(resp.toString())
                _state.update { it.copy(dashboard = dashboard, error = null, isLoading = false) }
            } catch (e: Exception) {
                if (_state.value.isLoading) {
                    _state.update { it.copy(error = e.message, isLoading = false) }
                }
            }
        }
    }

    // ── Auto-Refresh ─────────────────────────────────────────────────────────

    private fun startAutoRefresh() {
        stopAutoRefresh()
        refreshJob = viewModelScope.launch {
            while (true) {
                loadDashboard()
                delay(15_000)
            }
        }
    }

    private fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    // ── Delegate Task ────────────────────────────────────────────────────────

    fun delegateTask(
        task: String,
        profileId: String?,
    ) {
        viewModelScope.launch {
            try {
                val body = buildJsonObject {
                    put("task", task)
                    profileId?.let { put("profileId", it) }
                }
                client.postJson("/api/v1/orchestrator/delegate", body.toString())
                _state.update { it.copy(showDelegateDialog = false) }
            } catch (e: Exception) {
                val message = getString(Res.string.orchestrator_delegation_failed, e.message ?: "")
                _state.update { it.copy(error = message) }
            }
        }
    }

    // ── Cancel Sub-Agent ─────────────────────────────────────────────────────

    fun cancelSubAgent(sessionId: String) {
        viewModelScope.launch {
            try {
                client.postJson(
                    "/api/v1/orchestrator/cancel",
                    buildJsonObject { put("sessionId", sessionId) }.toString(),
                )
            } catch (e: Exception) {
                logger.warn(e) { "Failed to cancel sub-agent $sessionId" }
            }
        }
    }

    override fun onCleared() {
        stopAutoRefresh()
        super.onCleared()
    }
}
