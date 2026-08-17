package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.api.SessionInfo
import dev.promethe.api.ProjectInfo
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── UI State ─────────────────────────────────────────────────────────────────

data class SessionsUiState(
    val sessions: List<SessionInfo> = emptyList(),
    val isLoading: Boolean = true,
    val showError: Boolean = false,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val projects: List<ProjectInfo> = emptyList(),
    val activeProjectId: String? = null,
) {
    /** Filtered sessions based on search query. */
    val filteredSessions: List<SessionInfo>
        get() = if (searchQuery.isBlank()) {
            sessions
        } else {
            sessions.filter { session ->
                session.id.contains(searchQuery, ignoreCase = true) ||
                    (session.title?.contains(searchQuery, ignoreCase = true) == true) ||
                    projects.find { it.id == session.projectId }?.name?.contains(searchQuery, ignoreCase = true) == true
            }
        }

    val activeProject: ProjectInfo?
        get() = projects.find { it.id == activeProjectId }
}

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Sessions list screen.
 *
 * Manages session loading, search/filter, creation, and deletion.
 */
class SessionsViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    init {
        loadSessions()
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

    fun dismissError() {
        _state.update { it.copy(showError = false) }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    fun loadSessions() {
        _state.update { it.copy(isLoading = true, showError = false) }
        viewModelScope.launch {
            try {
                val projects = client.getProjects()
                val loaded = client.getSessions().distinctBy { it.id }
                _state.update {
                    it.copy(
                        sessions = loaded,
                        projects = projects.projects.filterNot(ProjectInfo::archived),
                        activeProjectId = projects.activeProjectId,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load sessions" }
                _state.update { it.copy(showError = true, isLoading = false) }
            }
        }
    }

    // ── Create Session ───────────────────────────────────────────────────────

    fun createSession(onCreated: (String) -> Unit) {
        // Guard against double-tap creating duplicate sessions
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val session = client.createSession(projectId = _state.value.activeProjectId)
                // Add optimistically instead of reloading (avoids race conditions)
                _state.update {
                    it.copy(
                        sessions = listOf(session) + it.sessions,
                        isLoading = false,
                    )
                }
                onCreated(session.id)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to create session" }
                _state.update { it.copy(showError = true, isLoading = false) }
            }
        }
    }

    // ── Delete Session ───────────────────────────────────────────────────────

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                client.deleteSession(sessionId)
                _state.update { it.copy(sessions = it.sessions.filter { s -> s.id != sessionId }) }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to delete session $sessionId" }
                _state.update { it.copy(showError = true) }
            }
        }
    }

    fun assignSessionProject(
        sessionId: String,
        projectId: String?,
    ) {
        viewModelScope.launch {
            try {
                val updated = client.assignSessionProject(sessionId, projectId)
                _state.update { state ->
                    state.copy(sessions = state.sessions.map { if (it.id == sessionId) updated else it })
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to assign session $sessionId to a project" }
                _state.update { it.copy(showError = true) }
            }
        }
    }
}
