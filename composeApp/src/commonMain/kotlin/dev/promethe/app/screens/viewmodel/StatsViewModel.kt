package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.api.GepaResultDto
import dev.promethe.api.ProvidersResponse
import dev.promethe.api.StatsResponse
import dev.promethe.api.SystemStatusResponse
import dev.promethe.app.network.PrometheClient
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── UI State ─────────────────────────────────────────────────────────────────

data class StatsUiState(
    val stats: StatsResponse = StatsResponse(),
    val systemStatus: SystemStatusResponse? = null,
    val providers: ProvidersResponse? = null,
    val gepaResult: GepaResultDto? = null,
    val isLoadingStats: Boolean = true,
    val isOptimizing: Boolean = false,
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Stats / Dashboard screen.
 *
 * Manages stats loading, system status, provider health,
 * GEPA optimization trigger, and auto-refresh every 30 seconds.
 */
class StatsViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    private var autoRefreshJob: Job? = null

    init {
        loadAll()
        startAutoRefresh()
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    fun loadAll() {
        viewModelScope.launch {
            try {
                val s = client.getStats()
                _state.update { it.copy(stats = s) }
            } catch (e: Exception) {
                logger.warn(e) { "Échec chargement stats" }
            }
            try {
                val ss = client.getSystemStatus()
                _state.update { it.copy(systemStatus = ss) }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load system status" }
            }
            try {
                val p = client.getProviderHealth()
                _state.update { it.copy(providers = p) }
            } catch (e: Exception) {
                logger.warn(e) { "Échec chargement providers" }
            }
            _state.update { it.copy(isLoadingStats = false) }
        }
    }

    // ── GEPA Optimize ────────────────────────────────────────────────────────

    fun runGepaOptimization() {
        _state.update { it.copy(isOptimizing = true) }
        viewModelScope.launch {
            try {
                val response = client.startGepaOptimization()
                val jobId = response.jobId
                // Poll until completed or failed
                while (true) {
                    kotlinx.coroutines.delay(2000)
                    val job = client.getGepaJobStatus(jobId)
                    when (job.status) {
                        "completed" -> {
                            _state.update { it.copy(gepaResult = job.result, isOptimizing = false) }
                            return@launch
                        }

                        "failed" -> {
                            logger.warn { "GEPA job $jobId failed: ${job.error}" }
                            _state.update { it.copy(isOptimizing = false) }
                            return@launch
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "GEPA optimization failed" }
                _state.update { it.copy(isOptimizing = false) }
            }
        }
    }

    // ── Auto-Refresh ─────────────────────────────────────────────────────────

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                delay(30_000)
                loadAll()
            }
        }
    }

    override fun onCleared() {
        autoRefreshJob?.cancel()
        super.onCleared()
    }
}
