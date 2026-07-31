package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.api.ScheduledTaskRequest
import dev.promethe.api.ScheduledTaskResponse
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── UI State ─────────────────────────────────────────────────────────────────

data class SchedulerUiState(
    val tasks: List<ScheduledTaskResponse> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val showCreateDialog: Boolean = false,
    val profiles: List<dev.promethe.api.AgentProfile> = emptyList(),
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Scheduler (cron tasks) screen.
 *
 * Manages task CRUD, toggle (pause/resume), and task creation dialog lifecycle.
 * Dialog form state stays in the composable (lightweight UI-only concerns).
 */
class SchedulerViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(SchedulerUiState())
    val state: StateFlow<SchedulerUiState> = _state.asStateFlow()

    init {
        loadTasks()
    }

    // ── State Mutation ───────────────────────────────────────────────────────

    fun showCreateDialog() {
        _state.update { it.copy(showCreateDialog = true) }
    }

    fun dismissCreateDialog() {
        _state.update { it.copy(showCreateDialog = false) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    fun loadTasks() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val loaded = client.getScheduledTasks()
                val profiles = try {
                    client.getAgentProfiles()
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to load agent profiles for scheduler" }
                    emptyList()
                }
                _state.update { it.copy(tasks = loaded, profiles = profiles, isLoading = false) }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load scheduled tasks" }
                _state.update { it.copy(error = e.message ?: "Failed to load tasks", isLoading = false) }
            }
        }
    }

    // ── Create Task ──────────────────────────────────────────────────────────

    fun createTask(
        name: String,
        cronExpression: String,
        prompt: String,
        profileId: String?,
    ) {
        viewModelScope.launch {
            try {
                client.createScheduledTask(
                    ScheduledTaskRequest(
                        name = name,
                        cronExpression = cronExpression,
                        prompt = prompt,
                        profileId = profileId,
                    ),
                )
                _state.update { it.copy(showCreateDialog = false) }
                loadTasks()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to create scheduled task" }
                _state.update { it.copy(error = "Failed to create task") }
            }
        }
    }

    // ── Toggle Task ──────────────────────────────────────────────────────────

    fun toggleTask(taskId: String) {
        viewModelScope.launch {
            try {
                client.toggleScheduledTask(taskId)
                loadTasks()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to toggle scheduled task $taskId" }
            }
        }
    }

    // ── Delete Task ──────────────────────────────────────────────────────────

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            try {
                client.deleteScheduledTask(taskId)
                _state.update { it.copy(tasks = it.tasks.filter { t -> t.id != taskId }) }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to delete scheduled task $taskId" }
            }
        }
    }
}
