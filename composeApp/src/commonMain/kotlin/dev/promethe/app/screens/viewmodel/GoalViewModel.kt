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

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── DTOs ─────────────────────────────────────────────────────────────────────

@Serializable
data class GoalStatusDto(
    val state: String = "IDLE",
    val goal: String = "",
    val tasksTotal: Int = 0,
    val tasksCompleted: Int = 0,
    val tasksFailed: Int = 0,
    val currentTask: String = "",
    val totalTokens: Long = 0,
    val totalCost: Double = 0.0,
    val elapsedMs: Long = 0,
    val results: List<GoalTaskDto> = emptyList(),
)

@Serializable
data class GoalTaskDto(
    val index: Int = 0,
    val title: String = "",
    val status: String = "",
    val response: String = "",
    val durationMs: Long = 0,
)

// ── UI State ─────────────────────────────────────────────────────────────────

data class GoalUiState(
    val goalText: String = "",
    val budgetPreset: String = "STANDARD",
    val status: GoalStatusDto = GoalStatusDto(),
    val error: String? = null,
    val isPolling: Boolean = false,
) {
    val isRunning: Boolean
        get() = status.state in listOf("DECOMPOSING", "RUNNING")
}

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Goal (Autonomous Mode) screen.
 *
 * Manages goal submission, auto-polling status, budget presets,
 * and goal cancellation. Polling starts automatically on goal launch
 * and stops when the goal completes or is cancelled.
 */
class GoalViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(GoalUiState())
    val state: StateFlow<GoalUiState> = _state.asStateFlow()

    private var pollingJob: Job? = null

    // ── State Mutation ───────────────────────────────────────────────────────

    fun updateGoalText(text: String) {
        _state.update { it.copy(goalText = text) }
    }

    fun updateBudgetPreset(preset: String) {
        _state.update { it.copy(budgetPreset = preset) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ── Launch Goal ──────────────────────────────────────────────────────────

    fun launchGoal() {
        val text = _state.value.goalText
        if (text.isBlank()) return

        viewModelScope.launch {
            try {
                val body = buildJsonObject {
                    put("goal", text)
                    put("budgetPreset", _state.value.budgetPreset)
                }
                client.postJson("/api/v1/goal", body.toString())
                _state.update {
                    it.copy(
                        error = null,
                        status = GoalStatusDto(state = "DECOMPOSING", goal = text),
                    )
                }
                startPolling()
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to launch goal: ${e.message}") }
            }
        }
    }

    // ── Cancel Goal ──────────────────────────────────────────────────────────

    fun cancelGoal() {
        viewModelScope.launch {
            try {
                client.postJson("/api/v1/goal/stop", "{}")
                stopPolling()
                _state.update {
                    it.copy(
                        status = it.status.copy(state = "STOPPED"),
                        isPolling = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to stop: ${e.message}") }
            }
        }
    }

    // ── Polling ──────────────────────────────────────────────────────────────

    private fun startPolling() {
        stopPolling()
        _state.update { it.copy(isPolling = true) }
        pollingJob = viewModelScope.launch {
            while (true) {
                try {
                    val resp = client.getJson("/api/v1/goal/status")
                    val newStatus = PrometheJson.decodeFromString<GoalStatusDto>(resp.toString())
                    _state.update { it.copy(status = newStatus, error = null) }
                    if (newStatus.state !in listOf("DECOMPOSING", "RUNNING")) {
                        _state.update { it.copy(isPolling = false) }
                        break
                    }
                } catch (e: Exception) {
                    logger.debug(e) { "Goal polling error" }
                }
                delay(2000)
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
