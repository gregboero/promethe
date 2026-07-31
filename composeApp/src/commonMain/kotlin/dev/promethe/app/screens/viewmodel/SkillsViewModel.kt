package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.api.SkillDto
import dev.promethe.api.SkillListResponse
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.promethe.api.ChatEvent
import kotlinx.coroutines.Job
import org.jetbrains.compose.resources.getString
import promethe.composeapp.generated.resources.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

data class ArchitectMessage(
    val id: String = "${kotlin.time.Clock.System.now().toEpochMilliseconds()}_${kotlin.random.Random.nextLong(0, Long.MAX_VALUE)}",
    val text: String,
    val isUser: Boolean,
    val isSystem: Boolean = false,
)

enum class ArchitectPhase {
    CollectName,
    CollectDescription,
    Generating,
    ReadyToValidate,
}

data class SkillsUiState(
    val skills: List<SkillDto> = emptyList(),
    val selectedSkill: SkillDto? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val editContent: String = "",
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val showCreateDialog: Boolean = false,
    val newSkillName: String = "",
    val showArchitectDialog: Boolean = false,
    val architectPhase: ArchitectPhase = ArchitectPhase.CollectName,
    val architectMessages: List<ArchitectMessage> = emptyList(),
    val architectSkillName: String = "",
    val architectDescription: String = "",
    val architectGeneratedContent: String = "",
    val architectSessionId: String = "",
    val isArchitectStreaming: Boolean = false,
    val isEditFlow: Boolean = false,
    val architectFirstMessageSent: Boolean = false,
    val originalContentForDiff: String = "",
)

class SkillsViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(SkillsUiState())
    val state: StateFlow<SkillsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val response = client.getSkills()
                _state.update { it.copy(skills = response.skills, isLoading = false) }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load skills" }
                _state.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun selectSkill(skill: SkillDto) {
        _state.update { it.copy(selectedSkill = skill, editContent = skill.content, isEditing = false) }
    }

    fun startEditing() {
        _state.update { it.copy(isEditing = true) }
    }

    fun cancelEditing() {
        _state.update { it.copy(editContent = it.selectedSkill?.content.orEmpty(), isEditing = false) }
    }

    fun updateEditContent(content: String) {
        _state.update { it.copy(editContent = content) }
    }

    fun saveSkill() {
        val current = _state.value
        val name = current.selectedSkill?.name ?: return
        val content = current.editContent
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                client.updateSkill(name, content)
                _state.update { it.copy(isSaving = false, isEditing = false) }
                load()
            } catch (e: Exception) {
                logger.error(e) { "Failed to save skill $name" }
                _state.update { it.copy(error = e.message, isSaving = false) }
            }
        }
    }

    fun deleteSkill(name: String) {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                client.deleteSkill(name)
                _state.update { it.copy(selectedSkill = null, editContent = "", isEditing = false) }
                load()
            } catch (e: Exception) {
                logger.error(e) { "Failed to delete skill $name" }
                _state.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun showCreateDialog() {
        _state.update { it.copy(showCreateDialog = true, newSkillName = "") }
    }

    fun hideCreateDialog() {
        _state.update { it.copy(showCreateDialog = false, newSkillName = "") }
    }

    fun updateNewSkillName(name: String) {
        _state.update { it.copy(newSkillName = name) }
    }

    fun createSkill(content: String) {
        val name = _state.value.newSkillName.trim()
        if (name.isBlank()) return
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                client.createSkill(name, content)
                _state.update { it.copy(isSaving = false, showCreateDialog = false, newSkillName = "") }
                load()
            } catch (e: Exception) {
                logger.error(e) { "Failed to create skill $name" }
                _state.update { it.copy(error = e.message, isSaving = false) }
            }
        }
    }

    // ── Architect mini-chat ──────────────────────────────────────────────

    private var architectJob: Job? = null

    fun openArchitectDialog(existingSkill: SkillDto? = null) {
        val sessionId = "architect-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        if (existingSkill != null) {
            viewModelScope.launch {
                val greeting = getString(Res.string.skills_architect_greeting_edit, existingSkill.name)
                _state.update {
                    it.copy(
                        showArchitectDialog = true,
                        architectPhase = ArchitectPhase.Generating,
                        architectMessages = listOf(
                            ArchitectMessage(
                                text = greeting,
                                isUser = false,
                                isSystem = true,
                            ),
                        ),
                        architectSkillName = existingSkill.name,
                        architectDescription = existingSkill.description,
                        architectGeneratedContent = existingSkill.content,
                        architectSessionId = sessionId,
                        isArchitectStreaming = false,
                        isEditFlow = true,
                        architectFirstMessageSent = false,
                        originalContentForDiff = existingSkill.content,
                    )
                }
            }
        } else {
            viewModelScope.launch {
                val greeting = getString(Res.string.skills_architect_greeting_new)
                _state.update {
                    it.copy(
                        showArchitectDialog = true,
                        architectPhase = ArchitectPhase.CollectName,
                        architectMessages = listOf(
                            ArchitectMessage(
                                text = greeting,
                                isUser = false,
                                isSystem = true,
                            ),
                        ),
                        architectSkillName = "",
                        architectDescription = "",
                        architectGeneratedContent = "",
                        architectSessionId = sessionId,
                        isArchitectStreaming = false,
                        isEditFlow = false,
                        architectFirstMessageSent = false,
                        originalContentForDiff = "",
                    )
                }
            }
        }
    }

    fun closeArchitectDialog() {
        architectJob?.cancel()
        _state.update {
            it.copy(
                showArchitectDialog = false,
                architectMessages = emptyList(),
                architectPhase = ArchitectPhase.CollectName,
                isArchitectStreaming = false,
            )
        }
    }

    fun submitArchitectInput(input: String) {
        val current = _state.value
        val trimmed = input.trim()
        if (trimmed.isBlank()) return

        when (current.architectPhase) {
            ArchitectPhase.CollectName -> {
                viewModelScope.launch {
                    val askDescription = getString(Res.string.skills_architect_ask_description, trimmed)
                    _state.update {
                        it.copy(
                            architectSkillName = trimmed,
                            architectMessages = it.architectMessages + listOf(
                                ArchitectMessage(text = trimmed, isUser = true),
                                ArchitectMessage(
                                    text = askDescription,
                                    isUser = false,
                                    isSystem = true,
                                ),
                            ),
                            architectPhase = ArchitectPhase.CollectDescription,
                        )
                    }
                }
            }

            ArchitectPhase.CollectDescription -> {
                viewModelScope.launch {
                    val generating = getString(Res.string.skills_architect_generating)
                    _state.update {
                        it.copy(
                            architectDescription = trimmed,
                            architectMessages = it.architectMessages + listOf(
                                ArchitectMessage(text = trimmed, isUser = true),
                                ArchitectMessage(
                                    text = generating,
                                    isUser = false,
                                    isSystem = true,
                                ),
                            ),
                            architectPhase = ArchitectPhase.Generating,
                            isArchitectStreaming = true,
                        )
                    }
                    launchArchitectGeneration()
                }
            }

            ArchitectPhase.Generating, ArchitectPhase.ReadyToValidate -> {
                // User sends a follow-up to the architect
                _state.update {
                    it.copy(
                        architectMessages = it.architectMessages +
                            ArchitectMessage(text = trimmed, isUser = true),
                        isArchitectStreaming = true,
                    )
                }
                launchArchitectFollowUp(trimmed)
            }
        }
    }

    private fun launchArchitectGeneration() {
        architectJob?.cancel()
        val s = _state.value
        val prompt = "Create a skill named \"${s.architectSkillName}\" with this purpose: ${s.architectDescription}"
        architectJob = viewModelScope.launch {
            try {
                // Create a session first
                try {
                    client.createSession(s.architectSessionId)
                } catch (_: Exception) {
                }

                var accumulated = ""
                var streamingMsgId: String? = null
                client.chatStream(
                    sessionId = s.architectSessionId,
                    message = prompt,
                    profileId = "skill_designer",
                ).collect { event ->
                    val text = event.content ?: return@collect
                    if (text.isBlank()) return@collect
                    accumulated = text
                    _state.update { st ->
                        val msgs = st.architectMessages.toMutableList()
                        val idx = msgs.indexOfFirst { it.id == streamingMsgId }
                        if (idx >= 0) {
                            msgs[idx] = msgs[idx].copy(text = accumulated)
                        } else {
                            val newMsg = ArchitectMessage(text = accumulated, isUser = false)
                            streamingMsgId = newMsg.id
                            msgs.add(newMsg)
                        }
                        st.copy(
                            architectMessages = msgs,
                            architectGeneratedContent = accumulated,
                        )
                    }
                }
                _state.update { it.copy(architectPhase = ArchitectPhase.ReadyToValidate, isArchitectStreaming = false) }
            } catch (e: Exception) {
                logger.error(e) { "Architect generation failed" }
                _state.update {
                    it.copy(
                        architectMessages = it.architectMessages +
                            ArchitectMessage(text = "\u274C Erreur : ${e.message}", isUser = false, isSystem = true),
                        isArchitectStreaming = false,
                    )
                }
            }
        }
    }

    private fun launchArchitectFollowUp(userMessage: String) {
        architectJob?.cancel()
        val s = _state.value
        val messageToSend = if (s.isEditFlow && !s.architectFirstMessageSent) {
            "Here is the current content of the skill \"${s.architectSkillName}\":\n\n```markdown\n${s.architectGeneratedContent}\n```\n\nThe user requests the following modification: $userMessage\n\nModify the skill and return the complete new content in SKILL.md format."
        } else {
            userMessage
        }

        // Mark first message as sent
        _state.update { it.copy(architectFirstMessageSent = true) }

        architectJob = viewModelScope.launch {
            try {
                var accumulated = ""
                var streamingMsgId: String? = null
                client.chatStream(
                    sessionId = s.architectSessionId,
                    message = messageToSend,
                    profileId = "skill_designer",
                ).collect { event ->
                    val text = event.content ?: return@collect
                    if (text.isBlank()) return@collect
                    accumulated = text
                    _state.update { st ->
                        val msgs = st.architectMessages.toMutableList()
                        val idx = msgs.indexOfFirst { it.id == streamingMsgId }
                        if (idx >= 0) {
                            msgs[idx] = msgs[idx].copy(text = accumulated)
                        } else {
                            val newMsg = ArchitectMessage(text = accumulated, isUser = false)
                            streamingMsgId = newMsg.id
                            msgs.add(newMsg)
                        }
                        st.copy(
                            architectMessages = msgs,
                            architectGeneratedContent = accumulated,
                        )
                    }
                }
                _state.update { it.copy(isArchitectStreaming = false, architectPhase = ArchitectPhase.ReadyToValidate) }
            } catch (e: Exception) {
                logger.error(e) { "Architect follow-up failed" }
                _state.update {
                    it.copy(
                        architectMessages = it.architectMessages +
                            ArchitectMessage(text = "❌ Erreur : ${e.message}", isUser = false, isSystem = true),
                        isArchitectStreaming = false,
                    )
                }
            }
        }
    }

    fun validateArchitectSkill() {
        val s = _state.value
        val name = s.architectSkillName.trim()
        val content = s.architectGeneratedContent.trim()
        if (name.isBlank() || content.isBlank()) return

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                if (s.isEditFlow) {
                    client.updateSkill(name, content)
                } else {
                    client.createSkill(name, content)
                }
                _state.update {
                    it.copy(
                        isSaving = false,
                        showArchitectDialog = false,
                        architectMessages = emptyList(),
                    )
                }
                load()
            } catch (e: Exception) {
                logger.error(e) { "Failed to save architect skill $name" }
                _state.update {
                    it.copy(
                        error = e.message,
                        isSaving = false,
                        architectMessages = it.architectMessages +
                            ArchitectMessage(text = "❌ Erreur sauvegarde : ${e.message}", isUser = false, isSystem = true),
                    )
                }
            }
        }
    }
}

// ── Diff utilities ──────────────────────────────────────────────────

enum class DiffType { ADD, REMOVE, EQUAL }

data class DiffLine(
    val type: DiffType,
    val text: String,
)

fun diffLines(
    oldContent: String,
    newContent: String,
): List<DiffLine> {
    val oldLines = oldContent.lines()
    val newLines = newContent.lines()
    val dp = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
    for (i in 0..oldLines.size) {
        for (j in 0..newLines.size) {
            if (i == 0 || j == 0) {
                dp[i][j] = 0
            } else if (oldLines[i - 1] == newLines[j - 1]) {
                dp[i][j] = dp[i - 1][j - 1] + 1
            } else {
                dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }

    val result = mutableListOf<DiffLine>()
    var i = oldLines.size
    var j = newLines.size
    while (i > 0 || j > 0) {
        if (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
            result.add(DiffLine(DiffType.EQUAL, oldLines[i - 1]))
            i--
            j--
        } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
            result.add(DiffLine(DiffType.ADD, newLines[j - 1]))
            j--
        } else {
            result.add(DiffLine(DiffType.REMOVE, oldLines[i - 1]))
            i--
        }
    }
    return result.reversed()
}
