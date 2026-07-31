package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.api.AgentProfile
import dev.promethe.api.AgentProfileRequest
import dev.promethe.app.network.PrometheClient
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── UI State ─────────────────────────────────────────────────────────────────

data class AgentProfilesUiState(
    val profiles: List<AgentProfile> = emptyList(),
    val skills: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val showEditor: Boolean = false,
    val editingProfile: AgentProfile? = null,
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Agent Profiles CRUD screen.
 *
 * Manages profile listing, creation, update, deletion, and duplication.
 * Editor dialog state is tracked here so orientation changes don't lose it.
 */
class AgentProfilesViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(AgentProfilesUiState())
    val state: StateFlow<AgentProfilesUiState> = _state.asStateFlow()

    init {
        loadProfiles()
    }

    // ── State Mutation ───────────────────────────────────────────────────────

    fun showCreateDialog() {
        _state.update { it.copy(showEditor = true, editingProfile = null) }
    }

    fun showEditDialog(profile: AgentProfile) {
        _state.update { it.copy(showEditor = true, editingProfile = profile) }
    }

    fun dismissEditor() {
        _state.update { it.copy(showEditor = false, editingProfile = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    fun loadProfiles() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val loaded = client.getAgentProfiles()
                val skillsResponse = client.getSkills()
                val skillNames = skillsResponse.skills.map { it.name }

                val toolsObj = client.getMcpTools()
                val toolsArray = toolsObj["tools"]?.jsonArray
                val toolNames = toolsArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content } ?: emptyList()

                _state.update {
                    it.copy(
                        profiles = loaded,
                        skills = skillNames,
                        tools = toolNames,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load agent profiles" }
                _state.update { it.copy(error = e.message ?: "Failed to load profiles", isLoading = false) }
            }
        }
    }

    // ── Create / Update ──────────────────────────────────────────────────────

    fun saveProfile(request: AgentProfileRequest) {
        val editing = _state.value.editingProfile
        viewModelScope.launch {
            try {
                if (editing != null) {
                    client.updateAgentProfile(editing.id, request)
                } else {
                    client.createAgentProfile(request)
                }
                _state.update { it.copy(showEditor = false, editingProfile = null) }
                loadProfiles()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            try {
                client.deleteAgentProfile(profileId)
                _state.update { it.copy(profiles = it.profiles.filter { p -> p.id != profileId }) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    // ── Set Default ─────────────────────────────────────────────────────────

    fun setDefaultProfile(profileId: String) {
        viewModelScope.launch {
            try {
                client.setDefaultProfile(profileId)
                loadProfiles()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    // ── Duplicate ────────────────────────────────────────────────────────────

    fun duplicateProfile(profile: AgentProfile) {
        val request = AgentProfileRequest(
            id = "${profile.id}-copy",
            name = "${profile.name} (copie)",
            provider = profile.provider,
            model = profile.model,
            systemPrompt = profile.systemPrompt,
            tools = profile.tools,
            skills = profile.skills,
            maxIterations = profile.maxIterations,
            temperature = profile.temperature,
            reasoningEffort = profile.reasoningEffort,
            isSystem = false,
        )
        viewModelScope.launch {
            try {
                client.createAgentProfile(request)
                loadProfiles()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }
}
