package dev.promethe.app.util

import kotlinx.serialization.json.Json

/**
 * Shared [Json] configuration for the UI layer, matching the core server settings.
 */
val PrometheJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    prettyPrint = false
    isLenient = true
}
