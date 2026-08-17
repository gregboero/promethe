package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.api.CreateProjectRequest
import dev.promethe.api.ProjectInfo
import dev.promethe.api.UpdateProjectRequest
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectsUiState(
    val projects: List<ProjectInfo> = emptyList(),
    val activeProjectId: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val showArchived: Boolean = false,
) {
    val visibleProjects: List<ProjectInfo>
        get() = projects.filter { showArchived || !it.archived }
}

class ProjectsViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { client.getProjects() }
                .onSuccess { response ->
                    _state.update {
                        it.copy(
                            projects = response.projects,
                            activeProjectId = response.activeProjectId,
                            isLoading = false,
                        )
                    }
                }.onFailure { error ->
                    _state.update { it.copy(isLoading = false, error = error.message ?: "Project request failed") }
                }
        }
    }

    fun setShowArchived(value: Boolean) {
        _state.update { it.copy(showArchived = value) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun save(
        existing: ProjectInfo?,
        name: String,
        description: String,
        instructions: String,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            val result =
                if (existing == null) {
                    runCatching { client.createProject(CreateProjectRequest(name, description, instructions)) }
                } else {
                    runCatching {
                        client.updateProject(
                            existing.id,
                            UpdateProjectRequest(name = name, description = description, instructions = instructions),
                        )
                    }
                }
            result
                .onSuccess {
                    onSaved()
                    load()
                }.onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "Project update failed") }
                }
        }
    }

    fun activate(id: String) {
        viewModelScope.launch {
            runCatching { client.activateProject(id) }
                .onSuccess { load() }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    fun setArchived(
        project: ProjectInfo,
        archived: Boolean,
    ) {
        viewModelScope.launch {
            runCatching { client.updateProject(project.id, UpdateProjectRequest(archived = archived)) }
                .onSuccess { load() }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }
}
