package dev.promethe.app.network

import dev.promethe.api.ProviderRegistry

/**
 * Compatibility model list used only before the gateway is available during setup.
 * Runtime discovery belongs to GET /api/v1/providers/{id}/models; provider keys
 * are never sent from the UI to third-party catalog endpoints.
 */
object ModelFetcher {
    data class ModelEntry(
        val id: String,
        val promptPricePerM: Double? = null,
        val completionPricePerM: Double? = null,
    )

    data class FetchResult(
        val models: List<String>,
        val entries: List<ModelEntry> = emptyList(),
        val error: String? = null,
        val isReachable: Boolean = true,
    )

    fun knownPricing(modelId: String): Pair<Double, Double>? = null

    @Suppress("UNUSED_PARAMETER")
    suspend fun fetch(
        providerKey: String,
        apiKey: String = "",
        localUrl: String = "",
    ): FetchResult {
        val candidates = ProviderRegistry.get(providerKey)?.fallbackModels.orEmpty()
        return FetchResult(
            models = candidates,
            entries = candidates.map(::ModelEntry),
            error = if (candidates.isEmpty()) "Model discovery will run through the gateway after setup" else null,
            isReachable = false,
        )
    }
}
