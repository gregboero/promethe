package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.api.GepaJobResponse
import dev.promethe.api.GepaOptimizeRequest
import dev.promethe.api.GepaResultDto
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── UI Models ────────────────────────────────────────────────────────────────

data class GepaRun(
    val id: String,
    val result: GepaResultDto,
    val timestamp: Long,
    val config: GepaOptimizeRequest,
    val targetLabel: String = "System Prompt",
)

// ── Target type ──────────────────────────────────────────────────────────────

enum class GepaTargetType(
    val label: String,
) {
    SYSTEM_PROMPT("System Prompt"),
    SKILL("Skill"),
    PROFILE("Profile"),
}

// ── UI State ─────────────────────────────────────────────────────────────────

data class GepaDashboardUiState(
    val isOptimizing: Boolean = false,
    val progress: Float = 0f,
    val currentGeneration: Int = 0,
    val maxGenerations: Int = 0,
    val runs: List<GepaRun> = emptyList(),
    val errorMessage: String? = null,
    val maxGenerationsSetting: Float = 5f,
    val populationSizeSetting: Float = 8f,
    val showConfig: Boolean = false,
    val activeJobId: String? = null,
    // ── Target selection ──
    val selectedTargetType: GepaTargetType = GepaTargetType.SYSTEM_PROMPT,
    val selectedSkillName: String? = null,
    val selectedProfileId: String? = null,
    val availableSkills: List<String> = emptyList(),
    val availableProfiles: List<Pair<String, String>> = emptyList(), // id to name
    val activeTargetLabel: String = "System Prompt",
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the GEPA (Genetic-Evolutionary Prompt Architecture) Dashboard.
 *
 * Uses background job pattern: start a job on the gateway, then poll for status.
 * The optimization survives page navigation — when coming back, the VM
 * detects the running job and resumes polling.
 */
class GepaDashboardViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(GepaDashboardUiState())
    val state: StateFlow<GepaDashboardUiState> = _state.asStateFlow()

    private var pollingJob: Job? = null

    // ── State Mutation ───────────────────────────────────────────────────────

    fun toggleConfig() {
        _state.update { it.copy(showConfig = !it.showConfig) }
    }

    fun updateMaxGenerations(value: Float) {
        _state.update { it.copy(maxGenerationsSetting = value) }
    }

    fun updatePopulationSize(value: Float) {
        _state.update { it.copy(populationSizeSetting = value) }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun selectTargetType(type: GepaTargetType) {
        _state.update { it.copy(selectedTargetType = type) }
    }

    fun selectSkill(name: String) {
        _state.update { it.copy(selectedSkillName = name) }
    }

    fun selectProfile(id: String) {
        _state.update { it.copy(selectedProfileId = id) }
    }

    // ── Progress (called from UI LaunchedEffect) ─────────────────────────────

    fun updateProgress(value: Float) {
        _state.update { it.copy(progress = value) }
    }

    // ── Build target string ──────────────────────────────────────────────────

    private fun buildTarget(state: GepaDashboardUiState): String =
        when (state.selectedTargetType) {
            GepaTargetType.SYSTEM_PROMPT -> "system_prompt"
            GepaTargetType.SKILL -> "skill:${state.selectedSkillName ?: return "system_prompt"}"
            GepaTargetType.PROFILE -> "profile:${state.selectedProfileId ?: return "system_prompt"}"
        }

    // ── Optimize ─────────────────────────────────────────────────────────────

    fun runOptimization() {
        val currentState = _state.value
        if (currentState.isOptimizing) return

        val target = buildTarget(currentState)
        val config = GepaOptimizeRequest(
            maxGenerations = currentState.maxGenerationsSetting.toInt(),
            populationSize = currentState.populationSizeSetting.toInt(),
            target = target,
        )

        _state.update { it.copy(isOptimizing = true, errorMessage = null, progress = 0f) }

        viewModelScope.launch {
            try {
                val response = client.startGepaOptimization(config)
                val jobId = response.jobId
                logger.info { "GEPA job started: $jobId (target=$target)" }
                _state.update {
                    it.copy(
                        activeJobId = jobId,
                        maxGenerations = config.maxGenerations,
                    )
                }
                startPolling(jobId)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to start GEPA optimization" }
                _state.update {
                    it.copy(
                        isOptimizing = false,
                        errorMessage = e.message ?: "Failed to start optimization",
                    )
                }
            }
        }
    }

    // ── Polling ──────────────────────────────────────────────────────────────

    private fun startPolling(jobId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                try {
                    val job = client.getGepaJobStatus(jobId)
                    _state.update {
                        it.copy(
                            progress = job.progress,
                            currentGeneration = job.currentGeneration,
                            maxGenerations = job.maxGenerations,
                            activeTargetLabel = job.targetLabel,
                        )
                    }

                    when (job.status) {
                        "completed" -> {
                            val result = job.result
                            if (result != null) {
                                val run = GepaRun(
                                    id = jobId,
                                    result = result,
                                    timestamp = Clock.System.now().toEpochMilliseconds(),
                                    config = GepaOptimizeRequest(
                                        maxGenerations = job.maxGenerations,
                                    ),
                                    targetLabel = job.targetLabel,
                                )
                                _state.update {
                                    it.copy(
                                        isOptimizing = false,
                                        progress = 1f,
                                        activeJobId = null,
                                        runs = listOf(run) + it.runs,
                                    )
                                }
                            } else {
                                _state.update {
                                    it.copy(
                                        isOptimizing = false,
                                        progress = 1f,
                                        activeJobId = null,
                                    )
                                }
                            }
                            return@launch
                        }

                        "failed" -> {
                            _state.update {
                                it.copy(
                                    isOptimizing = false,
                                    activeJobId = null,
                                    errorMessage = job.error ?: "Optimization failed",
                                )
                            }
                            return@launch
                        }

                        else -> {
                            // Still running — poll again after delay
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to poll GEPA job $jobId" }
                    // Don't stop polling on transient errors
                }
                delay(2000) // Poll every 2 seconds
            }
        }
    }

    // ── Init: Check for running job + load available targets ─────────────────

    init {
        checkForRunningJob()
        loadAvailableTargets()
    }

    private fun checkForRunningJob() {
        viewModelScope.launch {
            try {
                val running = client.getGepaCurrentJob()
                if (running != null && running.status == "running") {
                    logger.info { "Resuming GEPA job polling: ${running.jobId}" }
                    _state.update {
                        it.copy(
                            isOptimizing = true,
                            progress = running.progress,
                            currentGeneration = running.currentGeneration,
                            maxGenerations = running.maxGenerations,
                            activeJobId = running.jobId,
                            activeTargetLabel = running.targetLabel,
                        )
                    }
                    startPolling(running.jobId)
                }
                // Also load completed jobs as history
                loadJobHistory()
            } catch (e: Exception) {
                logger.debug(e) { "Could not check for running GEPA jobs" }
            }
        }
    }

    private fun loadAvailableTargets() {
        viewModelScope.launch {
            try {
                val skills = client.getSkills()
                val profiles = client.getAgentProfiles()
                _state.update {
                    it.copy(
                        availableSkills = skills.skills.map { s -> s.name },
                        availableProfiles = profiles.map { p -> p.id to p.name },
                    )
                }
            } catch (e: Exception) {
                logger.debug(e) { "Could not load available GEPA targets" }
            }
        }
    }

    private suspend fun loadJobHistory() {
        try {
            val jobs = client.getGepaJobs()
            val completedRuns = jobs
                .filter { it.status == "completed" && it.result != null }
                .map { job ->
                    GepaRun(
                        id = job.jobId,
                        result = job.result!!,
                        timestamp = job.startedAt,
                        config = GepaOptimizeRequest(maxGenerations = job.maxGenerations),
                        targetLabel = job.targetLabel,
                    )
                }
            if (completedRuns.isNotEmpty()) {
                _state.update { it.copy(runs = completedRuns) }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Could not load GEPA job history" }
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}
