package dev.promethe.app.screens.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.promethe.api.RagDocumentInfo
import dev.promethe.app.network.PrometheClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import promethe.composeapp.generated.resources.Res
import promethe.composeapp.generated.resources.common_not_configured

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── UI Data Models ───────────────────────────────────────────────────────────

data class DocumentUi(
    val id: String,
    val filename: String,
    val chunkCount: Int,
    val totalTokens: Int,
    val ingestedAt: Long,
)

data class SearchResultUi(
    val content: String,
    val score: Double,
    val source: String,
    val heading: String,
)

// ── UI State ─────────────────────────────────────────────────────────────────

data class KnowledgeUiState(
    val documents: List<DocumentUi> = emptyList(),
    val searchResults: List<SearchResultUi> = emptyList(),
    val isLoading: Boolean = true,
    val isSearching: Boolean = false,
    val searchQuery: String = "",
    val error: String? = null,
    val totalChunks: Int = 0,
    val embeddingOk: Boolean = false,
    val vectorStoreOk: Boolean = false,
    val embeddingProvider: String = "",
    val embeddingModel: String = "",
    val vectorStoreType: String = "",
    val showSearchResults: Boolean = false,
)

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * ViewModel for the Knowledge (RAG) screen.
 *
 * Manages document listing, semantic search, ingestion status,
 * and vector store / embedding connectivity status.
 */
class KnowledgeViewModel(
    private val client: PrometheClient,
) : ViewModel() {
    private val _state = MutableStateFlow(KnowledgeUiState())
    val state: StateFlow<KnowledgeUiState> = _state.asStateFlow()

    init {
        loadDocuments()
    }

    // ── State Mutation ───────────────────────────────────────────────────────

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun clearSearchResults() {
        _state.update { it.copy(showSearchResults = false, searchResults = emptyList()) }
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    fun loadDocuments() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val config = client.getRagConfig()
                val docs = client.getRagDocuments().map { doc ->
                    DocumentUi(
                        id = doc.id,
                        filename = doc.filename,
                        chunkCount = doc.chunkCount,
                        totalTokens = doc.totalTokens,
                        ingestedAt = doc.ingestedAt,
                    )
                }
                _state.update {
                    it.copy(
                        documents = docs,
                        totalChunks = docs.sumOf { d -> d.chunkCount },
                        embeddingOk = true,
                        vectorStoreOk = true,
                        embeddingProvider = config.embeddingProvider.name,
                        embeddingModel = config.embeddingModel.ifBlank { "-" },
                        vectorStoreType = config.vectorStoreType.name,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load RAG documents" }
                val notConfigured = getString(Res.string.common_not_configured)
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = e.message,
                        embeddingProvider = notConfigured,
                        embeddingModel = "-",
                        vectorStoreType = notConfigured,
                    )
                }
            }
        }
    }

    // ── Search ───────────────────────────────────────────────────────────────

    fun performSearch() {
        val query = _state.value.searchQuery
        if (query.isBlank()) return

        _state.update { it.copy(isSearching = true, showSearchResults = true) }
        viewModelScope.launch {
            try {
                val results = client.searchKnowledge(query = query)
                _state.update {
                    it.copy(
                        searchResults = results.results.map { r ->
                            SearchResultUi(
                                content = r.content,
                                score = r.score,
                                source = r.source,
                                heading = r.metadata["heading"] ?: "",
                            )
                        },
                        isSearching = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message, isSearching = false) }
            }
        }
    }

    // ── Delete Document ──────────────────────────────────────────────────────

    fun deleteDocument(docId: String) {
        viewModelScope.launch {
            try {
                client.delete("/api/v1/rag/documents/$docId")
                _state.update { it.copy(documents = it.documents.filter { d -> d.id != docId }) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }
}
