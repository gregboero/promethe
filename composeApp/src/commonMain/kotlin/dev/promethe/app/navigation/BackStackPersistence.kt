package dev.promethe.app.navigation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wrapper for serializing the back stack to/from JSON.
 * Uses polymorphic serialization via the sealed interface.
 */
@Serializable
private data class BackStackState(
    val routes: List<PrometheRoute>,
)

private val backStackJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Platform-agnostic back stack persistence.
 *
 * Serializes the current route stack to JSON and delegates
 * actual storage to platform-specific implementations.
 */
object BackStackPersistence {
    /**
     * Save the current back stack to persistent storage.
     * Call this from a LaunchedEffect whenever the back stack changes.
     */
    fun save(routes: List<PrometheRoute>) {
        try {
            val json = backStackJson.encodeToString(BackStackState(routes))
            PlatformStorage.write(KEY, json)
        } catch (_: Exception) {
            // Silently ignore serialization/storage failures
        }
    }

    /**
     * Restore the back stack from persistent storage.
     * Returns null if no saved state exists or deserialization fails.
     */
    fun restore(): List<PrometheRoute>? {
        return try {
            val json = PlatformStorage.read(KEY) ?: return null
            val state = backStackJson.decodeFromString<BackStackState>(json)
            state.routes.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /** Clear saved back stack state. */
    fun clear() {
        PlatformStorage.remove(KEY)
    }

    private const val KEY = "promethe_backStack"
}
