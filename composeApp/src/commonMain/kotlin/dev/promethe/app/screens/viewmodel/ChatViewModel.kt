package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.api.AgentProfile
import dev.promethe.api.ChatEvent
import dev.promethe.api.ChatRequest
import dev.promethe.api.FeedbackRequest
import dev.promethe.app.a2ui.DynamicContent
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── UI Models ────────────────────────────────────────────────────────────────

data class DisplayItem(
    val id: String = "${Clock.System.now().toEpochMilliseconds()}_${kotlin.random.Random.nextLong(0, Long.MAX_VALUE)}",
    val event: ChatEvent,
    val isUser: Boolean,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    val hasError: Boolean = false,
    val originalMessage: String? = null,
)

data class ChatUiState(
    val items: List<DisplayItem> = emptyList(),
    val inputText: String = "",
    val isStreaming: Boolean = false,
    val errorMessage: String? = null,
    val profiles: List<AgentProfile> = emptyList(),
    val selectedProfile: AgentProfile? = null,
    val showProfilePicker: Boolean = false,
    val dynamicContent: DynamicContent = DynamicContent(),
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Chat screen.
 *
 * Manages message history, SSE streaming with REST fallback,
 * profile selection, error recovery & retry, and feedback submission.
 */
class ChatViewModel(
    private val sessionId: String,
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        loadProfiles()
        loadHistory()
    }

    // ── State Mutation ───────────────────────────────────────────────────────

    fun updateInput(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun toggleProfilePicker() {
        _state.update { it.copy(showProfilePicker = !it.showProfilePicker) }
    }

    fun selectProfile(profile: AgentProfile) {
        _state.update { it.copy(selectedProfile = profile, showProfilePicker = false) }
        // Persist selected profile to session metadata
        viewModelScope.launch {
            try {
                val metadata = buildJsonObject { put("profileId", profile.id) }.toString()
                client.updateSessionMetadata(sessionId, metadata)
            } catch (e: Exception) {
                logger.debug(e) { "Failed to persist profile selection for session $sessionId" }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    fun loadProfiles() {
        viewModelScope.launch {
            try {
                val loaded = client.getAgentProfiles()
                // Try to restore the saved profile from session metadata
                val savedProfile = try {
                    val sessions = client.getSessions()
                    val session = sessions.find { it.id == sessionId }
                    val metadataStr = session?.metadata
                    if (!metadataStr.isNullOrBlank() && metadataStr != "{}") {
                        val metaJson = Json.parseToJsonElement(metadataStr).jsonObject
                        val profileId = metaJson["profileId"]?.jsonPrimitive?.content
                        profileId?.let { id -> loaded.find { it.id == id } }
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }

                val selected = savedProfile
                    ?: loaded.find { it.id == "promethe" }
                    ?: loaded.firstOrNull()
                _state.update { it.copy(profiles = loaded, selectedProfile = selected) }
            } catch (e: Exception) {
                logger.debug(e) { "Failed to load agent profiles" }
            }
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            try {
                val history = client.getMessages(sessionId)
                val displayItems = history.map { ev ->
                    DisplayItem(event = ev, isUser = ev.type == "user")
                }
                _state.update { it.copy(items = displayItems) }
            } catch (e: Exception) {
                logger.debug(e) { "Failed to load message history for session $sessionId" }
            }
        }
    }

    // ── Send Message ─────────────────────────────────────────────────────────

    /**
     * Send a message via SSE streaming with REST fallback.
     * Adds an optimistic user bubble, then collects streamed events.
     * On total failure, marks the message as retryable.
     */
    fun sendMessage(text: String? = null) {
        val message = text ?: _state.value.inputText
        if (message.isBlank() || _state.value.isStreaming) return

        // Optimistic update
        val userItem = DisplayItem(
            event = ChatEvent(type = "user", content = message),
            isUser = true,
        )
        _state.update {
            it.copy(
                items = it.items + userItem,
                inputText = "",
                errorMessage = null,
                isStreaming = true,
            )
        }

        viewModelScope.launch {
            // Resolve the currently selected profile's provider/model to pass to the backend.
            val profile = _state.value.selectedProfile
            val profileProvider = profile?.provider
            val profileModel = profile?.model
            try {
                client.chatStream(
                    sessionId = sessionId,
                    message = message,
                    profileProvider = profileProvider,
                    profileModel = profileModel,
                ).collect { event ->
                    _state.update { it.copy(items = it.items + DisplayItem(event = event, isUser = false)) }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Chat stream failed for session $sessionId, falling back to REST" }
                try {
                    val response = client.sendMessage(ChatRequest(sessionId, message))
                    _state.update { it.copy(items = it.items + DisplayItem(event = response, isUser = false)) }
                } catch (e2: Exception) {
                    _state.update { s ->
                        s.copy(
                            errorMessage = "Connection error: ${e2.message}",
                            items = s.items.mapIndexed { index, item ->
                                if (index == s.items.lastIndex && item.isUser) {
                                    item.copy(hasError = true, originalMessage = message)
                                } else {
                                    item
                                }
                            },
                        )
                    }
                }
            } finally {
                _state.update { it.copy(isStreaming = false) }
            }
        }
    }

    /**
     * Retry a failed message: remove it from the list and re-send.
     */
    fun retryMessage(failedItem: DisplayItem) {
        val msg = failedItem.originalMessage ?: return
        _state.update { it.copy(items = it.items.filter { item -> item !== failedItem }) }
        sendMessage(msg)
    }

    // ── Feedback ─────────────────────────────────────────────────────────────

    fun submitFeedback(score: Int) {
        viewModelScope.launch {
            try {
                client.submitFeedback(FeedbackRequest(sessionId, score.toDouble()))
            } catch (e: Exception) {
                logger.debug(e) { "Failed to submit feedback for session $sessionId" }
            }
        }
    }

    // ── A2UI ─────────────────────────────────────────────────────────────────

    /**
     * Handle actions from A2UI widgets.
     * Dispatches the action+params back to the agent as a structured message.
     */
    fun onA2UIAction(
        action: String,
        params: Map<String, String>,
    ) {
        val message = buildString {
            append("[UI Action] $action")
            if (params.isNotEmpty()) {
                append(": ")
                append(params.entries.joinToString(", ") { "${it.key}=${it.value}" })
            }
        }
        sendMessage(message)
    }

    /**
     * Update DynamicContent with server data.
     * Called when receiving a ChatEvent with ui_data metadata.
     */
    fun updateDynamicContent(data: JsonObject) {
        _state.value.dynamicContent.update(data)
    }
}
