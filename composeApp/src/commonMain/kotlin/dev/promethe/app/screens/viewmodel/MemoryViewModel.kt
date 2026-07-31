package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── UI Model ─────────────────────────────────────────────────────────────────

data class MemoryFactUiModel(
    val id: String,
    val userId: String,
    val category: String,
    val content: String,
    val confidence: Float,
    val tier: String,
    val createdAt: Long,
)

// ── UI State ─────────────────────────────────────────────────────────────────

data class MemoryUiState(
    val facts: List<MemoryFactUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val providerName: String = "",
    val factCount: Int = 0,
    val error: String? = null,
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Memory screen.
 *
 * Manages memory fact browsing, search, and deletion.
 * Injected via Koin: `koinViewModel<MemoryViewModel>()`
 */
class MemoryViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    init {
        load()
    }

    // ── State Mutation ───────────────────────────────────────────────────────

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                // Load status
                val statusJson = client.getJson("/api/v1/memory/status")
                val provider = statusJson["provider"]?.toString()?.removeSurrounding("\"") ?: "unknown"
                val count = statusJson["factCount"]?.toString()?.toIntOrNull() ?: 0

                // Load facts
                val factsJson = client.getJson("/api/v1/memory/facts")
                val facts = parseMemoryFacts(factsJson.toString())

                _state.update {
                    it.copy(
                        facts = facts,
                        providerName = provider,
                        factCount = count,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load memory" }
                _state.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    // ── Search ───────────────────────────────────────────────────────────────

    fun search() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val q = if (_state.value.searchQuery.isBlank()) "" else "?q=${_state.value.searchQuery}"
                val factsJson = client.getJson("/api/v1/memory/facts$q")
                val facts = parseMemoryFacts(factsJson.toString())
                _state.update { it.copy(facts = facts, isLoading = false) }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to search memory" }
                _state.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    // ── Delete Fact ──────────────────────────────────────────────────────────

    fun deleteFact(id: String) {
        viewModelScope.launch {
            try {
                client.delete("/api/v1/memory/facts/$id")
                _state.update {
                    it.copy(
                        facts = it.facts.filter { f -> f.id != id },
                        factCount = (it.factCount - 1).coerceAtLeast(0),
                    )
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to delete memory fact $id" }
                _state.update { it.copy(error = "Failed to delete fact: ${e.message}") }
            }
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    @Serializable
    private data class MemoryFactsResponseParsed(
        val facts: List<MemoryFactParsed> = emptyList(),
        val total: Int = 0,
    )

    @Serializable
    private data class MemoryFactParsed(
        val id: String = "",
        val userId: String = "default",
        val category: String = "",
        val content: String = "",
        val confidence: Float = 1.0f,
        val tier: String = "ATOMIC",
        val createdAt: Long = 0,
    )

    private fun parseMemoryFacts(json: String): List<MemoryFactUiModel> =
        try {
            val parsed = jsonParser.decodeFromString<MemoryFactsResponseParsed>(json)
            parsed.facts.map {
                MemoryFactUiModel(
                    id = it.id,
                    userId = it.userId,
                    category = it.category,
                    content = it.content,
                    confidence = it.confidence,
                    tier = it.tier,
                    createdAt = it.createdAt,
                )
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse memory facts JSON" }
            emptyList()
        }
}
