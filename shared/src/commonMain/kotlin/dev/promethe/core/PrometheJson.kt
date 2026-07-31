package dev.promethe.core

import kotlinx.serialization.json.Json

/**
 * Shared [Json] singleton for the entire Prométhé project.
 *
 * All modules should use this instance instead of creating ad-hoc `Json { ... }` blocks.
 * This ensures consistent serialization behavior across the codebase.
 *
 * Configuration:
 * - [ignoreUnknownKeys] = true  → forward-compatible deserialization
 * - [coerceInputValues] = true  → gracefully handle nulls in non-null fields
 * - [encodeDefaults] = true     → always emit default values (important for API contracts)
 * - [prettyPrint] = false       → compact output for production
 * - [isLenient] = true          → accept unquoted strings and trailing commas
 */
val PrometheJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
    prettyPrint = false
    isLenient = true
}

/**
 * Pretty-printing variant for user-facing output (credentials file, debug logs).
 */
val PromethePrettyJson: Json = Json(from = PrometheJson) {
    prettyPrint = true
}
